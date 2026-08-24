package com.vyze.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Manages voice-driven accessibility commands using Android [SpeechRecognizer].
 *
 * Parsed intent triggers:
 * - "read", "text", "sign"          → OCR scan
 * - "what", "object", "detect", "look" → Object detection readout
 * - "light", "flash", "torch", "dark"  → Luminance check + torch toggle
 */
class VoiceCommandManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    /** Callbacks for recognized intent triggers */
    var onOcrRequested: (() -> Unit)? = null
    var onObjectDetectionRequested: (() -> Unit)? = null
    var onLightCheckRequested: (() -> Unit)? = null

    /** Callback for listening state changes */
    var onListeningStateChanged: ((listening: Boolean) -> Unit)? = null

    /** Callback for errors */
    var onError: ((error: String) -> Unit)? = null

    /** Callback for partial / final recognized text (for debugging / UI display) */
    var onTextRecognized: ((text: String) -> Unit)? = null

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
            isListening = true
            onListeningStateChanged?.invoke(true)
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech began")
        }

        override fun onRmsChanged(rmsdB: Float) { /* no-op */ }

        override fun onBufferReceived(buffer: ByteArray?) { /* no-op */ }

        override fun onEndOfSpeech() {
            Log.d(TAG, "Speech ended")
        }

        override fun onError(error: Int) {
            val errorMessage = mapErrorCode(error)
            Log.e(TAG, "Speech error: $errorMessage")
            isListening = false
            onListeningStateChanged?.invoke(false)
            onError?.invoke(errorMessage)

            // Auto-restart listening on recoverable errors
            if (error != SpeechRecognizer.ERROR_CLIENT &&
                error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            ) {
                restartListening()
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull().orEmpty()
            Log.d(TAG, "Recognized: $text")
            onTextRecognized?.invoke(text)

            // Allow listening to continue for continuous mode
            isListening = false
            onListeningStateChanged?.invoke(false)

            // Restart listening for continuous command detection
            if (text.isNotEmpty()) {
                restartListening()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull().orEmpty()
            if (text.isNotEmpty()) {
                onTextRecognized?.invoke(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) { /* no-op */ }
    }

    /**
     * Start continuous voice listening.
     * TTS should be paused before starting to prevent the app from hearing itself.
     */
    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError?.invoke("Speech recognition not available on this device")
            return
        }

        if (isListening) {
            Log.w(TAG, "Already listening")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(recognitionListener)
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            speechRecognizer?.startListening(intent)
            Log.d(TAG, "Started listening")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            isListening = false
            onListeningStateChanged?.invoke(false)
            onError?.invoke("Failed to start voice recognition: ${e.message}")
        }
    }

    /**
     * Stop voice listening.
     */
    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            isListening = false
            onListeningStateChanged?.invoke(false)
            Log.d(TAG, "Stopped listening")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop listening", e)
        }
    }

    /**
     * Restart listening after a short delay.
     */
    private fun restartListening() {
        // Small delay before restarting to avoid rapid-fire restarts
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isListening) {
                startListening()
            }
        }, RESTART_DELAY_MS)
    }

    /**
     * Parse recognized text and trigger the appropriate accessibility action.
     */
    fun parseAndTrigger(text: String) {
        val lower = text.lowercase().trim()

        // OCR intent triggers
        if (OCR_KEYWORDS.any { lower.contains(it) }) {
            Log.d(TAG, "OCR intent detected from: '$text'")
            onOcrRequested?.invoke()
            return
        }

        // Object detection intent triggers
        if (OBJECT_KEYWORDS.any { lower.contains(it) }) {
            Log.d(TAG, "Object detection intent detected from: '$text'")
            onObjectDetectionRequested?.invoke()
            return
        }

        // Light / torch intent triggers
        if (LIGHT_KEYWORDS.any { lower.contains(it) }) {
            Log.d(TAG, "Light check intent detected from: '$text'")
            onLightCheckRequested?.invoke()
            return
        }

        Log.d(TAG, "No matching intent found for: '$text'")
    }

    /**
     * Release resources. Should be called during lifecycle teardown.
     */
    fun destroy() {
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        onOcrRequested = null
        onObjectDetectionRequested = null
        onLightCheckRequested = null
        onListeningStateChanged = null
        onError = null
        onTextRecognized = null
    }

    fun isCurrentlyListening(): Boolean = isListening

    private fun mapErrorCode(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
        else -> "Unknown error ($error)"
    }

    companion object {
        private const val TAG = "VoiceCommandManager"
        private const val RESTART_DELAY_MS = 500L

        private val OCR_KEYWORDS = listOf("read", "text", "sign")
        private val OBJECT_KEYWORDS = listOf("what", "object", "detect", "look")
        private val LIGHT_KEYWORDS = listOf("light", "flash", "torch", "dark")
    }
}
