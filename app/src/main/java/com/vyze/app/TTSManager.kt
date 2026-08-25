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

    /**
     * The currently active TTS locale. Updated via [setLocale] and
     * persisted to SharedPreferences under [KEY_LANGUAGE].
     */
    @Volatile
    private var currentLocale: Locale = Locale.US

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Restore saved language preference
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
            Log.d(TAG, "TTS initialized with locale: $currentLocale")
        } else {
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
    }

    // ── Speech ──────────────────────────────────────────────────────────

    /**
     * Speaks the given text with the specified queue mode.
     * Uses QUEUE_FLUSH by default (interrupts current speech).
     */
    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized yet, queuing: $text")
            return
        }
        tts?.speak(text, queueMode, null, "utterance_${System.currentTimeMillis()}")
    }

    /**
     * Immediate speech for urgent accessibility feedback.
     * Flushes any pending speech and speaks immediately.
     */
    fun speakImmediate(text: String) {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized for immediate speech: $text")
            return
        }
        tts?.stop()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "immediate_${System.currentTimeMillis()}")
    }

    /**
     * Stops any current speech output.
     */
    fun stop() {
        tts?.stop()
    }

    // ── Locale Switching ────────────────────────────────────────────────

    /**
     * Switches the TTS engine to the specified language.
     *
     * @param languageKey One of [LANGUAGE_ENGLISH], [LANGUAGE_MALAY], [LANGUAGE_CHINESE].
     * @param context     Used to persist the preference.
     */
    fun setLanguage(languageKey: String, context: Context) {
        val locale = localeFromKey(languageKey)
        currentLocale = locale

        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "Language $locale not available on device, falling back to English")
            tts?.setLanguage(Locale.US)
            currentLocale = Locale.US
        }

        // Persist the choice
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, languageKey)
            .apply()

        Log.d(TAG, "TTS language switched to: $currentLocale")
    }

    /**
     * Returns the current language key.
     */
    fun getCurrentLanguageKey(): String {
        return keyFromLocale(currentLocale)
    }

    /**
     * Returns the current locale display name for UI binding.
     */
    fun getCurrentLanguageDisplayName(context: Context): String {
        return when (keyFromLocale(currentLocale)) {
            LANGUAGE_MALAY  -> context.getString(R.string.tts_lang_malay)
            LANGUAGE_CHINESE -> context.getString(R.string.tts_lang_chinese)
            else           -> context.getString(R.string.tts_lang_english)
        }
    }

    // ── Settings ────────────────────────────────────────────────────────

    /**
     * Sets the speech rate. 1.0 is normal speed.
     */
    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    /**
     * Sets the pitch. 1.0 is normal pitch.
     */
    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch.coerceIn(0.5f, 1.5f))
    }

    /**
     * Sets the audio stream volume.
     */
    fun setVolume(volume: Float) {
        cachedVolume = volume.coerceIn(0f, 1f)
    }

    /**
     * Returns the last-set volume level (0.0–1.0).
     */
    fun getVolume(): Float = cachedVolume

    /**
     * Applies saved TTS settings from SharedPreferences.
     */
    fun applySettings(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        setSpeechRate(prefs.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE))
        setPitch(prefs.getFloat(KEY_PITCH, DEFAULT_PITCH))
        setVolume(prefs.getFloat(KEY_VOLUME, DEFAULT_VOLUME))

        // Restore language
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

    /**
     * Converts a language key string to a [Locale].
     */
    private fun localeFromKey(key: String): Locale {
        return when (key) {
            LANGUAGE_MALAY  -> Locale("ms", "MY")
            LANGUAGE_CHINESE -> Locale.SIMPLIFIED_CHINESE
            else           -> Locale.US
        }
    }

    /**
     * Converts a [Locale] to a language key string.
     */
    private fun keyFromLocale(locale: Locale): String {
        return when (locale.language) {
            "ms"  -> LANGUAGE_MALAY
            "zh"  -> LANGUAGE_CHINESE
            else  -> LANGUAGE_ENGLISH
        }
    }

    companion object {
        private const val TAG = "TTSManager"

        // ── SharedPreferences Keys ─────────────────────────────────────
        const val PREFS_NAME = "vyze_tts_settings"
        const val KEY_SPEECH_RATE = "speech_rate"
        const val KEY_PITCH = "pitch"
        const val KEY_VOLUME = "volume"
        const val KEY_LANGUAGE = "tts_language"

        // ── Defaults ───────────────────────────────────────────────────
        const val DEFAULT_SPEECH_RATE = 1.0f
        const val DEFAULT_PITCH = 1.0f
        const val DEFAULT_VOLUME = 0.8f

        // ── Language Keys ──────────────────────────────────────────────
        const val LANGUAGE_ENGLISH = "en"
        const val LANGUAGE_MALAY = "ms"
        const val LANGUAGE_CHINESE = "zh"

        /** All supported language keys for UI binding. */
        val SUPPORTED_LANGUAGES = listOf(LANGUAGE_ENGLISH, LANGUAGE_MALAY, LANGUAGE_CHINESE)
    }
}
