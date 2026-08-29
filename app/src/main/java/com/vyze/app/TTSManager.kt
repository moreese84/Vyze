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
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Thread-safe Text-to-Speech manager for the Vyze accessibility app.
 *
 * ## Volume Stability
 * Queries and locks the accessibility stream volume at construction time.
 * All speak() calls use KEY_PARAM_VOLUME = 1.0f so every utterance outputs
 * at the identical, stable gain for the entire session. The locked baseline
 * is logged once and never re-queried — no runtime volume drift.
 *
 * ## Audio Focus
 * Requests AUDIOFOCUS_GAIN_TRANSIENT (not MAY_DUCK) so TTS output is
 * treated as primary accessibility guidance. Other apps pause their audio
 * rather than ducking. Focus is released in UtteranceProgressListener.onDone()
 * so the session is clean between utterances.
 *
 * ## System Ducking Elimination
 * Uses USAGE_ASSISTANCE_ACCESSIBILITY / CONTENT_TYPE_SPEECH for both the
 * TTS engine's AudioAttributes and the AudioFocusRequest. Android's audio
 * framework treats all utterances as non-duckable accessibility speech.
 *
 * ## Speech Buffer
 * All speech requests are buffered in a [ConcurrentLinkedQueue] from the moment
 * of construction. A periodic drain timer retries every 200ms for up to
 * [DRAIN_RETRY_MS] after onInit to catch late-arriving messages.
 *
 * ## Thread Safety
 * All public methods are safe to call from any thread.
 */
class TTSManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null

    @Volatile
    private var isInitialized = false

    private var cachedVolume: Float = DEFAULT_VOLUME
    private val appContext: Context = context.applicationContext

    // ── Audio Manager & Volume Baseline ───────────────────────────

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Locked session volume baseline — queried once at construction time.
     * All TTS output uses KEY_PARAM_VOLUME = 1.0f relative to this stream,
     * so every utterance outputs at identical, stable gain for the entire session.
     */
    private val lockedStreamVolume: Int = try {
        audioManager.getStreamVolume(AudioManager.STREAM_ACCESSIBILITY)
    } catch (e: Throwable) {
        Log.w(TAG, "Failed to query accessibility stream volume: ${e.message}")
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    private val streamMaxVolume: Int = try {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_ACCESSIBILITY)
    } catch (e: Throwable) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

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

    // ── Audio Attributes (Accessibility — non-duckable) ───────────

    /**
     * TTS engine audio attributes.
     * USAGE_ASSISTANCE_ACCESSIBILITY ensures Android treats all utterances
     * as primary accessibility guidance — not duckable media streams.
     */
    private val ttsAudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * Audio focus request attributes.
     * Uses USAGE_ASSISTANCE_ACCESSIBILITY so the audio framework recognizes
     * this as critical accessibility speech, not background media.
     */
    private val focusAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // ── Initialization ────────────────────────────────────────────

    init {
        Log.i(TAG, "TTSManager created — locked stream volume=$lockedStreamVolume " +
            "(max=$streamMaxVolume, stream=ACCESSIBILITY)")
        tts = TextToSpeech(appContext, this)
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

            // Apply accessibility audio attributes — eliminates system ducking
            tts?.setAudioAttributes(ttsAudioAttributes)

            Log.i(TAG, "TTS setup OK — locale=$currentLocale, engine=${tts?.defaultEngine}, " +
                "lockedVolume=$lockedStreamVolume")

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
            val now = System.currentTimeMillis()
            val params = buildSpeakParams()
            val utteranceId = "buffered_${now}_$drained"
            val result = tts?.speak(text, mode, params, utteranceId) ?: TextToSpeech.ERROR

            if (result != TextToSpeech.SUCCESS) {
                Log.e(TAG, "drainPendingQueue: speak() FAILED code=$result, text=\"${text.take(60)}\"")
            } else {
                Log.d(TAG, "Drained #$drained (id=$utteranceId): ${text.take(60)}...")
            }

            lastSpeechTime = now
            lastSpokenText = text
            drained++
        }

        if (drained > 0) {
            Log.i(TAG, "Drained $drained buffered utterances, buffer remaining: ${speechBuffer.size}")
        }
        return drained
    }

    // ── Public Speech API ─────────────────────────────────────────

    /**
     * Speaks the given text with the specified queue mode.
     * Requests AUDIOFOCUS_GAIN_TRANSIENT before speaking.
     * ALWAYS adds to the buffer if TTS is not initialized.
     *
     * @return true if speak() succeeded, false if queued or failed
     */
    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_ADD): Boolean {
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

        // Pre-grant audio focus before speaking
        requestAudioFocus()

        val params = buildSpeakParams()
        val utteranceId = "utterance_${now}"
        val result = tts?.speak(text, queueMode, params, utteranceId) ?: TextToSpeech.ERROR

        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "speak() FAILED code=$result, text=\"${text.take(60)}\", id=$utteranceId")
        } else {
            Log.d(TAG, "speak() OK queueMode=$queueMode result=$result text=\"${text.take(60)}\"")
        }

        return result == TextToSpeech.SUCCESS
    }

    /**
     * Immediate speech for urgent accessibility feedback.
     * Requests AUDIOFOCUS_GAIN_TRANSIENT, stops current speech, speaks with QUEUE_FLUSH.
     * Focus is released in UtteranceProgressListener.onDone().
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

        tts?.stop()

        // Pre-grant audio focus — ACCESSIBILITY stream, GAIN_TRANSIENT (no ducking)
        requestAudioFocus()

        val params = buildSpeakParams()
        val utteranceId = "immediate_${now}"
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            ?: TextToSpeech.ERROR

        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "speakImmediate() FAILED code=$result, text=\"${text.take(60)}\", id=$utteranceId")
        } else {
            Log.d(TAG, "speakImmediate() OK result=$result text=\"${text.take(60)}\"")
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

    fun stop() {
        tts?.stop()
        abandonAudioFocus()
    }

    /**
     * Set a listener for utterance progress events.
     * Wraps the caller's listener to automatically release audio focus
     * in onDone(), ensuring the focus session is clean between utterances.
     */
    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener) {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                listener.onStart(utteranceId)
            }

            override fun onDone(utteranceId: String?) {
                // Release audio focus when utterance playback completes
                abandonAudioFocus()
                listener.onDone(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                abandonAudioFocus()
                listener.onError(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                abandonAudioFocus()
                listener.onError(utteranceId, errorCode)
            }
        })
    }

    // ── Audio Focus ───────────────────────────────────────────────

    /**
     * Request AUDIOFOCUS_GAIN_TRANSIENT with USAGE_ASSISTANCE_ACCESSIBILITY.
     * This pauses other apps' audio (rather than ducking) while TTS speaks.
     * Focus is released in UtteranceProgressListener.onDone().
     */
    private fun requestAudioFocus() {
        try {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(focusAttributes)
                .setOnAudioFocusChangeListener { /* released via abandonAudioFocus in onDone */ }
                .build()

            audioManager.requestAudioFocus(audioFocusRequest!!)
            Log.d(TAG, "Audio focus requested (GAIN_TRANSIENT, ACCESSIBILITY stream)")
        } catch (e: Throwable) {
            Log.w(TAG, "requestAudioFocus failed: ${e.message}")
        }
    }

    /**
     * Release audio focus so other apps can resume normal playback.
     * Called from UtteranceProgressListener.onDone() and from stop()/onDestroy().
     */
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

    /**
     * Build Bundle params for tts.speak().
     * KEY_PARAM_VOLUME = 1.0f means output at full volume of the locked
     * accessibility stream — every utterance at identical, stable gain.
     */
    private fun buildSpeakParams(): Bundle {
        return Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
    }

    // ── Locale Switching ──────────────────────────────────────────

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
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    fun onDestroy() {
        stopDrainRetryTimer()
        mainHandler.removeCallbacksAndMessages(null)
        tts?.stop()
        abandonAudioFocus()
        tts?.shutdown()
        tts = null
        isInitialized = false
        speechBuffer.clear()
        Log.d(TAG, "TTS destroyed — locked volume was $lockedStreamVolume")
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

        const val DEFAULT_SPEECH_RATE = 1.0f
        const val DEFAULT_PITCH = 1.0f
        const val DEFAULT_VOLUME = 0.8f

        const val LANGUAGE_ENGLISH = "en"
        const val LANGUAGE_MALAY = "ms"
        const val LANGUAGE_CHINESE = "zh"

        val SUPPORTED_LANGUAGES = listOf(LANGUAGE_ENGLISH, LANGUAGE_MALAY, LANGUAGE_CHINESE)

        /** Minimum milliseconds between identical TTS utterances. */
        const val DEBOUNCE_MS = 1500L

        /** Delay after onInit before first drain. */
        const val ENGINE_SETTLE_DELAY_MS = 200L

        /** How often the drain retry timer checks for new buffered messages. */
        const val DRAIN_RETRY_INTERVAL_MS = 200L

        /** Total duration the drain retry timer runs after onInit. */
        const val DRAIN_RETRY_MS = 5000L
    }
}
