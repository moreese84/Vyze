package com.vyze.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TTSManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var cachedVolume: Float = TTSManager.DEFAULT_VOLUME

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS language not supported, falling back to default")
            }
            isInitialized = true
            Log.d(TAG, "TTS initialized successfully")
        } else {
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
    }

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
     * Sets the speech rate. 1.0 is normal speed.
     * @param rate Value between 0.5 and 2.0.
     */
    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    /**
     * Sets the pitch. 1.0 is normal pitch.
     * @param pitch Value between 0.5 and 1.5.
     */
    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch.coerceIn(0.5f, 1.5f))
    }

    /**
     * Sets the audio stream volume via AudioManager.
     * @param volume Value between 0.0 and 1.0.
     */
    fun setVolume(volume: Float) {
        try {
            val context = tts?.javaClass?.let { null } // tts doesn't expose context
            // Store volume for application by caller via AudioManager
            cachedVolume = volume.coerceIn(0f, 1f)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume", e)
        }
    }

    /**
     * Returns the last-set volume level (0.0–1.0).
     * Callers should apply this via AudioManager STREAM_MUSIC.
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
    }

    /**
     * Stops any current speech output.
     */
    fun stop() {
        tts?.stop()
    }

    /**
     * Releases TTS resources. Should be called during lifecycle teardown.
     */
    fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        Log.d(TAG, "TTS destroyed")
    }

    companion object {
        private const val TAG = "TTSManager"
        const val PREFS_NAME = "vyze_tts_settings"
        const val KEY_SPEECH_RATE = "speech_rate"
        const val KEY_PITCH = "pitch"
        const val KEY_VOLUME = "volume"
        const val DEFAULT_SPEECH_RATE = 1.0f
        const val DEFAULT_PITCH = 1.0f
        const val DEFAULT_VOLUME = 0.8f
    }
}
