package com.vyze.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.fragment.NavHostFragment
import com.vyze.app.databinding.ActivityMainBinding

/**
 * Main entry point into the Vyze app. Single-activity pattern with
 * Navigation component hosting all fragments.
 *
 * ## Accessibility Flow
 * - Onboarding fires AFTER VLM model is ready (deferred, not on TTS init)
 * - Barge-in: any user touch or mic trigger instantly silences TTS
 * - Single-output: speak VLM result once, then go to IDLE (no infinite loop)
 * - Timeouts reset to IDLE without spoken error loops
 *
 * ## State Machine
 * IDLE → listening → (speech result) → analyzing → (VLM result) → speaking → IDLE
 */
class MainActivity : AppCompatActivity() {

    private var activityMainBinding: ActivityMainBinding? = null
    private val viewModel: MainViewModel by viewModels()
    private val ttsViewModel: TtsViewModel by viewModels()
    private var hapticManager: HapticManager? = null

    // ── TTS ───────────────────────────────────────────────────────

    private val ttsManager: TTSManager by lazy { ttsViewModel.ttsManager }
    private var ttsReady = false
    var talkBackDetected = false
        private set

    // ── Speech-to-Text ────────────────────────────────────────────

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    /** Cached intent for speech recognition sessions. */
    private var speechIntent: Intent? = null

    /** Callback invoked when speech recognition completes with final text + detected language. */
    var onSpeechResult: ((String, java.util.Locale?) -> Unit)? = null

    /** Callback invoked with partial (live) transcription text for UI feedback. */
    var onPartialSpeechResult: ((String) -> Unit)? = null

    /** Callback for speech recognition errors (non-fatal). */
    var onSpeechError: ((String) -> Unit)? = null

    /** Permission launcher for RECORD_AUDIO at runtime. */
    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Log.i(TAG, "RECORD_AUDIO granted — starting listening")
                startListeningInternal()
            } else {
                Log.w(TAG, "RECORD_AUDIO denied")
                Toast.makeText(this, "Microphone permission required for voice input", Toast.LENGTH_LONG).show()
            }
        }

    // ── Lifecycle ─────────────────────────────────────────────────

    @SuppressLint("MissingSuperCall")
    override fun onCreate(savedInstanceState: Bundle?) {
        var superCalled = false

        try {
            CrashLogFile.log(TAG, "onCreate start")

            val splashScreen = installSplashScreen()
            splashScreen.setKeepOnScreenCondition { !SplashViewModel.isMlReady }

            super.onCreate(savedInstanceState)
            superCalled = true

            try {
                hapticManager = HapticManager(applicationContext)
                hapticManager?.vibrateTap()
            } catch (e: Throwable) {
                Log.e(TAG, "HapticManager init failed: ${e.message}")
            }

            activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(activityMainBinding!!.root)

            try {
                supportFragmentManager.findFragmentById(R.id.fragment_container) as? NavHostFragment
            } catch (e: Throwable) {
                Log.e(TAG, "NavHostFragment lookup failed: ${e.message}")
            }

            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finish()
                }
            })

            initSpeechRecognizer()
            initTts()

            // Hold permanent audio focus for the entire session (like Google Lens / Be My Eyes)
            // This suppresses TalkBack and other accessibility audio while Vyze is active.
            mainHandler.postDelayed({
                ttsManager.holdSessionFocus()
                detectTalkBack()
            }, 500L)

            CrashLogFile.log(TAG, "onCreate completed successfully")

        } catch (e: Throwable) {
            Log.e(TAG, "FATAL onCreate crash: ${e.javaClass.simpleName}: ${e.message}", e)
            CrashLogFile.logError(TAG, "FATAL onCreate crash", e)
            CrashLogFile.flush()

            if (!superCalled) {
                try {
                    super.onCreate(savedInstanceState)
                    superCalled = true
                } catch (_: Throwable) {
                    Log.e(TAG, "super.onCreate() failed in catch block — unrecoverable")
                    return
                }
            }

            try {
                val errorText = buildString {
                    appendLine("VYZE LAUNCH ERROR")
                    appendLine()
                    appendLine("${e.javaClass.simpleName}: ${e.message}")
                    appendLine()
                    appendLine("Stack trace:")
                    appendLine(e.stackTraceToString())
                }

                val scrollView = ScrollView(this)
                val textView = TextView(this).apply {
                    text = errorText
                    setTextColor(Color.RED)
                    setBackgroundColor(Color.BLACK)
                    textSize = 12f
                    setPadding(32, 32, 32, 32)
                    setOnLongClickListener {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("crash", errorText)
                        )
                        Toast.makeText(this@MainActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        true
                    }
                }

                scrollView.addView(textView)
                setContentView(scrollView)
            } catch (e2: Throwable) {
                Log.e(TAG, "Error screen itself crashed: ${e2.message}")
                try {
                    Toast.makeText(this, "VYZE CRASH: ${e.message}", Toast.LENGTH_LONG).show()
                } catch (_: Throwable) {}
            }
        }
    }

    override fun onDestroy() {
        destroySpeechRecognizer()
        ttsManager.releaseSessionFocus()
        super.onDestroy()
        hapticManager?.cancel()
    }

    // ── TTS Setup ────────────────────────────────────────────────

    /**
     * Initialize TTS — no onboarding here.
     * Onboarding is triggered by CameraFragment after VLM model is ready.
     */
    private fun initTts() {
        if (ttsManager.isReady()) {
            ttsReady = true
        } else {
            ttsManager.onReady = { ttsReady = true }
        }
    }

    /**
     * Detect if TalkBack is enabled and warn the user once.
     * Vyze holds permanent audio focus to suppress TalkBack, but if the user
     * has TalkBack enabled, they should know they can disable it for the best
     * experience (fewer audio conflicts).
     */
    private fun detectTalkBack() {
        try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
            if (am != null && am.isEnabled && am.isTouchExplorationEnabled) {
                Log.i(TAG, "TalkBack detected — announcing advisory")
                CrashLogFile.log(TAG, "TalkBack enabled — advisory spoken")
                // One-time advisory after model is ready (handled by CameraFragment onboarding)
                // Store flag so CameraFragment can include the advisory in its onboarding message
                talkBackDetected = true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "TalkBack detection failed: ${e.message}")
        }
    }

    /**
     * Play the onboarding announcement. Called by CameraFragment when VLM is ready.
     * Speaks once, then returns to IDLE — no auto-listening restart.
     */
    fun playOnboardingSpeech() {
        if (!ttsReady) {
            Log.w(TAG, "playOnboardingSpeech called but TTS not ready")
            return
        }

        Log.i(TAG, "Playing onboarding announcement")
        CrashLogFile.log(TAG, "Playing onboarding announcement")

        ttsManager.speakImmediate(
            "Vyze model ready. Tap anywhere or speak to ask a question, " +
            "such as what is in front of me. Tap again to interrupt or ask a new question."
        )
    }

    /**
     * Speak text and invoke a callback when TTS finishes.
     * Used to speak VLM output. Does NOT restart listening — returns to IDLE.
     */
    fun speakThenCallback(text: String, onDone: () -> Unit) {
        if (!ttsReady || text.isBlank()) {
            onDone()
            return
        }

        // Barge-in: stop any current speech first
        ttsManager.stop()

        ttsManager.speak(text, TextToSpeech.QUEUE_FLUSH)

        // ADDITIVE listener: setOnUtteranceProgressListener wraps the caller's
        // listener around the global pendingUtteranceIds listener — both fire.
        // The global listener maintains hasPendingSpeech() accuracy.
        try {
            ttsManager.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS onStart: $utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS onDone: $utteranceId — going to IDLE")
                    runOnUiThread { onDone() }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.w(TAG, "TTS onError: $utteranceId")
                    runOnUiThread { onDone() }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.w(TAG, "TTS onError: $utteranceId code=$errorCode")
                    runOnUiThread { onDone() }
                }
            })
        } catch (e: Throwable) {
            Log.w(TAG, "Could not set UtteranceProgressListener: ${e.message}")
            mainHandler.postDelayed({ onDone() }, 500L)
        }
    }

    /**
     * Speak a short announcement without waiting for completion.
     * Used for status updates like "Analyzing scene..."
     */
    fun announceStatus(text: String) {
        if (!ttsReady || text.isBlank()) return
        ttsManager.speakImmediate(text)
    }

    /**
     * Barge-in: immediately silence any active TTS output.
     * Called on any user touch, mic trigger, or incoming spoken prompt.
     */
    fun interruptTts() {
        if (ttsReady && ttsManager.isSpeaking()) {
            Log.d(TAG, "Barge-in: stopping TTS")
            ttsManager.stop()
        }
    }

    /**
     * Barge-in with mic pause: stops TTS AND pauses the microphone
     * for [settleMs] milliseconds so the physical tap sound on glass
     * is not captured as a false audio intent.
     * After the settle period the mic automatically resumes.
     */
    fun interruptTtsWithMicPause(settleMs: Long = 100L) {
        // 0. Cancel SpeechRecognizer session to avoid hw conflict with ImageCapture
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
                isListening = false
            } catch (_: Throwable) {}
        }
        // 1. Stop TTS immediately
        interruptTts()
        // 2. Resume mic after settle delay (thread-safe)
        mainHandler.postDelayed({ startListeningSafely() }, settleMs)
    }

    // ── Speech Recognizer Setup ───────────────────────────────────

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun initSpeechRecognizer() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                Log.w(TAG, "Speech recognition not available on this device")
                return
            }

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(createRecognitionListener())
            }
            Log.i(TAG, "SpeechRecognizer initialized")
        } catch (e: Throwable) {
            Log.e(TAG, "SpeechRecognizer init failed: ${e.javaClass.simpleName}: ${e.message}")
            speechRecognizer = null
        }
    }

    private fun destroySpeechRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
            isListening = false
            Log.d(TAG, "SpeechRecognizer destroyed")
        } catch (e: Throwable) {
            Log.w(TAG, "SpeechRecognizer destroy failed: ${e.message}")
            speechRecognizer = null
            isListening = false
        }
    }

    // ── Public API ────────────────────────────────────────────────

    /**
     * Start listening for voice input.
     * Barge-in: stops any active TTS before opening the microphone.
     */
    /**
     * Thread-safe method to start speech recognition.
     * Detect language from transcribed text using Unicode character ranges.
     * Fallback for devices where SpeechRecognizer doesn't return EXTRA_LANGUAGE.
     */
    private fun detectLocaleFromText(text: String): java.util.Locale {
        if (text.isBlank()) return java.util.Locale.US

        val lower = text.lowercase()

        // Count character types
        var cjkCount = 0
        var totalLetters = 0
        for (ch in text) {
            if (ch.isLetter()) totalLetters++
            val cp = ch.code
            // CJK Unified Ideographs (Chinese/Japanese)
            if (cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF) {
                cjkCount++
            }
        }

        // If >30% of letters are CJK characters → Chinese
        if (totalLetters > 0 && cjkCount.toFloat() / totalLetters > 0.3f) {
            Log.d(TAG, "detectLocaleFromText: CJK detected ($cjkCount/$totalLetters) → zh")
            return java.util.Locale.CHINESE
        }

        // Malay detection — common Malay words/phrases that English speakers
        // would never say in sequence. This covers Latin-script Malay where
        // SpeechRecognizer can't distinguish from English.
        val malaySignals = listOf(
            "apa ini", "apa itu", "apa khabar", "selamat",
            "terima kasih", "tolong", "saya mahu", "saya nak",
            "saya perlu", "boleh tak", "macam mana",
            "di mana", "ini apa", "itu apa",
            "bagus", "cantik", "buruk", "besar", "kecil",
            "untuk saya", "saya tak", "saya tidak",
            "kenapa", "bila", "siapa"
        )
        if (malaySignals.any { lower.contains(it) }) {
            Log.d(TAG, "detectLocaleFromText: Malay phrases detected → ms")
            return java.util.Locale("ms", "MY")
        }

        // For remaining Latin-script languages, use device default locale.
        val deviceLocale = java.util.Locale.getDefault()
        Log.d(TAG, "detectLocaleFromText: Latin script → device default $deviceLocale")
        return deviceLocale
    }

    /**
     * Wraps ALL calls inside a Main Handler block to satisfy Android's
     * strict requirement that SpeechRecognizer operations run on the Main UI thread.
     * Calls cancel() first to clear any stale session before starting a fresh one.
     */
    fun startListeningSafely() {
        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    Log.w(TAG, "startListeningSafely: SpeechRecognizer is null")
                    return@post
                }

                // ALWAYS cancel first — clears stale audio buffer from previous
                // recognition session. Without this, the recognizer may carry
                // partial audio from the last session into the new one, causing
                // the second query to include stale speech data.
                speechRecognizer?.cancel()
                isListening = false

                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    Log.d(TAG, "startListeningSafely: Requesting RECORD_AUDIO permission")
                    audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    return@post
                }

                startListeningAfterTtsStop()
            } catch (e: Exception) {
                Log.e(TAG, "startListeningSafely error: ${e.message}")
                isListening = false
            }
        }
    }

    fun startListening() {
        if (speechRecognizer == null) {
            Log.w(TAG, "startListening called but SpeechRecognizer is null")
            return
        }

        if (isListening) {
            Log.d(TAG, "Already listening — ignoring duplicate startListening()")
            return
        }

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Requesting RECORD_AUDIO permission")
            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }

        startListeningInternal()
    }

    fun stopListening() {
        try {
            if (isListening) {
                speechRecognizer?.stopListening()
                isListening = false
                Log.d(TAG, "Stopped listening")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "stopListening failed: ${e.message}")
            isListening = false
        }
    }

    fun isCurrentlyListening(): Boolean = isListening

    fun isTtsReady(): Boolean = ttsReady

    fun isTtsSpeaking(): Boolean = ttsReady && ttsManager.isSpeaking()

    // ── Internal Listening ────────────────────────────────────────

    /**
     * Start listening. Barge-in: stops TTS before opening mic.
     */
    private fun startListeningInternal() {
        try {
            // Barge-in: stop TTS before opening the microphone
            if (ttsReady && ttsManager.isSpeaking()) {
                Log.d(TAG, "Barge-in: stopping TTS before speech recognition")
                ttsManager.stop()
                mainHandler.postDelayed({ startListeningAfterTtsStop() }, 200L)
                return
            }

            startListeningAfterTtsStop()

        } catch (e: Throwable) {
            Log.e(TAG, "startListeningInternal failed: ${e.javaClass.simpleName}: ${e.message}")
            isListening = false
            onSpeechError?.invoke("Failed to start voice input: ${e.message}")
        }
    }

    private fun startListeningAfterTtsStop() {
        try {
            // Reuse cached intent or create new one
            val intent = speechIntent ?: Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                // Do NOT set EXTRA_LANGUAGE — let the device use its default language.
                // This allows the recognizer to handle English, Malay, Chinese, etc.
                // based on what the user actually speaks.
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)

                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    400L
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    300L
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    1000L
                )
            }.also { speechIntent = it }

            isListening = true
            speechRecognizer?.startListening(intent)
            Log.i(TAG, "Speech recognition started — waiting for voice input")
            CrashLogFile.log(TAG, "Speech recognition started")

        } catch (e: Throwable) {
            Log.e(TAG, "startListeningAfterTtsStop failed: ${e.javaClass.simpleName}: ${e.message}")
            isListening = false
            onSpeechError?.invoke("Failed to start voice input: ${e.message}")
        }
    }

    /**
     * Create the RecognitionListener. Timeouts and errors reset to IDLE
     * without spoken error loops or automatic listening restart.
     */
    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "onReadyForSpeech")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "onBeginningOfSpeech")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech — waiting for final results")
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> {
                        Log.d(TAG, "onError: NO_MATCH — no speech detected")
                        "No speech detected."
                    }
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        Log.d(TAG, "onError: SPEECH_TIMEOUT — silence too long")
                        "Listening timed out."
                    }
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    else -> "Unknown error ($error)"
                }

                Log.w(TAG, "Speech error: $errorMsg (code=$error)")
                CrashLogFile.log(TAG, "Speech error: $errorMsg (code=$error)")

                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // Silently reset and auto-restart mic (thread-safe)
                        onPartialSpeechResult?.invoke("")
                        mainHandler.postDelayed({ startListeningSafely() }, 300L)
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> {
                        // CLIENT errors cascade when mic is grabbed by another service.
                        // Use exponential backoff: 1s → 2s → 4s (capped at 4s).
                        // Reset isListening so next startListeningSafely() actually fires.
                        isListening = false
                        val backoffMs = 1000L.coerceAtMost(4000L)
                        mainHandler.postDelayed({ startListeningSafely() }, backoffMs)
                    }
                    else -> {
                        isListening = false
                        onSpeechError?.invoke(errorMsg)
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val bestMatch = matches?.firstOrNull()

                // Extract detected language from SpeechRecognizer
                // Many devices don't return EXTRA_LANGUAGE in results — fall back to text-based detection
                val detectedLang = results?.getString(RecognizerIntent.EXTRA_LANGUAGE)
                val detectedLocale: java.util.Locale? = if (!detectedLang.isNullOrBlank()) {
                    try {
                        java.util.Locale.forLanguageTag(detectedLang)
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to parse locale '$detectedLang': ${e.message}")
                        null
                    }
                } else null

                // Fallback: detect language from the transcribed text itself
                // This covers devices where EXTRA_LANGUAGE is missing from results Bundle
                val finalLocale = detectedLocale ?: detectLocaleFromText(bestMatch ?: "")

                if (!bestMatch.isNullOrBlank()) {
                    Log.i(TAG, "onResults: \"$bestMatch\" lang=$finalLocale (bundle=$detectedLang)")
                    CrashLogFile.log(TAG, "Speech result: \"$bestMatch\" lang=$finalLocale")
                    onSpeechResult?.invoke(bestMatch, finalLocale)
                } else {
                    Log.d(TAG, "onResults: empty — no speech recognized, silently restarting mic")
                    onPartialSpeechResult?.invoke("")
                    mainHandler.postDelayed({ startListeningSafely() }, 300L)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    Log.d(TAG, "onPartialResults: \"$partial\"")
                    onPartialSpeechResult?.invoke(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
