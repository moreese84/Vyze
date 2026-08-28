package com.vyze.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TTSManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var cachedVolume: Float = TTSManager.DEFAULT_VOLUME
    private var appContext: Context = context.applicationContext

    @Volatile
    private var currentLocale: Locale = Locale.US

    // ── Debounce state ────────────────────────────────────────────────

    @Volatile
    private var lastSpeechTime = 0L

    @Volatile
    private var lastSpokenText = ""

    init {
        tts = TextToSpeech(context.applicationContext, this)
        Log.d(TAG, "TTS constructor called, waiting for onInit callback")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedLang = prefs.getString(KEY_LANGUAGE, LANGUAGE_ENGLISH) ?: LANGUAGE_ENGLISH
            currentLocale = localeFromKey(savedLang)

            val result = tts?.setLanguage(currentLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS language $currentLocale not supported, falling back to default")
                tts?.setLanguage(Locale.US)
                currentLocale = Locale.US
            }
            isInitialized = true
            Log.i(TAG, "TTS INITIALIZED OK — locale=$currentLocale, engine=${tts?.defaultEngine}")

            // Flush any pending queued utterances that arrived before init
            synchronized(pendingUtterances) {
                for (text in pendingUtterances) {
                    Log.d(TAG, "Flushing pending utterance: $text")
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pending_${System.currentTimeMillis()}")
                }
                pendingUtterances.clear()
            }
        } else {
            Log.e(TAG, "TTS INIT FAILED — status=$status (SUCCESS=${TextToSpeech.SUCCESS})")
        }
    }

    /** Texts queued before TTS finished initializing. */
    private val pendingUtterances = mutableListOf<String>()

    // ── Speech ──────────────────────────────────────────────────────────

    /**
     * Speaks the given text with the specified queue mode.
     */
    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_ADD) {
        if (!isInitialized) {
            Log.w(TAG, "speak() called before TTS init — queuing: \"$text\"")
            synchronized(pendingUtterances) {
                pendingUtterances.add(text)
            }
            return
        }

        val now = System.currentTimeMillis()
        if (text == lastSpokenText && (now - lastSpeechTime) < DEBOUNCE_MS) {
            Log.d(TAG, "speak() DEBOUNCE — dropping duplicate: \"$text\"")
            return
        }

        lastSpeechTime = now
        lastSpokenText = text

        val result = tts?.speak(text, queueMode, null, "utterance_${now}")
        Log.d(TAG, "speak() OK queueMode=$queueMode result=$result text=\"$text\"")
    }

    /**
     * Immediate speech for urgent accessibility feedback.
     * Stops any current speech, then speaks the new text using QUEUE_FLUSH.
     */
    fun speakImmediate(text: String) {
        if (text.isBlank()) {
            Log.w(TAG, "speakImmediate() called with blank text — skipping")
            return
        }

        if (!isInitialized) {
            Log.w(TAG, "speakImmediate() called before TTS init — queuing: \"$text\"")
            synchronized(pendingUtterances) {
                pendingUtterances.clear() // drop old, keep only latest
                pendingUtterances.add(text)
            }
            return
        }

        val now = System.currentTimeMillis()
        if (text == lastSpokenText && (now - lastSpeechTime) < DEBOUNCE_MS) {
            Log.d(TAG, "speakImmediate() DEBOUNCE — dropping duplicate: \"$text\"")
            return
        }

        lastSpeechTime = now
        lastSpokenText = text

        tts?.stop()
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "immediate_${now}")
        Log.d(TAG, "speakImmediate() OK result=$result text=\"$text\"")
    }

    fun isSpeaking(): Boolean {
        return tts?.isSpeaking == true
    }

    fun stop() {
        tts?.stop()
    }

    // ── Locale Switching ────────────────────────────────────────────────

    fun setLanguage(languageKey: String, context: Context) {
        val locale = localeFromKey(languageKey)
        currentLocale = locale

        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "Language $locale not available on device, falling back to English")
            tts?.setLanguage(Locale.US)
            currentLocale = Locale.US
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, languageKey)
            .apply()

        Log.d(TAG, "TTS language switched to: $currentLocale")
    }

    fun getCurrentLanguageKey(): String {
        return keyFromLocale(currentLocale)
    }

    fun getCurrentLanguageDisplayName(context: Context): String {
        return when (keyFromLocale(currentLocale)) {
            LANGUAGE_MALAY  -> context.getString(R.string.tts_lang_malay)
            LANGUAGE_CHINESE -> context.getString(R.string.tts_lang_chinese)
            else           -> context.getString(R.string.tts_lang_english)
        }
    }

    // ── Settings ────────────────────────────────────────────────────────

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

    // ── Lifecycle ───────────────────────────────────────────────────────

    fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        Log.d(TAG, "TTS destroyed")
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun localeFromKey(key: String): Locale {
        return when (key) {
            LANGUAGE_MALAY  -> Locale("ms", "MY")
            LANGUAGE_CHINESE -> Locale.SIMPLIFIED_CHINESE
            else           -> Locale.US
        }
    }

    private fun keyFromLocale(locale: Locale): String {
        return when (locale.language) {
            "ms"  -> LANGUAGE_MALAY
            "zh"  -> LANGUAGE_CHINESE
            else  -> LANGUAGE_ENGLISH
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
    }
}
