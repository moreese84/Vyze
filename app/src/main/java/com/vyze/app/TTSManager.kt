package com.vyze.app

import android.content.Context
import android.media.AudioTrack
import android.media.AudioFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Singleton Text-to-Speech manager for the Vyze accessibility app.
 *
 * ## Single Instance
 * This class is a strict singleton — only one instance exists per process.
 * Previously, VyzeApplication + TtsViewModel each created their own TTSManager,
 * causing double model extraction and conflicting native JNI state.
 *
 * ## Offline Engine
 * All speech is produced by Sherpa-ONNX VITS (Meta MMS multilingual model).
 * Fully offline, no Google TTS dependency. The Android TextToSpeech types
 * appear only in signatures kept for call-site compatibility.
 *
 * ## Audio Focus
 * Requests AUDIOFOCUS_GAIN (permanent) for the entire app session.
 * Focus is held from app open to app close. Never released per-utterance
 * or on stop() — only on onDestroy().
 */
class TTSManager private constructor(context: Context) {

    // ── Sherpa-ONNX TTS (offline, no Google dependency) ────────
    // Shared singleton: two TTSManager instances exist (VyzeApplication +
    // TtsViewModel) and each would otherwise load its own copy of the models.
    private val sherpaTts: SherpaTtsManager by lazy { SherpaTtsManager.getInstance(appContext) }

    @Volatile
    private var isInitialized = false

    /** True once the missing-model warning has been logged/crash-reported in this session. */
    @Volatile
    private var missingModelWarningShown = false

    private var cachedVolume: Float = DEFAULT_VOLUME
    private var cachedRate: Float = DEFAULT_SPEECH_RATE
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

    /**
     * Caller-supplied progress listener (engine-global, like the old additive
     * wrapper around Google TTS): fired for EVERY utterance start/done, and
     * for error on utterances dropped by stop()/QUEUE_FLUSH.
     */
    @Volatile
    private var callerListener: UtteranceProgressListener? = null

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
    // NOTE: This is retained for AudioFocus requests even though this app uses
    // Sherpa TTS, not the Android TTS engine.

    private val focusAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // ── Initialization ────────────────────────────────────────────

    init {
        Log.i(TAG, "[TTSManager] Single-File VITS Engine Active (Sherpa-ONNX MMS multilingual)")
        // Model loading is lazy — triggered by first speak() call to SherpaTtsManager.
        // No eager initialization to avoid native exit(255) during startup.
    }

    /** Runs on the main thread once Sherpa model loading has finished. */
    private fun onSherpaReady() {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedLang = prefs.getString(KEY_LANGUAGE, LANGUAGE_ENGLISH) ?: LANGUAGE_ENGLISH
        currentLocale = localeFromKey(savedLang)

        if (!sherpaTts.isReady()) {
            // initialize() completed but neither model could be loaded.
            if (!missingModelWarningShown) {
                missingModelWarningShown = true
                Log.w(TAG, "TTS startup warning: offline voice models not loadable")
                CrashLogFile.log(TAG, "TTS startup: offline voice models not loadable")
            }
            return
        }

        Log.i(TAG, "TTS setup OK — locale=$currentLocale, sherpa ready, " +
            "pitch=$WARM_PITCH, rate=$WARM_RATE, volume=$cachedVolume")

        isInitialized = true
        Log.i(TAG, "TTS fully initialized — initial drain: ${speechBuffer.size} buffered")
        drainPendingQueue()
        onReady?.invoke()
        startDrainRetryTimer()
    }

    /**
     * Legacy Android TTS init callback. Google TTS is no longer used; kept
     * only so any stale callers compile. Initialization is event-driven via
     * Sherpa's ready callback instead.
     */
    fun onInit(status: Int) {
        Log.i(TAG, "onInit: no-op — Google TTS disabled, using Sherpa offline TTS")
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

            val utteranceId = nextUtteranceId()

            if (sherpaTts.isReady()) {
                val enhanced = enhanceForNaturalProsody(applyPronunciationOverrides(text))
                pendingUtteranceIds.add(utteranceId)
                sherpaTts.speak(
                    enhanced, currentLocale, cachedRate(), cachedVolume,
                    onStart = { onUtteranceStart(utteranceId) },
                    onDone = { onUtteranceDone(utteranceId) }
                )
                Log.d(TAG, "Drained #$drained (sherpa, id=$utteranceId): ${text.take(60)}...")
            } else {
                Log.d(TAG, "drainPendingQueue: Sherpa not ready — re-queuing: \"${text.take(60)}\"")
                speechBuffer.add(text)
                break
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
     * @param queueMode  QUEUE_FLUSH barges in (cancels current + queued speech
     *                   and reports them as onError); QUEUE_ADD appends.
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
        val now = System.currentTimeMillis()
        if (text == lastSpokenText && (now - lastSpeechTime) < DEBOUNCE_MS) {
            Log.d(TAG, "speak() DEBOUNCE: " + text.take(60))
            return false
        }
        lastSpeechTime = now
        lastSpokenText = text
        val enhancedText = enhanceForNaturalProsody(applyPronunciationOverrides(text))
        if (sherpaTts.isReady()) {
            if (queueMode == TextToSpeech.QUEUE_FLUSH) {
                notifyFlushed()
                sherpaTts.stop()
            }
            val id = utteranceId ?: nextUtteranceId()
            pendingUtteranceIds.add(id)
            Log.d(TAG, "speak() on IO: " + enhancedText.take(60))
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                synthesizeAndPlay(enhancedText, id)
            }
            return true
        }
        Log.d(TAG, "speak() not ready: " + text.take(60))
        speechBuffer.add(enhancedText)
        return false
    }

    private fun synthesizeAndPlay(text: String, utteranceId: String) {
        try {
            Log.d(TAG, "generate(): " + text.take(60))
            val audio = sherpaTts.generateSamples(text, speed = cachedRate())
            if (audio == null) {
                Log.e(TAG, "generate() returned null")
                kotlinx.coroutines.runBlocking(Dispatchers.Main) { onUtteranceDone(utteranceId) }
                return
            }
            Log.d(TAG, "Samples: " + audio.samples.size + ", Rate: " + audio.sampleRate)
            if (audio.samples.isEmpty()) {
                Log.e(TAG, "generate() returned empty samples")
                kotlinx.coroutines.runBlocking(Dispatchers.Main) { onUtteranceDone(utteranceId) }
                return
            }
            val peak = audio.samples.maxOrNull() ?: 0f
            Log.d(TAG, "peak=" + peak + ", dur=" + (audio.samples.size / audio.sampleRate) + "s")
            playFloatPcm(audio.samples, audio.sampleRate, utteranceId)
        } catch (e: Throwable) {
            Log.e(TAG, "synth error: " + e.javaClass.simpleName + ": " + (e.message ?: ""))
            try { kotlinx.coroutines.runBlocking(Dispatchers.Main) { onUtteranceDone(utteranceId) } } catch (_: Throwable) {}
        }
    }

    private fun playFloatPcm(samples: FloatArray, sampleRate: Int, utteranceId: String) {
        if (samples.isEmpty() || sampleRate <= 0) {
            kotlinx.coroutines.runBlocking(Dispatchers.Main) { onUtteranceDone(utteranceId) }
            return
        }
        var track: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            if (minBuf <= 0) {
                Log.e(TAG, "getMinBuf=" + minBuf)
                kotlinx.coroutines.runBlocking(Dispatchers.Main) { onUtteranceDone(utteranceId) }
                return
            }
            val bufSize = maxOf(minBuf, samples.size * 4)
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack state=" + track.state)
                track.release()
                track = null
                kotlinx.coroutines.runBlocking(Dispatchers.Main) { onUtteranceDone(utteranceId) }
                return
            }
            Log.d(TAG, "AudioTrack OK: " + samples.size + " @ " + sampleRate + "Hz")
            track.play()
            Log.d(TAG, "play() called")
            val written = track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            Log.d(TAG, "write()=" + written + "/" + samples.size)
            track.stop()
            track.release()
            track = null
            kotlinx.coroutines.runBlocking(Dispatchers.Main) { onUtteranceDone(utteranceId) }
        } catch (e: Throwable) {
            Log.e(TAG, "play error: " + e.javaClass.simpleName + ": " + (e.message ?: ""))
            try { track?.stop() } catch (_: Throwable) {}
            try { track?.release() } catch (_: Throwable) {}
            try { kotlinx.coroutines.runBlocking(Dispatchers.Main) { onUtteranceDone(utteranceId) } } catch (_: Throwable) {}
        }
    }

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

        // QUEUE_FLUSH semantics: barge-in — report the flushed utterances as
        // errors (the Google TTS contract) so waiting listeners can resume.
        notifyFlushed()
        val id = nextUtteranceId()

        // This app does not use Google TTS. If Sherpa is not ready, enqueue for retry.
        if (sherpaTts.isReady()) {
            pendingUtteranceIds.add(id)
            sherpaTts.speak(
                enhancedText, currentLocale, cachedRate(), cachedVolume,
                onStart = { onUtteranceStart(id) },
                onDone = { onUtteranceDone(id) },
                flush = true
            )
            Log.d(TAG, "speakImmediate() OK (sherpa) id=$id pending=${pendingUtteranceIds.size} " +
                "text=\"${text.take(60)}\"")
            return true
        }

        Log.d(TAG, "speakImmediate() Sherpa not ready — buffering: \"${text.take(60)}\"")
        speechBuffer.add(enhancedText)
        return false
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

    fun isSpeaking(): Boolean = sherpaTts.isSpeaking()

    fun isReady(): Boolean = sherpaTts.isReady()

    /**
     * Stop all speech and clear all pending utterance tracking.
     * This is the ONLY way to guarantee hasPendingSpeech() returns false
     * immediately after stop().
     */
    fun stop() {
        notifyFlushed()
        sherpaTts.stop()
        Log.d(TAG, "stop() — pendingUtteranceIds cleared, flushed utterances reported as onError")
    }

    // ── Utterance Event Forwarding ────────────────────────────────

    // The listener is CAPTURED when the event happens, not when the posted
    // runnable executes: speakThenCallback() assigns a fresh listener right
    // after stop()+speak(), and a captured-at-execution listener would deliver
    // the OLD utterance's onError to the NEW listener — firing its onDone
    // before the new utterance even starts.

    /** Utterance started processing — forward to the caller listener. */
    private fun onUtteranceStart(id: String) {
        val listener = callerListener
        mainHandler.post { listener?.onStart(id) }
    }

    /** Utterance finished playing (or failed) — untrack and forward. */
    private fun onUtteranceDone(id: String) {
        pendingUtteranceIds.remove(id)
        val listener = callerListener
        mainHandler.post { listener?.onDone(id) }
    }

    /**
     * Report currently-pending utterances as errored and untrack them.
     * Mirrors Google TTS, which fires onError for utterances dropped by
     * stop()/QUEUE_FLUSH — callers rely on that to leave their wait state.
     */
    private fun notifyFlushed() {
        val ids = pendingUtteranceIds.toList()
        if (ids.isEmpty()) return
        pendingUtteranceIds.removeAll(ids)
        val listener = callerListener
        ids.forEach { id -> mainHandler.post { listener?.onError(id) } }
    }

    /**
     * Set a caller-provided UtteranceProgressListener.
     *
     * Engine-global (as the old additive wrapper effectively was): the
     * listener fires for EVERY utterance — onStart when generation begins,
     * onDone when its audio finishes playing, onError when it is dropped by
     * stop()/QUEUE_FLUSH. Internal pendingUtteranceIds tracking is separate
     * and cannot be overwritten by callers.
     */
    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener) {
        callerListener = listener
    }

    /**
     * Legacy Google-TTS silent tail. Obsolete with Sherpa: onDone now fires
     * only after the hardware AudioTrack has drained the utterance, so
     * hasPendingSpeech() already stays true until the speaker is silent.
     * Kept as a harmless no-op for call-site compatibility.
     */
    fun playSilentUtterance(durationMs: Int = 300, queueMode: Int = TextToSpeech.QUEUE_ADD): Boolean {
        if (!isInitialized) return false
        Log.d(TAG, "playSilentUtterance: no-op (Sherpa TTS does not produce silent utterances)")
        return false
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

    // Not used: this app does not use the Android Google TTS engine.
    // Volume is applied inside SherpaTtsManager via AudioTrack scaling.
    @Suppress("UNUSED_PARAMETER")
    private fun buildSpeakParams(): Bundle = Bundle()

    // ── Voice Quality Selection ──────────────────────────────────

    // Google TTS is not used by this app, so Android voice selection is disabled.
    // Sherpa model selection is driven by locale inside SherpaTtsManager.

    private fun selectBestVoice(locale: Locale) {
        // No-op: Google TTS voices are not used.
    }

    // ── Voice Picker Support (Tier 1) ──────────────────────────────

    /** Installed voices for the current language (uninstalled packs excluded).
     * Returns an empty list because this app uses Sherpa TTS, not the Android TTS engine.
     */
    fun getInstalledVoicesForCurrentLanguage(): List<Voice> = emptyList()

    /** Name of the voice actually in use, or null if unknown.
     * Always null because this app uses Sherpa TTS, not Android TTS voices.
     */
    fun getCurrentVoiceName(): String? = null

    /**
     * True when an offline Sherpa model can speak the given language
     * code ("en" / "ms" / "zh"). Drives the voice-settings language gate:
     * all three supported languages are covered once either model loads.
     */
    fun hasInstalledVoicesFor(language: String): Boolean = sherpaTts.hasVoiceFor(language)

    /**
     * Select a voice by name ("" or [VOICE_AUTO] → auto-pick the best
     * installed voice). No-op because this app uses Sherpa TTS.
     */
    fun setVoiceByName(name: String) {
        // No-op: Google TTS voices are not used.
    }

    /**
     * True when the best installed voice for the current language is the
     * robotic base quality (or when no voice pack is installed at all and
     * the engine falls back to its default). Used to offer the better
     * voice install prompt.
     * Always false because this app uses Sherpa TTS, not Android TTS.
     */
    fun isVoiceQualityLow(): Boolean = false

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

        // Google TTS is not used by this app, so there is no Android engine to switch.
        // Sherpa model selection is locale-driven inside SherpaTtsManager.
        currentLocale = locale

        Log.i(TAG, "switchToLocale: locale updated to $currentLocale (Sherpa model selection is locale-based)")
    }

    fun setLanguage(languageKey: String, context: Context) {
        val locale = localeFromKey(languageKey)
        currentLocale = locale

        // Google TTS is not used by this app, so there is no Android engine to switch.
        // Sherpa model selection is locale-driven inside SherpaTtsManager.

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

    // Google TTS is not used by this app.
    // SetSpeechRate / setPitch are no-ops here — prosody is controlled by the
    // speed parameter passed to SherpaTtsManager.speak().
    fun setSpeechRate(rate: Float) {
        cachedRate = rate.coerceIn(0.5f, 2.0f)
    }

    fun setPitch(pitch: Float) {
        // Sherpa TTS does not expose pitch control. No-op to keep settings consistent.
    }

    fun setVolume(volume: Float) {
        cachedVolume = volume.coerceIn(0f, 1f)
    }

    fun getVolume(): Float = cachedVolume

    internal fun cachedRate(): Float = cachedRate

    fun applySettings(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        setSpeechRate(prefs.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE))
        setPitch(prefs.getFloat(KEY_PITCH, DEFAULT_PITCH))
        setVolume(prefs.getFloat(KEY_VOLUME, DEFAULT_VOLUME))
        cachedRate = prefs.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE)

        val savedLang = prefs.getString(KEY_LANGUAGE, LANGUAGE_ENGLISH) ?: LANGUAGE_ENGLISH
        setLanguage(savedLang, context)

        // Google TTS is not used by this app, so there is no voice name to restore.
        // Sherpa model selection is driven by locale inside SherpaTtsManager.

        // Model loading is now lazy (triggered by first speak() call).
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    fun onDestroy() {
        stopDrainRetryTimer()
        mainHandler.removeCallbacksAndMessages(null)
        // stop() only — the Sherpa engine is a shared app-scoped singleton
        // (VyzeApplication + TtsViewModel both hold a TTSManager), so one
        // instance's teardown must not unload the models for the other.
        // Models stay resident for the process lifetime; the OS reclaims
        // them when the whole app is killed.
        notifyFlushed()
        sherpaTts.stop()
        pendingUtteranceIds.clear()
        abandonAudioFocus()
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
        private const val TAG = "[TTSManager]"

        @Volatile
        private var instance: TTSManager? = null

        fun getInstance(context: Context): TTSManager {
            return instance ?: synchronized(this) {
                instance ?: TTSManager(context.applicationContext).also { instance = it }
            }
        }

        const val PREFS_NAME = "vyze_tts_settings"
        const val KEY_SPEECH_RATE = "speech_rate"
        const val KEY_PITCH = "pitch"
        const val KEY_VOLUME = "volume"
        const val KEY_LANGUAGE = "tts_language"
        const val KEY_VOICE_NAME = "tts_voice_name"
        const val VOICE_AUTO = "automatic"
        const val KEY_VOICE_PROMPT_RESOLVED = "voice_prompt_resolved"
        const val KEY_VOICE_SETTINGS_KNOWN = "voice_settings_known"
        const val DEFAULT_SPEECH_RATE = 1.0f
        const val DEFAULT_PITCH = 1.0f
        const val DEFAULT_VOLUME = 1.0f
        const val LANGUAGE_ENGLISH = "en"
        const val LANGUAGE_MALAY = "ms"
        const val LANGUAGE_CHINESE = "zh"
        val SUPPORTED_LANGUAGES = listOf(LANGUAGE_ENGLISH, LANGUAGE_MALAY, LANGUAGE_CHINESE)

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

        private val ENGLISH_PRONUNCIATION_OVERRIDES = mapOf(
            "maggi" to "Mayghee"
        )
        private const val WARM_PITCH = 0.96f
        private const val WARM_RATE = 0.98f
    }
}
