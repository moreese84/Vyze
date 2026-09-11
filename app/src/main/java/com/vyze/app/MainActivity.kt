package com.vyze.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    /** Clear the detected-TalkBack flag once the user confirms TalkBack is off. */
    fun clearTalkBackDetected() {
        talkBackDetected = false
    }

    // ── Speech-to-Text ────────────────────────────────────────────

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    /**
     * True while the fragment WANTS the mic open (a voice session, the voice
     * audition, report mode). Cleared the instant the user aborts with a tap
     * or the session ends. When false, stale recognizer callbacks from the
     * just-cancelled session are dropped at the source instead of reaching
     * the fragment — this is what caused the phantom "I did not catch that"
     * speech that used to precede tap results when switching between single
     * tap and double tap.
     */
    @Volatile
    var voiceSessionWanted = false

    // ── Noise Robustness (Tier 1) ────────────────────────────────
    // L1: Adaptive restart backoff — grows between failed recognition
    // cycles so the recognizer doesn't beep-loop in noisy rooms.
    // L3: Chatter counter — when ambient conversation keeps getting
    // rejected, pause free-form listening until the user taps.
    private var retryIndex = 0

    @Volatile
    private var noisePaused = false

    private var rejectedCycleCount = 0

    /** Last partial transcription from the active session (L2 stability check). */
    @Volatile
    private var lastPartialText: String = ""

    // ── Tier 2: Model-Native ASR Rescue ──────────────────────────
    // When Android's SpeechRecognizer fails in a noisy room (NO_MATCH,
    // SPEECH_TIMEOUT, ERROR_AUDIO), Vyze rescues the query with Gemma 4
    // E2B's NATIVE audio encoder: it records the user's speech directly
    // and transcribes it fully offline — no Google services, no network.
    private val asrScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Prevent fallback loops — only one model-ASR attempt per session. */
    @Volatile
    private var asrFallbackTried = false

    /**
     * True once the recognizer heard the user BEGIN speaking in the current
     * session (onBeginningOfSpeech). The model-ASR rescue is ONLY allowed for
     * speech that was attempted but failed (the noisy-room case). Pure silence
     * timeouts must never trigger the "Please say that again" rescue — doing so
     * hijacked quiet pauses and made double-tap sessions appear dead.
     */
    @Volatile
    private var speechAttempted = false

    /**
     * False while the hands-free voice audition is running — the rescue's
     * "Please say that again" cue must never interrupt audition samples.
     * Toggled by the fragment when the audition starts/stops.
     */
    @Volatile
    var modelAsrRescueAllowed = true

    /** Last locale Google's recognizer reported — reused for the model-ASR result. */
    @Volatile
    private var lastDetectedLocale: java.util.Locale? = null

    /**
     * Index into [FALLBACK_RECOGNITION_LOCALES] for the locale-fallback ladder.
     * When an English-pinned session fails (NO_MATCH / low-confidence — the
     * classic first-contact Malay/Chinese "no response" bug), the session is
     * silently retried in the next language of the ladder before giving up.
     * Reset when a transcription is accepted or the session is user-aborted.
     */
    private var localeFallbackIndex = 0

    /** Callback invoked when speech recognition completes with final text + detected language. */
    var onSpeechResult: ((String, java.util.Locale?) -> Unit)? = null

    /** Callback invoked with partial (live) transcription text for UI feedback. */
    var onPartialSpeechResult: ((String) -> Unit)? = null

    /** Callback for speech recognition errors (non-fatal). */
    var onSpeechError: ((String) -> Unit)? = null

    /** Invoked when repeated rejected cycles indicate a noisy room (Tier 1 L3). */
    var onNoiseDetected: (() -> Unit)? = null

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

        // Note: Process kill removed. The previous killProcess(myPid()) call
        // conflicted with the error-screen catch block in onCreate: when the
        // user pressed Back on the error screen, onDestroy ran with isFinishing=true
        // and killed the process — making it impossible to read the crash details
        // and producing an EXIT_SELF / status=255 in dumpsys.
        // GPU/LiteRT/CameraX resources are reclaimed by the OS on process death
        // or by the next cold start.
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
     * True when TalkBack (or another touch-exploration screen reader) is
     * enabled. Touch exploration means the screen reader intercepts taps,
     * which conflicts with Vyze's gesture map — the user should disable
     * it while using Vyze.
     */
    fun isTalkBackEnabled(): Boolean {
        return try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
            am != null && am.isEnabled && am.isTouchExplorationEnabled
        } catch (e: Throwable) {
            Log.w(TAG, "TalkBack detection failed: ${e.message}")
            false
        }
    }

    /**
     * Detect if TalkBack is enabled and warn the user once.
     * Vyze cannot duck or pause TalkBack (Android forbids it), so if the user
     * has TalkBack enabled we advise them to turn it off for the best
     * experience — or open Accessibility Settings for them on request.
     */
    private fun detectTalkBack() {
        if (isTalkBackEnabled()) {
            Log.i(TAG, "TalkBack detected — announcing advisory")
            CrashLogFile.log(TAG, "TalkBack enabled — advisory spoken")
            // One-time advisory after model is ready (handled by CameraFragment onboarding)
            // Store flag so CameraFragment can include the advisory in its onboarding message
            talkBackDetected = true
        }
    }

    /**
     * Open the system Accessibility Settings screen so the user can toggle
     * TalkBack off (TalkBack still works on that screen, so they can navigate
     * it). Returns false if the screen is unavailable on this device.
     */
    fun openAccessibilitySettings(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                Log.i(TAG, "Opened Accessibility Settings")
                true
            } else {
                Log.w(TAG, "No Accessibility Settings screen available")
                false
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to open Accessibility Settings: ${e.message}")
            false
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
            ttsManager.localized(
                "Vyze model ready. Tap anywhere or speak to ask a question, " +
                "such as what is in front of me. Tap again to interrupt or ask a new question.",
                "Model Vyze sedia. Sentuh di mana-mana atau bercakap untuk bertanya soalan, " +
                "contohnya apa yang ada di hadapan saya. Sentuh lagi untuk ganggu atau tanya soalan baharu.",
                "Vyze模型已就绪。点击任意位置或说话提问，例如我前面有什么。再次点击可打断或提出新问题。"
            )
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

    // ── Noise Robustness Helpers (Tier 1) ─────────────────────────

    /** L1: schedule the next mic restart with adaptive backoff. */
    private fun scheduleAdaptiveRestart() {
        if (noisePaused) {
            Log.d(TAG, "scheduleAdaptiveRestart: noise pause active — not restarting")
            return
        }
        val delay = RETRY_RAMP_MS[retryIndex]
        if (retryIndex < RETRY_RAMP_MS.size - 1) retryIndex++
        Log.d(TAG, "Adaptive mic restart in ${delay}ms (ramp=$retryIndex)")
        mainHandler.postDelayed({ startListeningSafely() }, delay)
    }

    /** L1: reset the backoff ramp (real query accepted or user tapped). */
    fun resetRetryBackoff() {
        retryIndex = 0
        Log.d(TAG, "Retry backoff reset to ${RETRY_RAMP_MS[0]}ms")
    }

    /** L3: count a rejected ambient-chatter cycle; pause listening at threshold. */
    private fun registerRejectedCycle() {
        rejectedCycleCount++
        Log.d(TAG, "Rejected cycle #$rejectedCycleCount (threshold $NOISE_DETECTION_THRESHOLD)")
        if (rejectedCycleCount >= NOISE_DETECTION_THRESHOLD) {
            noisePaused = true
            rejectedCycleCount = 0
            Log.w(TAG, "Noise detected — pausing free-form listening until user taps")
            CrashLogFile.log(TAG, "NOISE PAUSE triggered: $NOISE_DETECTION_THRESHOLD rejected cycles")
            mainHandler.post { onNoiseDetected?.invoke() }
        } else {
            scheduleAdaptiveRestart()
        }
    }

    /** Clear the noise pause + backoff — called when the user taps. */
    fun resumeAfterNoisePause() {
        noisePaused = false
        rejectedCycleCount = 0
        resetRetryBackoff()
        Log.i(TAG, "Noise pause cleared — listening can resume")
    }

    /**
     * Restore the one-shot model-ASR rescue budget. Called when a fresh
     * hands-free voice session opens (double tap / voice audition / report), so
     * each conversation gets one offline rescue when genuine speech fails.
     */
    fun resetModelAsrBudget() {
        asrFallbackTried = false
    }

    // ── Tier 2: Model-Native ASR Rescue ────────────────────────────

    /**
     * Rescue a failed recognition with Gemma 4 E2B's NATIVE audio encoder.
     *
     * Called when Android's SpeechRecognizer gives up (NO_MATCH,
     * SPEECH_TIMEOUT, ERROR_AUDIO) — the classic "query lost in a noisy
     * room" case. Vyze speaks a short cue, records the user's repeat with
     * [AudioCapture], and transcribes it fully offline via the model.
     *
     * @return true if the rescue path is running (caller must NOT end the
     *         session); false if the rescue is unavailable and the caller
     *         should fall through to normal error handling.
     */
    private fun attemptModelAsrRescue(originalError: String): Boolean {
        if (!modelAsrRescueAllowed) {
            Log.d(TAG, "Model-ASR rescue suppressed (e.g. voice audition active)")
            return false
        }
        if (asrFallbackTried) {
            Log.d(TAG, "Model-ASR rescue already attempted this session — skipping")
            return false
        }
        asrFallbackTried = true

        val core = (application as? VyzeApplication)?.coreController
        if (core == null || !core.isEngineReady() || core.isCurrentlyInferring()) {
            Log.d(TAG, "Model-ASR rescue unavailable (core=${core != null}, " +
                "ready=${core?.isEngineReady()}, inferring=${core?.isCurrentlyInferring()})")
            return false
        }

        Log.i(TAG, "SpeechRecognizer failed — launching model-native ASR rescue")
        CrashLogFile.log(TAG, "MODEL-ASR RESCUE: $originalError")

        // Cue the user to repeat, then record + transcribe on the IO scope.
        // speakThenCallback fires onDone on the UI thread.
        speakThenCallback(
            ttsManager.localized(
                "Please say that again.",
                "Sila sebut sekali lagi.",
                "请再说一遍。"
            )
        ) {
            asrScope.launch {
                try {
                    val audio = AudioCapture.recordSpeech()
                    if (audio == null) {
                        Log.w(TAG, "Model-ASR rescue: audio capture failed")
                        finishRescueWithError(originalError)
                        return@launch
                    }
                    // ── RE-CHECK BEFORE TRANSCRIBING ──────────────
                    // The readiness guard above ran BEFORE the spoken cue and the
                    // multi-second recording. A tap analysis or continuous
                    // snapshot may have started since — transcribing now would
                    // collide with the running inference (the engine runs one
                    // native generation at a time). Also bail if the user tapped
                    // away while we were recording.
                    if (!voiceSessionWanted) {
                        Log.d(TAG, "Model-ASR rescue: session aborted during recording — dropping")
                        return@launch
                    }
                    if (core.isCurrentlyInferring()) {
                        Log.d(TAG, "Model-ASR rescue: inference started during recording — aborting rescue")
                        finishRescueWithError(originalError)
                        return@launch
                    }
                    val transcription = core.transcribeAudio(audio)?.trim()
                    if (transcription.isNullOrBlank()) {
                        Log.w(TAG, "Model-ASR rescue: nothing understood")
                        finishRescueWithError(originalError)
                        return@launch
                    }
                    Log.i(TAG, "Model-ASR transcription: \"$transcription\"")
                    CrashLogFile.log(TAG, "MODEL-ASR transcription: \"$transcription\"")
                    runOnUiThread {
                        // The user may have tapped away while the rescue was
                        // recording (e.g. they chose a tap instead of repeating)
                        // — a late transcription must not fire as a fresh query.
                        if (!voiceSessionWanted) {
                            Log.d(TAG, "Model-ASR transcription after session aborted — dropping")
                            return@runOnUiThread
                        }
                        // Language FIX: detect the locale from the rescue
                        // TRANSCRIPTION itself. The old code reused
                        // lastDetectedLocale — typically the recognizer's own
                        // default (en) or a stale value from an earlier session
                        // — so a Malay/Chinese query rescued by the model-ASR
                        // path always came back English, breaking mirroring.
                        // detectLocaleFromText applies the same CJK/Malay
                        // detection used by the normal onResults path.
                        // Also persist the rescue locale for recognition:
                        // resolveRecognitionLocale() reads it, so the NEXT mic
                        // session listens in the language the rescue actually
                        // heard instead of falling back to en-US again.
                        val rescueLocale = detectLocaleFromText(transcription)
                        lastDetectedLocale = rescueLocale
                        onSpeechResult?.invoke(transcription, rescueLocale)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Model-ASR rescue crashed: ${e.message}")
                    finishRescueWithError(originalError)
                }
            }
        }
        return true
    }

    /** Fall through to the original speech error after a failed rescue. */
    private fun finishRescueWithError(originalError: String) {
        runOnUiThread {
            // The user may have tapped away while the rescue was recording —
            // then this error belongs to the dead session and must not reach
            // the fragment (it would clobber the tap's analysis state).
            if (!voiceSessionWanted) {
                Log.d(TAG, "Model-ASR rescue failed after session aborted — dropping error")
                return@runOnUiThread
            }
            onPartialSpeechResult?.invoke("")
            onSpeechError?.invoke(originalError)
        }
    }

    /** True when [s] contains any CJK ideograph (Chinese text has no spaces). */
    private fun hasCjkCharacters(s: String): Boolean =
        s.any { ch ->
            val cp = ch.code
            cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF
        }

    /** L2: decide whether a transcription is ambient conversation, not the user. */
    private fun isAmbientChat(text: String, confidence: FloatArray?): Boolean {
        // 1. Fragmentary single-word results ("yeah", "okay", "hi") — pass-over chatter
        val trimmed = text.trim()
        if (trimmed.split(Regex("\\s+")).size == 1 && trimmed.length < MIN_SINGLE_WORD_CHARS) {
            // Language FIX: CJK exempt — "你好" (2 chars) is a complete,
            // meaningful query. CJK characters carry several times the
            // information of Latin ones and the recognizer returns Chinese
            // WITHOUT spaces, so every short Chinese query looked like a
            // fragmentary single word and was dropped as ambient chatter
            // (the reported "Chinese → no response" symptom).
            if (!hasCjkCharacters(trimmed)) {
                return true
            }
        }
        // 2. Low recognition confidence — mumbles and mixed chatter score low.
        // Language FIX: ms/zh transcriptions are EXEMPT — on an English-default
        // phone the recognizer scores genuinely spoken Malay/Chinese low, and
        // dropping them reproduced the "no response" bug.
        if (confidence != null && confidence.isNotEmpty()) {
            val score = confidence.firstOrNull() ?: return false
            if (score in 0.0f..1.0f && score < MIN_CONFIDENCE &&
                detectLocaleFromText(text).language !in listOf("ms", "zh")
            ) {
                return true
            }
        }
        // 3. Unstable transcription: the final text shares no words with the
        //    partial stream — a sign the recognizer latched onto a different speaker.
        // Language FIX: CJK text has no spaces — word-splitting yields ONE
        // giant token per string, and a progressive recognizer's partial is
        // a PREFIX of the final ("这是" → "这是什么"), so the word-overlap
        // test rejected EVERY multi-stage Chinese recognition as unstable.
        // Compare characters for CJK instead of words.
        val partial = lastPartialText
        if (partial.isNotBlank() && partial.length >= 4 && trimmed.length >= 4) {
            if (hasCjkCharacters(trimmed)) {
                val finalChars = trimmed.toSet()
                if (partial.none { it in finalChars }) {
                    return true
                }
            } else {
                val finalWords = trimmed.lowercase().split(Regex("\\s+")).toSet()
                val partialWords = partial.lowercase().split(Regex("\\s+")).toSet()
                if (finalWords.none { it in partialWords }) {
                    return true
                }
            }
        }
        return false
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
        val words = lower.split(Regex("\\s+"))

        // ── Step 1: CJK (Chinese) detection ────────────────────
        var cjkCount = 0
        var totalLetters = 0
        for (ch in text) {
            if (ch.isLetter()) totalLetters++
            val cp = ch.code
            if (cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0xF900..0xFAFF) {
                cjkCount++
            }
        }
        if (totalLetters > 0 && cjkCount.toFloat() / totalLetters > 0.3f) {
            Log.d(TAG, "detectLocaleFromText: CJK detected ($cjkCount/$totalLetters) → zh")
            return java.util.Locale.CHINESE
        }

        // ── Step 2: Malay detection (multi-signal scoring) ──────
        // Malay uses Latin script so it can't be distinguished from English
        // by script alone. We use a weighted scoring system:
        //
        // Signal A: High-frequency Malay function words (appear in almost
        //           every Malay sentence — the most reliable signal)
        // Signal B: Malay morphological suffixes (unique to Malay grammar)
        // Signal C: Malay-specific word patterns
        // Signal D: Malay reduplication (very common, e.g. "rumah-rumah")
        //
        // A score of 3+ is sufficient to identify Malay. English rarely
        // scores above 1 because Malay function words don't exist in English.

        var malayScore = 0

        // Signal A: High-frequency function words
        // These appear in virtually every Malay sentence and never in English.
        val malayFunctionWords = setOf(
            "saya", "kami", "kita", "anda", "kamu", "mereka",  // pronouns
            "tidak", "tak", "bukan", "jangan", "belum",        // negation
            "dan", "atau", "tapi", "kerana", "sebab",          // conjunctions
            "ini", "itu", "sini", "situ", "sono",              // demonstratives
            "yang", "adalah", "ialah",                            // copula/relativizer
            "di", "ke", "dari", "dengan", "untuk",            // prepositions
            "dalam", "atas", "bawah", "antara", "sebelum",    // spatial/temporal
            "sudah", "sedang", "akan", "baru", "lagi",        // tense/aspect
            "boleh", "mahu", "nak", "perlu", "mesti",         // modals
            "ini", "apa", "siapa", "mana", "kenapa",          // question words
            "bila", "berapa", "mengapa"
        )
        for (word in words) {
            val cleaned = word.replace(Regex("[^a-z]"), "")
            if (cleaned in malayFunctionWords) {
                malayScore += 2  // high confidence signal
            }
        }

        // Signal B: Malay morphological suffixes
        // Malay is agglutinative — these suffixes are grammatical markers
        // that don't appear in English.
        // Language FIX: the original patterns included a leading '-'
        // ("-kan") which never matched — word.endsWith("-kan") is false for
        // "buatkan". Every Malay word scored 0 on this signal, so short
        // natural queries ("apa ini", "baca label") hovered below the old
        // threshold of 3 and fell through to English.
        val malaySuffixes = listOf(
            "kan", "an", "i", "lah", "kah", "tah",
            "nya", "pun"
        )
        for (word in words) {
            val cleaned = word.replace(Regex("[^a-z]"), "")
            // Suffixes like "an"/"i" also occur in English words; only
            // count them on words of realistic Malay root length.
            if (malaySuffixes.any { cleaned.length >= 4 && cleaned.endsWith(it) }) {
                malayScore += 1
            }
        }

        // Signal C: Malay-specific word patterns
        // These are words unique to Malay that don't exist in English.
        // Language FIX: split into DECISIVE action/noun words (+2) and
        // descriptive words (+1). Real Vyze queries are often verb-only
        // ("baca label", "analisis scene", "tolong tengok") — under the old
        // flat +1 they scored below the threshold and the answer came back
        // English, exactly the reported double-tap mirroring failure. Every
        // +2 word is impossible in ordinary English speech ("wang" was
        // deliberately EXCLUDED — it is also a common Chinese surname).
        val malayStrongPatterns = listOf(
            "baca", "tolong", "analisis", "tengok", "tunjuk", "tunjukkan",
            "lihat", "cari", "dengar", "cakap", "bagitahu",
            "hasil", "gambar", "kamera", "warna", "harga", "duit"
        )
        val malayPatterns = listOf(
            "selamat", "terima", "kasih",
            "macam", "bahasa", "malaysia",
            "rumah", "makan", "minum", "jalan",
            "kenal", "paham", "faham",
            "pergi", "datang", "balik",
            "besar", "kecil", "cantik", "bagus",
            "panas", "sejuk", "hujan", "cerah",
            "hari", "malam", "pagi", "petang",
            "orang", "anak", "ibu", "bapa",
            "objek", "benda", "senario", "teks",
            "semua", "sekarang", "sikit", "banyak", "sama", "ada"
        )
        for (word in words) {
            val cleaned = word.replace(Regex("[^a-z]"), "")
            when {
                cleaned in malayStrongPatterns -> malayScore += 2
                cleaned in malayPatterns -> malayScore += 1
            }
        }

        // Signal D: Malay reduplication
        // Very common in Malay (e.g., "rumah-rumah", "anak-anak",
        // "sikit-sikit", "satu-satu"). English almost never reduplicates.
        // Language FIX: the old pattern "(\\b\\w+)-(\\w+)\\b" ALSO matched
        // English hyphenations like "e-mail" / "check-in" (any two word
        // fragments around a hyphen), awarding +2 Malay points to English
        // text. Require both halves to be 2+ letters, alphabetic, and
        // (weak signal) share a stem — a real reduplication.
        if (Regex("(\\b[a-z]{2,})-([a-z]{2,})\\b").containsMatchIn(lower)) {
            malayScore += 2
        }

        Log.d(TAG, "detectLocaleFromText: Malay score=$malayScore (words=${words.size})")

        // Threshold: score >= 3 means confident Malay identification
        // This prevents false positives from occasional English words
        // that happen to match (e.g., "saya" could theoretically appear
        // in English speech-to-text as noise).
        // Language FIX: threshold lowered 3 → 2. A function word (2 pts) +
        // one suffix/pattern hit now qualifies, e.g. "baca ini untuk saya"
        // (ini+saya=4), "tolong baca label" (tolong=1). Under the old bar,
        // most short real queries fell through to the device-locale fallback
        // (English), which is exactly the reported mirroring failure.
        if (malayScore >= 2) {
            Log.d(TAG, "detectLocaleFromText: Malay detected (score=$malayScore) → ms")
            return java.util.Locale("ms", "MY")
        }

        // ── Step 3: Fallback — device default locale ────────────
        val deviceLocale = java.util.Locale.getDefault()
        Log.d(TAG, "detectLocaleFromText: no strong signal → device default $deviceLocale")
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

                // ── L3: NOISE PAUSE ──────────────────────────────
                // After repeated ambient-chatter rejections, stay quiet
                // until the user taps. Only an explicit tap re-opens the mic.
                if (noisePaused) {
                    Log.d(TAG, "startListeningSafely: noise pause active — staying quiet until tap")
                    return@post
                }

                // ── ANSWER PLAYBACK GUARD (voice session fix) ───────
                // A mic cycle scheduled by a stale error handler
                // (ERROR_RECOGNIZER_BUSY / ERROR_CLIENT retry, adaptive
                // backoff) can fire while the user's answer is still being
                // spoken. Starting recognition here BARGED IN and cut the
                // answer mid-sentence. Let the speech finish; the fragment
                // reopens the mic itself when the answer completes
                // (maybeOpenFollowUpWindow).
                // hasPendingSpeech() (deterministic utterance-ID tracking)
                // rather than isSpeaking(): the raw engine flag lingers true
                // during AudioTrack hardware drain AFTER onDone, which would
                // wrongly defer the fragment's legit follow-up reopen.
                if (ttsReady && ttsManager.hasPendingSpeech()) {
                    Log.d(TAG, "startListeningSafely: answer still queued/speaking — deferring mic start until it finishes")
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

    /**
     * Open the TTS engine's "install voice data" screen so the user can
     * download a better (neural) voice pack. Returns false if no installer
     * is available on the device.
     */
    fun openTtsVoiceInstaller(): Boolean {
        return try {
            val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                Log.i(TAG, "Opened TTS voice installer")
                true
            } else {
                Log.w(TAG, "No TTS voice installer available")
                false
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to open TTS voice installer: ${e.message}")
            false
        }
    }

    fun isCurrentlyListening(): Boolean = isListening

    fun isTtsReady(): Boolean = ttsReady

    fun isTtsSpeaking(): Boolean = ttsReady && ttsManager.isSpeaking()

    /**
     * Which language the recognizer should listen for, when known:
     * 1. A Malay or Chinese voice chosen in Vyze's voice settings — the user
     *    DECLARED that language, so recognition must match it even when the
     *    phone itself is English (the device default otherwise hears Malay as
     *    garbage / NO_MATCH).
     * 2. Otherwise the language most recently recognized this session, once it
     *    is Malay or Chinese (adaptive — a Malay speaker recognized once keeps
     *    getting Malay recognition for the rest of the session).
     * English (declared or detected) returns null → the recognizer uses its
     * own device default, which is English on English phones.
     */
    private fun resolveRecognitionLocale(): java.util.Locale? {
        val stored = TTSManager.storedLanguageLocale(this)
        if (stored.language == "ms" || stored.language == "zh") return stored
        return lastDetectedLocale?.takeIf { it.language == "ms" || it.language == "zh" }
    }

    // ── Internal Listening ────────────────────────────────────────

    /**
     * Start listening. Barge-in: stops TTS before opening mic.
     */
    private fun startListeningInternal() {
        try {
            // Fresh voice session — allow one model-ASR rescue attempt
            asrFallbackTried = false

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
            // ── LOCALE FALLBACK LADDER ───────────────────────────
            // If a previous English-pinned session failed and no ms/zh voice
            // is declared/detected yet, transparently retry in the ladder's
            // next language instead of surfacing "No speech detected". This
            // breaks the chicken-and-egg where adaptive detection (which needs
            // ONE successful ms/zh result) never engages on the first query.
            val ladderLocale = if (resolveRecognitionLocale() == null &&
                localeFallbackIndex < FALLBACK_RECOGNITION_LOCALES.size
            ) {
                FALLBACK_RECOGNITION_LOCALES[localeFallbackIndex]
            } else null
            // Fresh intent EVERY session — the recognition language follows the
            // user. Relying on the device default (English phone) made Malay and
            // Chinese queries fail with NO_MATCH or low-confidence garbage that
            // the chatter filter then silently dropped.
            val recognitionLocale = resolveRecognitionLocale() ?: ladderLocale
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                if (recognitionLocale != null) {
                    // Strong hint: listen for the user's actual language
                    // (Malay ms-MY / Chinese zh-CN on an English phone).
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE,
                        recognitionLocale.toLanguageTag()
                    )
                    Log.d(TAG, "Recognition language forced to ${recognitionLocale.toLanguageTag()}" +
                        if (ladderLocale != null) " (fallback ladder step ${localeFallbackIndex + 1})" else "")
                } else {
                    // Language FIX: ALWAYS pin a base language, never leave the
                    // recognizer on its own default. Most recognizer engines
                    // (including Google's on an English-default phone) IGNORE
                    // EXTRA_LANGUAGE_PREFERENCE — it is a hint, not a command.
                    // An unpinned session then defaults to en-US, so a Malay or
                    // Chinese query comes back as NO_MATCH (the "no response"
                    // bug). Pinning en-US keeps English queries working while
                    // the language-detection extras below let the engine follow
                    // Malay/Chinese speech within the same session.
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                        SUPPORTED_RECOGNITION_LANGUAGES
                    )
                    // API 34+: ask the engine to auto-detect the spoken
                    // language from the supported set (en-US, ms-MY, zh-CN)
                    // and switch mid-session — this is what makes first-contact
                    // Malay/Chinese queries work on English-default phones.
                    if (android.os.Build.VERSION.SDK_INT >= 34) {
                        putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                            SUPPORTED_RECOGNITION_LANGUAGES
                        )
                        putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, true)
                    }
                }
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
            }

            isListening = true
            // Fresh session — clear the partial stream so the L2 stability
            // check never compares against a previous session's text, and
            // reset the speech-attempt flag (no speech heard yet).
            lastPartialText = ""
            speechAttempted = false
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
                // The user started talking — if recognition then fails, that is
                // a genuine "lost in noise" case (eligible for the model-ASR
                // rescue), not a silence pause.
                speechAttempted = true
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech — waiting for final results")
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                // ── STALE-SESSION GATE ──────────────────────────────
                // The user may have aborted this session (a tap/double-tap
                // cancels the mic before the recognizer reports its result).
                // In that case the error belongs to the dead session — do NOT
                // restart the mic, do NOT run the model-ASR rescue, and do NOT
                // surface the error to the fragment (it would speak "I did not
                // catch that" right before the real answer arrives).
                if (!voiceSessionWanted) {
                    Log.d(TAG, "onError($error) after session aborted — dropping stale callback")
                    localeFallbackIndex = 0
                    return
                }
                // ── LOCALE FALLBACK LADDER RETRY ────────────────────
                // NO_MATCH on an English-pinned session usually means the
                // user spoke Malay/Chinese into an English acoustic model —
                // the "no response" bug. If the user DID speak, silently
                // retry the session in the ladder's next language (ms-MY →
                // zh-CN) before giving up. SPEECH_TIMEOUT is excluded: that
                // is silence, not a language mismatch. Exhausted ladder or a
                // declared/detected ms/zh voice falls through to normal
                // handling (including the model-ASR rescue).
                if (error == SpeechRecognizer.ERROR_NO_MATCH && speechAttempted &&
                    resolveRecognitionLocale() == null &&
                    // Guard: once an ENGLISH transcription has been accepted
                    // this session, NO_MATCH is likely real silence/noise, not
                    // a language mismatch — an English user must never get
                    // stuck cycling the ms/zh ladder.
                    lastDetectedLocale?.language != "en" &&
                    localeFallbackIndex < FALLBACK_RECOGNITION_LOCALES.size
                ) {
                    val nextLocale = FALLBACK_RECOGNITION_LOCALES[localeFallbackIndex]
                    localeFallbackIndex++
                    Log.i(TAG, "Ladder retry: NO_MATCH on English session — retrying as ${nextLocale.toLanguageTag()} (step $localeFallbackIndex)")
                    CrashLogFile.log(TAG, "LADDER RETRY: recognition → ${nextLocale.toLanguageTag()}")
                    isListening = false
                    speechRecognizer?.cancel()
                    startListeningAfterTtsStop()
                    return
                }
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
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_AUDIO -> {
                        // ── TIER 2 RESCUE: model-native ASR ──────────────
                        // The recognizer heard nothing useful (the classic
                        // "query lost in a noisy room" case). Before ending
                        // the session, let Gemma's own audio encoder listen:
                        // record the user's speech and transcribe it offline.
                        // Only one attempt per session — no beep loops.
                        //
                        // GATE: the rescue only runs when the user actually
                        // ATTEMPTED speech (onBeginningOfSpeech fired). Pure
                        // silence pauses inside the follow-up window must end
                        // quietly (the fragment reopens the mic) — running the
                        // rescue on every quiet pause spoke "Please say that
                        // again" unprompted and consumed the session.
                        if (speechAttempted && attemptModelAsrRescue(errorMsg)) {
                            return
                        }
                        // Rescue failed / unavailable / not attempted — end the
                        // session cleanly (no auto-restart, no idle listening).
                        onPartialSpeechResult?.invoke("")
                        onSpeechError?.invoke(errorMsg)
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> {
                        // Mic briefly busy — one short retry for the on-demand
                        // session, then the error surfaces and the session ends.
                        isListening = false
                        onPartialSpeechResult?.invoke("")
                        mainHandler.postDelayed({ startListeningSafely() }, 1000L)
                    }
                    else -> {
                        isListening = false
                        onSpeechError?.invoke(errorMsg)
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                // ── STALE-SESSION GATE ──────────────────────────────
                // A transcription can arrive AFTER the user already tapped
                // away (SpeechRecognizer commits asynchronously). Re-queuing
                // it as a fresh query would cancel the user's in-flight tap
                // analysis — drop it instead.
                if (!voiceSessionWanted) {
                    Log.d(TAG, "onResults after session aborted — dropping stale transcription")
                    return
                }
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val bestMatch = matches?.firstOrNull()

                if (bestMatch.isNullOrBlank()) {
                    Log.d(TAG, "onResults: empty — no speech recognized, ending session")
                    onPartialSpeechResult?.invoke("")
                    onSpeechError?.invoke("No speech detected.")
                    return
                }

                // ── L2: AMBIENT CHATTER FILTER ──────────────────────
                // In a noisy room, the recognizer commits background
                // conversation as if the user spoke it. Reject fragmentary,
                // low-confidence, or unstable transcriptions before they
                // become VLM queries.
                val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                if (isAmbientChat(bestMatch, confidence)) {
                    Log.d(TAG, "onResults: chatter filter dropped \"$bestMatch\"")
                    onPartialSpeechResult?.invoke("")
                    registerRejectedCycle()
                    return
                }

                // Real query — reset adaptive backoff + chatter counters
                resetRetryBackoff()
                rejectedCycleCount = 0

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
                // This covers two cases:
                // 1. Devices where EXTRA_LANGUAGE is missing from results Bundle
                // 2. SpeechRecognizer returns 'en' even for Malay/Chinese speech
                //    (common on English-default phones where the recognizer ignores
                //     the EXTRA_LANGUAGE_PREFERENCE hint)
                val textDetectedLocale = detectLocaleFromText(bestMatch)
                val finalLocale = if (detectedLocale != null &&
                    detectedLocale.language in listOf("ms", "zh")
                ) {
                    detectedLocale // Trust recognizer if it says Malay/Chinese
                } else {
                    textDetectedLocale // Use text detection for Malay/Chinese
                }

                Log.i(TAG, "onResults: \"$bestMatch\" lang=$finalLocale (bundle=$detectedLang)")
                CrashLogFile.log(TAG, "Speech result: \"$bestMatch\" lang=$finalLocale")
                lastDetectedLocale = finalLocale
                localeFallbackIndex = 0 // a session succeeded — ladder back to start
                onSpeechResult?.invoke(bestMatch, finalLocale)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!voiceSessionWanted) {
                    return // stale partial from an aborted session
                }
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    lastPartialText = partial
                    Log.d(TAG, "onPartialResults: \"$partial\"")
                    onPartialSpeechResult?.invoke(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        /**
         * Comma-separated BCP-47 languages hinted to the recognizer before any
         * user language is known — lets a Malay/Chinese speaker on an
         * English-default phone be heard on the very first session.
         */
        private const val SUPPORTED_RECOGNITION_LANGUAGES = "en-US,ms-MY,zh-CN"

        /**
         * Locale-fallback ladder for first-contact recognition failures.
         * An English-pinned session that returns NO_MATCH is retried as
         * ms-MY, then zh-CN, before the error surfaces. Reset when any
         * transcription is accepted or the session is user-aborted.
         */
        private val FALLBACK_RECOGNITION_LOCALES = listOf(
            java.util.Locale("ms", "MY"),
            java.util.Locale("zh", "CN")
        )

        // ── Tier 1: Noise Robustness ──────────────────────────────
        /** L1: restart delays after failed recognition cycles (ms). */
        private val RETRY_RAMP_MS = longArrayOf(300L, 1000L, 3000L, 8000L)

        /** L3: consecutive rejected chatter cycles before pausing listening. */
        private const val NOISE_DETECTION_THRESHOLD = 5

        /** L2: reject transcriptions below this confidence (0.0–1.0). */
        private const val MIN_CONFIDENCE = 0.35f

        /** L2: reject one-word results shorter than this ("yeah", "okay"). */
        private const val MIN_SINGLE_WORD_CHARS = 5
    }
}
