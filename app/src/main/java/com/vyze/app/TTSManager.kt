package com.vyze.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TTSManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false

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
    }
}
