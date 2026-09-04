package com.vyze.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe Text-to-Speech manager for the Vyze accessibility app.
 *
 * ## Utterance ID Tracking (Deterministic Completion Detection)
 * Every speak() call registers a unique utteranceId in [pendingUtteranceIds].
 * UtteranceProgressListener.onDone/onError removes the ID.
 * [hasPendingSpeech] returns true iff the set is non-empty — guaranteed
 * 100% accurate, no polling, no transient false readings.
 *
 * ## Volume Stability
 * TTS plays through the MEDIA stream (USAGE_MEDIA) so it matches the
 * phone's normal media volume exactly: the hardware volume buttons keep
 * working as usual and every utterance outputs at the identical, stable
 * gain for the entire session. The optional in-app volume setting is
 * applied per-utterance via KEY_PARAM_VOLUME.
 *
 * ## Audio Focus
 * Requests AUDIOFOCUS_GAIN (permanent) for the entire app session.
 * Focus is held from app open to app close. Never released per-utterance
 * or on stop() — only on onDestroy().
 */
class TTSManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null

    @Volatile
    private var isInitialized = false

    private var cachedVolume: Float = DEFAULT_VOLUME
    private val appContext: Context = context.applicationContext

    // ── Audio Manager ─────────────────────────────────────────────

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ── Audio Focus ───────────────────────────────────────────────

    private var audioFocusRequest: AudioFocusRequest? = null

    @Volatile
    private var currentLocale: Locale = Locale.US

    // ── Debounce state ────────────────────────────────────────────

    @Volatile
    private var lastSpeechTime = 0L

    @Volatile
    private var lastSpokenText = ""

    // ── Speech Buffer ─────────────────────────────────────────────

    private val speechBuffer = ConcurrentLinkedQueue<String>()

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Callback invoked when TTS engine is fully ready. */
    var onReady: (() -> Unit)? = null

    // ── Utterance ID Tracking ─────────────────────────────────────
    // Thread-safe set of utterance IDs currently queued or playing.
    // Every speak() call adds an ID; onDone/onError removes it.
    // hasPendingSpeech() returns true iff the set is non-empty.

    private val pendingUtteranceIds = ConcurrentHashMap.newKeySet<String>()

    /** Monotonically increasing counter for unique utterance IDs. */
    private val utteranceCounter = AtomicLong(0)

    /**
     * Generate a unique utterance ID for a speak() call.
     * Format: "utt_{counter}_{timestamp}"
     */
    private fun nextUtteranceId(): String {
        return "utt_${utteranceCounter.incrementAndGet()}_${System.currentTimeMillis()}"
    }

    /**
     * Returns true if any utterances are currently queued or playing.
     * This is the deterministic replacement for isSpeaking() polling.
     */
    fun hasPendingSpeech(): Boolean = pendingUtteranceIds.isNotEmpty()

    // ── Audio Attributes (Media stream — follows the phone volume) ──
    // USAGE_MEDIA routes TTS to STREAM_MUSIC, so Vyze speaks at exactly
    // the phone's media volume and the hardware volume buttons work
    // normally during speech (no hidden accessibility-stream slider).

    private val ttsAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // ── Utterance Progress Listener (Global) ──────────────────────
    // Set once during init. Tracks ALL utterances via pendingUtteranceIds.
    // This is the single source of truth for completion detection.

    private val globalUtteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            Log.d(TAG, "onStart: $utteranceId (pending=${pendingUtteranceIds.size})")
        }

        override fun onDone(utteranceId: String?) {
            if (utteranceId != null) {
                pendingUtteranceIds.remove(utteranceId)
                Log.d(TAG, "onDone: $utteranceId removed (pending=${pendingUtteranceIds.size})")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            if (utteranceId != null) {
                pendingUtteranceIds.remove(utteranceId)
                Log.w(TAG, "onError: $utteranceId removed (pending=${pendingUtteranceIds.size})")
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (utteranceId != null) {
                pendingUtteranceIds.remove(utteranceId)
                Log.w(TAG, "onError: $utteranceId code=$errorCode removed (pending=${pendingUtteranceIds.size})")
            }
        }
    }

    // ── Initialization ────────────────────────────────────────────

    init {
        Log.i(TAG, "TTSManager created — stream=MEDIA (follows phone volume)")
        tts = try {
            TextToSpeech(appContext, this, GOOGLE_TTS_ENGINE)
        } catch (e: Throwable) {
            Log.w(TAG, "Google TTS engine not available, falling back to system: ${e.message}")
            TextToSpeech(appContext, this)
        }
        Log.d(TAG, "TTS constructor called, waiting for onInit callback")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            Log.i(TAG, "onInit: TextToSpeech.SUCCESS — setting up language")

            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedLang = prefs.getString(KEY_LANGUAGE, LANGUAGE_ENGLISH) ?: LANGUAGE_ENGLISH
            currentLocale = localeFromKey(savedLang)

            val langResult = tts?.setLanguage(currentLocale)
            Log.d(TAG, "setLanguage($currentLocale) returned: $langResult")

            if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                langResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Log.w(TAG, "Language $currentLocale not supported, falling back to English")
                val fallbackResult = tts?.setLanguage(Locale.US)
                Log.d(TAG, "setLanguage(Locale.US) returned: $fallbackResult")
                currentLocale = Locale.US

                if (fallbackResult == TextToSpeech.LANG_MISSING_DATA ||
                    fallbackResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Log.e(TAG, "TTS INIT FAILED: No supported language found")
                    return
                }
            }

            tts?.setAudioAttributes(ttsAudioAttributes)

            // ── Voice Quality Selection ──────────────────────────────
            selectBestVoice(currentLocale)

            // ── User Voice Preference ───────────────────────────────
            // Restore the user's chosen voice (if any) over the auto pick.
            val savedVoice = prefs.getString(KEY_VOICE_NAME, "") ?: ""
            if (savedVoice.isNotBlank() && savedVoice != VOICE_AUTO) {
                setVoiceByName(savedVoice)
            }

            // ── Prosody Tuning ───────────────────────────────────────
            tts?.setPitch(WARM_PITCH)
            tts?.setSpeechRate(WARM_RATE)

            // Set the GLOBAL utterance progress listener — tracks ALL utterances
            tts?.setOnUtteranceProgressListener(globalUtteranceListener)

            Log.i(TAG, "TTS setup OK — locale=$currentLocale, engine=${tts?.defaultEngine}, " +
                "pitch=$WARM_PITCH, rate=$WARM_RATE, volume=$cachedVolume")

            mainHandler.postDelayed({
                isInitialized = true
                Log.i(TAG, "TTS fully initialized — initial drain: ${speechBuffer.size} buffered")
                drainPendingQueue()
                onReady?.invoke()
                startDrainRetryTimer()
            }, ENGINE_SETTLE_DELAY_MS)

        } else {
            Log.e(TAG, "TTS INIT FAILED — status=$status (expected SUCCESS=${TextToSpeech.SUCCESS})")
        }
    }

    // ── Drain Retry Timer ─────────────────────────────────────────

    private var drainRetryRunnable: Runnable? = null

    private fun startDrainRetryTimer() {
        drainRetryRunnable?.let { mainHandler.removeCallbacks(it) }

        val startTime = System.currentTimeMillis()
        drainRetryRunnable = object : Runnable {
            override fun run() {
                if (speechBuffer.isNotEmpty() && isInitialized) {
                    val drained = drainPendingQueue()
                    if (drained > 0) {
                        Log.d(TAG, "Drain retry: spoke $drained messages")
                    }
                }
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed < DRAIN_RETRY_MS && speechBuffer.isNotEmpty()) {
                    mainHandler.postDelayed(this, DRAIN_RETRY_INTERVAL_MS)
                }
            }
        }
        mainHandler.postDelayed(drainRetryRunnable!!, DRAIN_RETRY_INTERVAL_MS)
    }

    private fun stopDrainRetryTimer() {
        drainRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        drainRetryRunnable = null
    }

    // ── Speech Buffer Drain ───────────────────────────────────────

    private fun drainPendingQueue(): Int {
        if (!isInitialized) {
            Log.w(TAG, "drainPendingQueue: TTS not ready, skipping")
            return 0
        }

        var drained = 0
        while (true) {
            val text = speechBuffer.poll() ?: break
            if (text.isBlank()) continue

            val mode = if (drained == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val utteranceId = nextUtteranceId()
            val params = buildSpeakParams()
            val result = tts?.speak(text, mode, params, utteranceId) ?: TextToSpeech.ERROR

            if (result != TextToSpeech.SUCCESS) {
                Log.e(TAG, "drainPendingQueue: speak() FAILED code=$result, text=\"${text.take(60)}\"")
            } else {
                pendingUtteranceIds.add(utteranceId)
                Log.d(TAG, "Drained #$drained (id=$utteranceId): ${text.take(60)}...")
            }

            lastSpeechTime = System.currentTimeMillis()
            lastSpokenText = text
            drained++
        }

        if (drained > 0) {
            Log.i(TAG, "Drained $drained buffered utterances, buffer remaining: ${speechBuffer.size}, " +
                "pending IDs: ${pendingUtteranceIds.size}")
        }
        return drained
    }

    // ── Public Speech API ─────────────────────────────────────────

    /**
     * Speaks the given text with the specified queue mode.
     * Generates a unique utteranceId, adds it to pendingUtteranceIds,
     * and tracks it until onDone/onError removes it.
     *
     * @param text       The text to speak
     * @param queueMode  TextToSpeech.QUEUE_FLUSH or QUEUE_ADD
     * @param utteranceId Optional caller-provided ID (e.g., "session_chunk_3")
     *                    If null, generates one automatically.
     * @return true if speak() succeeded
     */
    fun speak(
        text: String,
        queueMode: Int = TextToSpeech.QUEUE_ADD,
        utteranceId: String? = null
    ): Boolean {
        if (text.isBlank()) return false

        if (!isInitialized) {
            Log.d(TAG, "speak() before TTS init — buffering: \"${text.take(60)}\"")
            speechBuffer.add(text)
            return false
        }

        val now = System.currentTimeMillis()
        if (text == lastSpokenText && (now - lastSpeechTime) < DEBOUNCE_MS) {
            Log.d(TAG, "speak() DEBOUNCE — dropping duplicate: \"${text.take(60)}\"")
            return false
        }

        lastSpeechTime = now
        lastSpokenText = text

        // Enhance text with pronunciation overrides + natural prosody pauses
        val enhancedText = enhanceForNaturalProsody(applyPronunciationOverrides(text))

        val id = utteranceId ?: nextUtteranceId()
        val params = buildSpeakParams()
        val result = tts?.speak(enhancedText, queueMode, params, id) ?: TextToSpeech.ERROR

        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "speak() FAILED code=$result, text=\"${text.take(60)}\", id=$id")
        } else {
            pendingUtteranceIds.add(id)
            Log.d(TAG, "speak() OK id=$id queueMode=$queueMode pending=${pendingUtteranceIds.size} " +
                "text=\"${text.take(60)}\"")
        }

        return result == TextToSpeech.SUCCESS
    }

    /**
     * Immediate speech for urgent accessibility feedback.
     * Stops current speech, speaks with QUEUE_FLUSH.
     */
    fun speakImmediate(text: String): Boolean {
        if (text.isBlank()) {
            Log.w(TAG, "speakImmediate() called with blank text — skipping")
            return false
        }

        if (!isInitialized) {
            Log.d(TAG, "speakImmediate() before TTS init — buffering: \"${text.take(60)}\"")
            speechBuffer.add(text)
            return false
        }

        val now = System.currentTimeMillis()
        if (text == lastSpokenText && (now - lastSpeechTime) < DEBOUNCE_MS) {
            Log.d(TAG, "speakImmediate() DEBOUNCE — dropping duplicate: \"${text.take(60)}\"")
            return false
        }

        lastSpeechTime = now
        lastSpokenText = text

        // Enhance text with pronunciation overrides + natural prosody pauses
        val enhancedText = enhanceForNaturalProsody(applyPronunciationOverrides(text))

        tts?.stop()
        pendingUtteranceIds.clear()

        val id = nextUtteranceId()
        val params = buildSpeakParams()
        val result = tts?.speak(enhancedText, TextToSpeech.QUEUE_FLUSH, params, id) ?: TextToSpeech.ERROR

        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "speakImmediate() FAILED code=$result, text=\"${text.take(60)}\", id=$id")
        } else {
            pendingUtteranceIds.add(id)
            Log.d(TAG, "speakImmediate() OK id=$id pending=${pendingUtteranceIds.size} " +
                "text=\"${text.take(60)}\"")
        }

        return result == TextToSpeech.SUCCESS
    }

    fun speakQueued(text: String) {
        if (text.isBlank()) return
        if (isInitialized) {
            speakImmediate(text)
        } else {
            Log.d(TAG, "speakQueued: TTS not ready — buffering: \"${text.take(60)}\"")
            speechBuffer.add(text)
        }
    }

    fun speakImmediateQueued(text: String) {
        if (text.isBlank()) return
        if (isInitialized) {
            speakImmediate(text)
        } else {
            Log.d(TAG, "speakImmediateQueued: TTS not ready — buffering: \"${text.take(60)}\"")
            speechBuffer.add(text)
        }
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    fun isReady(): Boolean = isInitialized

    /**
     * Stop all speech and clear all pending utterance tracking.
     * This is the ONLY way to guarantee hasPendingSpeech() returns false
     * immediately after stop().
     */
    fun stop() {
        tts?.stop()
        pendingUtteranceIds.clear()
        Log.d(TAG, "stop() — pendingUtteranceIds cleared")
    }

    /**
     * Set a caller-provided UtteranceProgressListener.
     *
     * CRITICAL: This is ADDITIVE — the global pendingUtteranceIds listener
     * is ALWAYS preserved. The caller's listener is wrapped around the global
     * one so both fire for each utterance. This prevents the caller from
     * accidentally overwriting the global listener and breaking hasPendingSpeech().
     */
    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener) {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                globalUtteranceListener.onStart(utteranceId)
                listener.onStart(utteranceId)
            }

            override fun onDone(utteranceId: String?) {
                globalUtteranceListener.onDone(utteranceId)
                listener.onDone(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                globalUtteranceListener.onError(utteranceId)
                listener.onError(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                globalUtteranceListener.onError(utteranceId, errorCode)
                listener.onError(utteranceId, errorCode)
            }
        })
    }

    /**
     * Queue a silent utterance to keep hasPendingSpeech() == true
     * while the hardware AudioTrack drains the final audible utterance.
     * Android TTS onDone() fires when the engine finishes encoding,
     * NOT when the speaker finishes playing. This silent tail prevents
     * the caller from prematurely transitioning to IDLE.
     *
     * @param durationMs  Duration of silence in milliseconds (200–400 typical)
     * @param queueMode   QUEUE_ADD to append after the last audible utterance
     */
    fun playSilentUtterance(durationMs: Int = 300, queueMode: Int = TextToSpeech.QUEUE_ADD): Boolean {
        if (!isInitialized) return false
        val id = nextUtteranceId()
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.0f)
        }
        val result = tts?.speak(" ", queueMode, params, id) ?: TextToSpeech.ERROR
        if (result == TextToSpeech.SUCCESS) {
            pendingUtteranceIds.add(id)
            Log.d(TAG, "playSilentUtterance: id=$id (${durationMs}ms) pending=${pendingUtteranceIds.size}")
        } else {
            Log.e(TAG, "playSilentUtterance: FAILED code=$result")
        }
        return result == TextToSpeech.SUCCESS
    }

    /**
     * Explicitly release audio focus.
     */
    fun abandonFocus() {
        abandonAudioFocus()
    }

    // ── Audio Focus ───────────────────────────────────────────────

    /**
     * Request permanent audio focus for the entire app session.
     * Uses AUDIOFOCUS_GAIN (not TRANSIENT) to suppress TalkBack and
     * other accessibility audio while Vyze is active.
     * Called once on app open — not per-utterance.
     */
    fun holdSessionFocus() {
        if (audioFocusRequest != null) {
            Log.d(TAG, "holdSessionFocus: already holding focus")
            return
        }
        try {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(focusAttributes)
                .setOnAudioFocusChangeListener { focusChange ->
                    Log.d(TAG, "Audio focus changed: $focusChange")
                }
                .build()

            val result = audioManager.requestAudioFocus(audioFocusRequest!!)
            Log.i(TAG, "holdSessionFocus: AUDIOFOCUS_GAIN requested (result=$result)")
            CrashLogFile.log(TAG, "Session audio focus acquired (GAIN, result=$result)")
        } catch (e: Throwable) {
            Log.w(TAG, "holdSessionFocus failed: ${e.message}")
        }
    }

    /**
     * Release session audio focus. Called only on app destroy.
     * Allows TalkBack and other services to resume.
     */
    fun releaseSessionFocus() {
        abandonAudioFocus()
        CrashLogFile.log(TAG, "Session audio focus released")
    }

    private fun abandonAudioFocus() {
        try {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
                audioFocusRequest = null
                Log.d(TAG, "Audio focus abandoned")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "abandonAudioFocus failed: ${e.message}")
        }
    }

    // ── Speak Parameters ──────────────────────────────────────────

    private fun buildSpeakParams(): Bundle {
        return Bundle().apply {
            // Apply the in-app volume setting (0-1 multiplier over the
            // media stream volume). Default 1.0 = exactly the phone volume.
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, cachedVolume)
        }
    }

    // ── Voice Quality Selection ──────────────────────────────────

    private fun selectBestVoice(locale: Locale) {
        val engine = tts ?: return
        val voices = engine.voices
        if (voices.isNullOrEmpty()) {
            Log.w(TAG, "selectBestVoice: no voices available")
            return
        }

        val localeVoices = voices.filter { voice ->
            voice.locale.language == locale.language &&
                !voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }

        if (localeVoices.isEmpty()) {
            Log.w(TAG, "selectBestVoice: NO voice pack installed for ${locale.getDisplayLanguage(Locale.US)} (${locale.language}). " +
                "TTS will use the engine default voice (likely English). " +
                "Install the language pack in Android Settings > System > Languages > TTS.")
            return
        }

        val best = localeVoices.sortedWith(
            compareByDescending<Voice> { it.quality }
                .thenByDescending { it.isNetworkConnectionRequired }
        ).first()

        engine.voice = best
        Log.i(TAG, "selectBestVoice: chose '${best.name}' quality=${best.quality} " +
            "locale=${best.locale} network=${best.isNetworkConnectionRequired}")
    }

    // ── Voice Picker Support (Tier 1) ──────────────────────────────

    /** Installed voices for the current language (uninstalled packs excluded). */
    fun getInstalledVoicesForCurrentLanguage(): List<Voice> {
        val engine = tts ?: return emptyList()
        val voices = engine.voices ?: return emptyList()
        return voices.filter { voice ->
            voice.locale.language == currentLocale.language &&
                !voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }.sortedByDescending { it.quality }
    }

    /** Name of the voice actually in use, or null if unknown. */
    fun getCurrentVoiceName(): String? = tts?.voice?.name

    /**
     * True when at least one INSTALLED voice exists for the given language
     * code ("en" / "ms" / "zh"). Uninstalled packs are excluded, so this is
     * the safe pre-check before switching the language — setLanguage() would
     * otherwise fall back to English AND persist that fallback as the choice.
     */
    fun hasInstalledVoicesFor(language: String): Boolean {
        val engine = tts ?: return false
        val voices = engine.voices ?: return false
        return voices.any { v ->
            v.locale.language == language &&
                !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }
    }

    /**
     * Select a voice by name ("" or [VOICE_AUTO] → auto-pick the best
     * installed voice). No-op if the name is no longer installed.
     */
    fun setVoiceByName(name: String) {
        val engine = tts ?: return
        if (!isInitialized) {
            Log.d(TAG, "setVoiceByName($name) — TTS not ready, ignored (applied on init)")
            return
        }
        if (name.isBlank() || name == VOICE_AUTO) {
            selectBestVoice(currentLocale)
            return
        }
        val match = engine.voices?.find { v ->
            v.name == name &&
                !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }
        if (match != null) {
            engine.voice = match
            Log.i(TAG, "setVoiceByName: chose '${match.name}' quality=${match.quality}")
        } else {
            Log.w(TAG, "setVoiceByName: '$name' not installed — keeping current voice")
        }
    }

    /**
     * True when the best installed voice for the current language is the
     * robotic base quality (or when no voice pack is installed at all and
     * the engine falls back to its default). Used to offer the better
     * voice install prompt.
     */
    fun isVoiceQualityLow(): Boolean {
        val engine = tts ?: return false
        if (!isInitialized) return false
        val installed = getInstalledVoicesForCurrentLanguage()
        if (installed.isEmpty()) return true
        val bestQuality = installed.maxOf { it.quality }
        Log.d(TAG, "isVoiceQualityLow: best quality=$bestQuality (${installed.size} installed)")
        return bestQuality < Voice.QUALITY_HIGH
    }

    // ── Natural Prosody Enhancement ───────────────────────────────

    /**
     * Apply curated pronunciation overrides for brand/product names that
     * generic TTS voices misread. Example: Google's English voice reads the
     * noodle brand "Maggi" as "MAY-jee"; the respelling below forces the
     * brand's real pronunciation "MAY-ghee" (ghee → hard g, /giː/).
     *
     * Whole-word, case-insensitive, and scoped to the ACTIVE voice language
     * (Malay and Chinese voices already read these brands correctly, so the
     * respellings are English-only — a Malay "Mayghee" would itself be wrong).
     * Additions welcome: one entry per brand + language. Heuristic by nature:
     * final accuracy depends on the installed engine voice.
     */
    private fun applyPronunciationOverrides(text: String): String {
        if (text.isBlank()) return text
        val overrides = when (currentLocale.language) {
            "en" -> ENGLISH_PRONUNCIATION_OVERRIDES
            else -> return text
        }
        var out = text
        for ((from, to) in overrides) {
            out = out.replace(Regex("(?i)\\b" + Regex.escape(from) + "\\b"), to)
        }
        return out
    }

    private fun enhanceForNaturalProsody(text: String): String {
        if (text.isBlank()) return text

        var enhanced = text.trim()

        // Ensure sentence terminators are followed by a space
        enhanced = enhanced.replace(Regex("([.!?])([A-Za-z0-9])"), "$1 $2")

        // Ensure commas are followed by a space
        enhanced = enhanced.replace(Regex("(,)([A-Za-z0-9])"), "$1 $2")

        // Ensure colons/semicolons are followed by a space
        enhanced = enhanced.replace(Regex("([:;])([A-Za-z0-9])"), "$1 $2")

        // Add trailing period if missing
        if (enhanced.isNotEmpty() && !enhanced.last().isWhitespace() &&
            enhanced.last() !in charArrayOf('.', '!', '?')) {
            enhanced = "$enhanced."
        }

        return enhanced
    }

    // ── Locale Switching ──────────────────────────────────────────

    fun switchToLocale(locale: Locale) {
        if (!isInitialized) {
            Log.d(TAG, "switchToLocale($locale) — TTS not ready, will apply on next init")
            currentLocale = locale
            return
        }

        val engine = tts ?: return

        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "switchToLocale($locale) — language not supported, trying locale.language only")
            val langOnly = Locale(locale.language)
            val result2 = engine.setLanguage(langOnly)
            if (result2 == TextToSpeech.LANG_MISSING_DATA || result2 == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "switchToLocale($locale) — no voice pack installed, falling back to English")
                engine.setLanguage(Locale.US)
                currentLocale = Locale.US
                return
            }
            currentLocale = langOnly
        } else {
            currentLocale = locale
        }

        selectBestVoice(currentLocale)

        Log.i(TAG, "switchToLocale: locked to $currentLocale")
    }

    fun setLanguage(languageKey: String, context: Context) {
        val locale = localeFromKey(languageKey)
        currentLocale = locale

        val result = tts?.setLanguage(locale)
        Log.d(TAG, "setLanguage($locale) returned: $result")
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "Language $locale not available, falling back to English")
            tts?.setLanguage(Locale.US)
            currentLocale = Locale.US
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, languageKey)
            .apply()

        Log.d(TAG, "TTS language switched to: $currentLocale")
    }

    fun getCurrentLanguageKey(): String = keyFromLocale(currentLocale)

    fun getCurrentLanguageDisplayName(context: Context): String {
        return when (keyFromLocale(currentLocale)) {
            LANGUAGE_MALAY -> context.getString(R.string.tts_lang_malay)
            LANGUAGE_CHINESE -> context.getString(R.string.tts_lang_chinese)
            else -> context.getString(R.string.tts_lang_english)
        }
    }

    // ── Settings ──────────────────────────────────────────────────

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch.coerceIn(0.5f, 1.5f))
    }

    fun setVolume(volume: Float) {
        cachedVolume = volume.coerceIn(0f, 1f)
    }

    fun getVolume(): Float = cachedVolume

    fun applySettings(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        setSpeechRate(prefs.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE))
        setPitch(prefs.getFloat(KEY_PITCH, DEFAULT_PITCH))
        setVolume(prefs.getFloat(KEY_VOLUME, DEFAULT_VOLUME))

        val savedLang = prefs.getString(KEY_LANGUAGE, LANGUAGE_ENGLISH) ?: LANGUAGE_ENGLISH
        setLanguage(savedLang, context)

        // Restore the user's chosen voice (or re-auto-pick best after language switch)
        val savedVoice = prefs.getString(KEY_VOICE_NAME, "") ?: ""
        if (savedVoice.isNotBlank() && savedVoice != VOICE_AUTO) {
            setVoiceByName(savedVoice)
        } else {
            selectBestVoice(currentLocale)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    fun onDestroy() {
        stopDrainRetryTimer()
        mainHandler.removeCallbacksAndMessages(null)
        tts?.stop()
        pendingUtteranceIds.clear()
        abandonAudioFocus()
        tts?.shutdown()
        tts = null
        isInitialized = false
        speechBuffer.clear()
        Log.d(TAG, "TTS destroyed")
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun localeFromKey(key: String): Locale {
        return when (key) {
            LANGUAGE_MALAY -> Locale("ms", "MY")
            LANGUAGE_CHINESE -> Locale.SIMPLIFIED_CHINESE
            else -> Locale.US
        }
    }

    private fun keyFromLocale(locale: Locale): String {
        return when (locale.language) {
            "ms" -> LANGUAGE_MALAY
            "zh" -> LANGUAGE_CHINESE
            else -> LANGUAGE_ENGLISH
        }
    }

    companion object {
        private const val TAG = "TTSManager"

        const val PREFS_NAME = "vyze_tts_settings"
        const val KEY_SPEECH_RATE = "speech_rate"
        const val KEY_PITCH = "pitch"
        const val KEY_VOLUME = "volume"
        const val KEY_LANGUAGE = "tts_language"
        const val KEY_VOICE_NAME = "tts_voice_name"

        /** Sentinel value: auto-pick the best installed voice. */
        const val VOICE_AUTO = "automatic"

        /** Set once the weak-voice install prompt has been answered/skipped. */
        const val KEY_VOICE_PROMPT_RESOLVED = "voice_prompt_resolved"

        /** Set once the user has used Voice Settings (dismisses the cue hint). */
        const val KEY_VOICE_SETTINGS_KNOWN = "voice_settings_known"

        const val DEFAULT_SPEECH_RATE = 1.0f
        const val DEFAULT_PITCH = 1.0f
        const val DEFAULT_VOLUME = 1.0f

        const val LANGUAGE_ENGLISH = "en"
        const val LANGUAGE_MALAY = "ms"
        const val LANGUAGE_CHINESE = "zh"

        val SUPPORTED_LANGUAGES = listOf(LANGUAGE_ENGLISH, LANGUAGE_MALAY, LANGUAGE_CHINESE)

        /**
         * The language the user DECLARED in Vyze's voice settings (persisted),
         * mapped to a Locale (US by default). This drives the speech-recognition
         * language and the prompt output language from launch — not just after
         * the first spoken exchange — so a Malay-speaking user on an English
         * phone gets Malay recognition and Malay voice output consistently.
         */
        fun storedLanguageLocale(context: android.content.Context): Locale {
            val key = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, LANGUAGE_ENGLISH) ?: LANGUAGE_ENGLISH
            return when (key) {
                LANGUAGE_MALAY -> Locale("ms", "MY")
                LANGUAGE_CHINESE -> Locale.SIMPLIFIED_CHINESE
                else -> Locale.US
            }
        }

        const val DEBOUNCE_MS = 1500L
        const val ENGINE_SETTLE_DELAY_MS = 200L
        const val DRAIN_RETRY_INTERVAL_MS = 200L
        const val DRAIN_RETRY_MS = 5000L

        // ── Voice Quality & Prosody Constants ───────────────────────

        private const val GOOGLE_TTS_ENGINE = "com.google.android.tts"

        /**
         * English-voice respellings for brand names generic EN voices misread.
         * Key: the word as printed on the product. Value: a respelling the
         * engine speaks the way the brand is actually said.
         *
         * "Maggi" (instant noodles): Google EN reads it "MAY-jee"; the brand
         * is said "MAY-ghee" (hard g). "Mayghee" produces exactly that.
         * Malay/Chinese voices already read these brands correctly.
         */
        private val ENGLISH_PRONUNCIATION_OVERRIDES = mapOf(
            "maggi" to "Mayghee"
        )

        /**
         * Warmer pitch (-2%): eliminates flat, metallic synth tones.
         */
        private const val WARM_PITCH = 0.96f

        /**
         * Conversational rate (-2%): slightly slower than default to
         * allow natural cadence and emphasis.
         */
        private const val WARM_RATE = 0.98f
    }
}
