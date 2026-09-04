package com.vyze.app.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.Navigation
import com.vyze.app.*
import com.vyze.app.data.MemoryDao
import com.vyze.app.data.ScanRepository
import com.vyze.app.delegates.CameraSetupDelegate
import com.vyze.app.delegates.GestureRouter
import com.vyze.app.databinding.FragmentCameraBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Main camera fragment for Vyze accessibility app — VLM Snapshot Mode.
 *
 * ## Accessibility Flow
 * - Onboarding fires after VLM model is ready (deferred from TTS init)
 * - Barge-in: touch events instantly silence any active TTS
 * - Single-output: speak VLM result once, return to IDLE (no infinite loop)
 * - Mic is CLOSED at IDLE — voice sessions open on demand (double-tap)
 *
 * ## Gesture Map
 * Single tap → look (scene description) | Double tap → ask (voice session) |
 * Long press → light check | Triple tap → color | Triple tap + hold → SOS
 *
 * ## State Machine
 * IDLE → (tap) → ANALYZING → SPEAKING → (follow-up window) → IDLE
 * IDLE → (double tap) → LISTENING → (speech) → ANALYZING → SPEAKING → IDLE
 *
 * Follow-up window: after every answer the mic reopens hands-free for
 * CONVERSATION_WINDOW_MS — keep talking, no gesture needed until silence.
 */
class CameraFragment : Fragment() {

    private val TAG = "CameraFragment"

    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private val viewModel: MainViewModel by activityViewModels()
    private val ttsViewModel: TtsViewModel by activityViewModels()
    private val splashViewModel: SplashViewModel by viewModels()

    private lateinit var cameraSetup: CameraSetupDelegate
    private lateinit var gestureRouter: GestureRouter

    private lateinit var coreController: VyzeCoreController
    private lateinit var memoryDao: MemoryDao

    private lateinit var ttsManager: TTSManager
    private lateinit var hapticManager: HapticManager
    private var systemVibrator: Vibrator? = null
    private lateinit var flashlightManager: FlashlightManager
    private lateinit var reportManager: ReportManager

    private lateinit var backgroundExecutor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())

    private enum class AppState { LOADING, IDLE, LISTENING, ANALYZING, SPEAKING, REPORTING }

    @Volatile
    private var appState = AppState.LOADING

    @Volatile
    private var isCameraActive = false

    @Volatile
    private var onboardingSpoken = false

    /** True while the "better voice" install question awaits an answer. */
    @Volatile
    private var awaitingInstallVoiceAnswer = false

    /** True once the weak-voice prompt was handled this session (no repeat nags). */
    @Volatile
    private var voicePromptHandledInSession = false

    /** True right after the user chose to open the voice installer. */
    @Volatile
    private var pendingVoiceInstallConfirmation = false

    /** True right after the user asked to open Accessibility Settings. */
    @Volatile
    private var pendingAccessibilityReturn = false

    /** True while the hands-free voice-selection audition is running. */
    @Volatile
    private var voiceAuditionActive = false

    /** Candidate voices for the audition — entry 0 is "automatic". */
    private val voiceAuditionVoices = ArrayList<android.speech.tts.Voice>()
    private var voiceAuditionIndex = 0

    /** Debounce: prevents duplicate triggers from gesture + click overlap or speech re-trigger. */
    private var lastTriggerTime = 0L
    private val TRIGGER_DEBOUNCE_MS = 1000L

    /**
     * Atomic capture lock — prevents re-entry during the async window
     * between takeSnapshot() start and onBitmap/onError callback.
     * Independent of appState which legitimately transitions during the flow.
     */
    private val isCapturing = java.util.concurrent.atomic.AtomicBoolean(false)

    // ── Continuous Auto-Snapshot Mode ────────────────────────────
    // When enabled, automatically captures and describes the scene
    // every AUTO_SNAPSHOT_INTERVAL_MS — similar to Gemini Live.

    @Volatile
    private var isContinuousMode = false

    /** Timestamp when continuous mode was last activated. Used for thermal throttling. */
    @Volatile
    private var continuousModeStartTime = 0L

    private val autoSnapshotRunnable = object : Runnable {
        override fun run() {
            if (!isContinuousMode || !isAdded) return

            // ── THERMAL SAFETY: throttle after CONTINUOUS_MODE_THROTTLE_AFTER_MS ──
            // After extended use, force a minimum interval between captures
            // to prevent SoC thermal throttling on mid-tier chipsets.
            val elapsed = System.currentTimeMillis() - continuousModeStartTime
            val effectiveInterval = if (elapsed > CONTINUOUS_MODE_THROTTLE_AFTER_MS) {
                Log.d(TAG, "Continuous mode: thermal throttle active (${elapsed / 1000}s elapsed)")
                THERMALTHROTTLE_INTERVAL_MS
            } else {
                AUTO_SNAPSHOT_INTERVAL_MS
            }

            // ── SAFETY GUARDS ──────────────────────────────────
            // Only trigger if ALL conditions are met:
            if (appState == AppState.IDLE
                && !coreController.isCurrentlyInferring()
                && !ttsManager.hasPendingSpeech()
                && !isCapturing.get()
                && isCameraActive
            ) {
                Log.d(TAG, "Auto-snapshot: triggering continuous capture")
                triggerContinuousSnapshot()
            } else {
                Log.d(TAG, "Auto-snapshot: skipped (state=$appState, inferring=${coreController.isCurrentlyInferring()}, pending=${ttsManager.hasPendingSpeech()})")
            }

            // Schedule next tick if still in continuous mode
            if (isContinuousMode) {
                mainHandler.postDelayed(this, effectiveInterval)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        backgroundExecutor = Executors.newSingleThreadExecutor()

        ttsManager = ttsViewModel.ttsManager
        hapticManager = HapticManager(requireContext().applicationContext)

        // System vibrator for instant capture acknowledgement (~5ms latency)
        systemVibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vm = requireContext().getSystemService(VibratorManager::class.java)
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            requireContext().getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        flashlightManager = FlashlightManager()

        // ── Auto-Torch Audio Announcement ────────────────────────
        // When the environment darkens/brightens and the torch toggles,
        // announce the state change so blind users know what happened.
        flashlightManager.onTorchStateChanged = { isNowOn ->
            mainHandler.post {
                try {
                    if (isNowOn) {
                        ttsManager.speakQueued("It is dark. Flashlight is on.")
                    } else {
                        ttsManager.speakQueued("Light is sufficient. Flashlight is off.")
                    }
                } catch (_: Throwable) {}
            }
        }

        reportManager = ReportManager(requireContext().applicationContext)

        val app = requireActivity().applicationContext as VyzeApplication
        memoryDao = app.memoryDao

        coreController = app.coreController ?: VyzeCoreController(
            context = requireContext().applicationContext,
            ttsManager = ttsManager,
            memoryDao = memoryDao,
            interactionDao = app.interactionDao
        )

        // Wire VLM completion → speak result once, go to IDLE
        coreController.onInferenceComplete = { response ->
            activity?.runOnUiThread {
                try {
                    if (isAdded && _fragmentCameraBinding != null) {
                        Log.d(TAG, "VLM response: ${response.take(100)}...")
                        appState = AppState.SPEAKING
                        updateStatus("Ready")

                        val mainActivity = activity as? MainActivity
                        if (mainActivity != null && response.isNotBlank()) {
                            if (coreController.isDuplicateDescription(response)) {
                                Log.d(TAG, "Duplicate description — skipping TTS, returning to IDLE")
                                appState = AppState.IDLE
                                maybeOpenFollowUpWindow()
                            } else if (coreController.isStreamingActive()) {
                                Log.d(TAG, "Streaming active — waiting for TTS queue to drain")
                                waitForTtsDrain {
                                    appState = AppState.IDLE
                                    Log.d(TAG, "TTS drain complete — post-answer state")
                                    maybeOpenFollowUpWindow()
                                }
                            } else {
                                mainActivity.speakThenCallback(response) {
                                    appState = AppState.IDLE
                                    Log.d(TAG, "Response spoken — post-answer state")
                                    maybeOpenFollowUpWindow()
                                }
                            }
                        } else {
                            appState = AppState.IDLE
                            endFollowUpWindow()
                        }

                        // SAFETY TIMEOUT: If TTS doesn't finish within 10 seconds,
                        // force-reset to IDLE. Prevents indefinite ANALYZING/SPEAKING
                        // state when speakThenCallback's onDone doesn't fire.
                        mainHandler.postDelayed({
                            if (appState == AppState.SPEAKING) {
                                Log.w(TAG, "SPEAKING timeout — forcing IDLE")
                                appState = AppState.IDLE
                                updateStatus("Ready")
                            }
                        }, SPEAKING_TIMEOUT_MS)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "onInferenceComplete UI error: ${e.message}")
                    appState = AppState.IDLE
                }
            }
        }

        coreController.onProgressUpdate = { percent, step ->
            activity?.runOnUiThread {
                if (isAdded && _fragmentCameraBinding != null) {
                    updateStatus(step)
                }
            }
        }

        coreController.onError = { error ->
            activity?.runOnUiThread {
                if (isAdded && _fragmentCameraBinding != null) {
                    // ── SILENT DROP: if state has moved past ANALYZING/SPEAKING,
                    //    this error is from a stale session — don't interrupt the
                    //    current flow with error speech or state changes.
                    if (appState != AppState.ANALYZING && appState != AppState.SPEAKING) {
                        Log.d(TAG, "onError arrived in state=$appState — stale error, dropping")
                        return@runOnUiThread
                    }
                    appState = AppState.IDLE
                    updateStatus("Error: $error")
                    // Don't speak the error — just log it. Speaking errors during
                    // rapid tapping causes "Failed to capture" double-speak.
                    Log.e(TAG, "VLM error (not spoken to user): $error")
                }
            }
        }

        coreController.onStatusUpdate = { msg ->
            activity?.runOnUiThread {
                if (isAdded && _fragmentCameraBinding != null) {
                    updateStatus(msg)
                    if (msg.startsWith("VLM ready") && !onboardingSpoken) {
                        onboardingSpoken = true
                        val mainActivity = activity as? MainActivity
                        if (mainActivity != null && mainActivity.isTtsReady()) {
                            appState = AppState.IDLE
                            val talkBackHint = if (mainActivity.talkBackDetected) {
                                " TalkBack detected. Vyze needs the full screen to work: " +
                                "hold both volume keys for three seconds to turn it off, " +
                                "or say open accessibility settings and I will take you there. "
                            } else ""
                            mainActivity.speakThenCallback(
                                "${talkBackHint}Vyze is ready. Tap once to describe what is in front of you. " +
                                "Double tap to ask a question by voice. Press and hold to check the light."
                            ) {
                                Log.d(TAG, "Onboarding spoken — staying quiet (mic closed at IDLE)")
                            }
                        }
                    }
                }
            }
        }

        try {
            val backend = coreController.getEngineBackend()
            if (coreController.isEngineReady()) {
                updateStatus("Ready [$backend]")
                if (!onboardingSpoken) {
                    onboardingSpoken = true
                    val mainActivity = activity as? MainActivity
                    if (mainActivity != null && mainActivity.isTtsReady()) {
                        appState = AppState.IDLE
                        val talkBackHint = if (mainActivity.talkBackDetected) {
                            " TalkBack detected. Vyze needs the full screen to work: " +
                            "hold both volume keys for three seconds to turn it off, " +
                            "or say open accessibility settings and I will take you there. "
                        } else ""
                        mainActivity.speakThenCallback(
                            "${talkBackHint}Vyze is ready. Tap once to describe what is in front of you. " +
                            "Double tap to ask a question by voice. Press and hold to check the light."
                        ) {
                            Log.d(TAG, "Onboarding spoken — staying quiet (mic closed at IDLE)")
                        }
                    }
                }
            } else {
                updateStatus("Model still loading...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Engine status UI error: ${e.message}")
        }

        wireSpeechCallbacks()

        cameraSetup = CameraSetupDelegate()
        cameraSetup.setContext(requireContext().applicationContext)

        gestureRouter = GestureRouter(
            context = requireContext(),
            ttsManager = ttsManager,
            hapticManager = hapticManager,
            colorAnalyzer = ColorAnalyzer(),
            scanRepository = ScanRepository(requireContext().applicationContext),
            mainHandler = mainHandler
        )

        // ── Gesture Map (v2) ────────────────────────────────────────
        // Single tap  → "look": describe the scene (absorbs the old single-tap
        //               hit-test + double-tap navigation query).
        // Double tap  → "ask": open an on-demand voice session — the user
        //               speaks a question to the VLM (OCR reading included
        //               automatically via reading keywords).
        // Long press  → check ambient light + flashlight status.
        // Triple tap  → color analysis. Triple tap + hold → SOS.
        gestureRouter.onSingleTapAction = { x, y ->
            bargeInAndCapture(
                "User tapped at position (${x.toInt()}, ${y.toInt()}). " +
                "Describe what is in front of me and around me for navigation. " +
                "If the tapped object is a packaged product (packet, box, bottle, can), " +
                "first say its BRAND name and product type exactly as printed " +
                "(for example: Maggi instant noodle packet), then continue. " +
                "If the tapped object has text on it (a label, box, or sign), " +
                "read it aloud verbatim as whole words and sentences, never spelling letter by letter. " +
                "Read the ENTIRE text on the object in reading order; do not stop halfway."
            )
        }

        gestureRouter.onDoubleTapAction = {
            openVoiceQuery()
        }

        gestureRouter.onLongPressAction = {
            performLightCheck()
        }

        gestureRouter.attach(fragmentCameraBinding.cameraContainer)

        backgroundExecutor.execute {
            fragmentCameraBinding.viewFinder.post {
                setUpCamera()
            }
        }

        // NOTE: no viewFinder.setOnClickListener fallback — it fired on every
        // tap-up and would defeat single-vs-double disambiguation (a double
        // tap would also have triggered a scene analysis).
    }

    override fun onResume() {
        super.onResume()

        if (::coreController.isInitialized) {
            coreController.resetSessionState()
        }

        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Log.w(TAG, "Permissions missing — requesting via PermissionsFragment")
            try {
                Navigation.findNavController(requireActivity(), R.id.fragment_container)
                    .navigate(CameraFragmentDirections.actionCameraToPermissions())
            } catch (e: Exception) {
                Log.e(TAG, "Navigation to PermissionsFragment failed: ${e.message}")
            }
        }

        if (cameraSetup.cameraProvider != null) {
            cameraSetup.rebindCamera(
                requireContext(), this,
                fragmentCameraBinding.viewFinder
            )
        }

        isCameraActive = true

        if (coreController.isEngineReady() && onboardingSpoken) {
            // Mic stays CLOSED at IDLE — voice sessions open only on demand
            // (double-tap voice query, report mode).
            appState = AppState.IDLE
        } else {
            appState = AppState.LOADING
        }

        maybePromptBetterVoice()
        confirmVoiceInstallIfReturned()
        confirmAccessibilityReturn()
    }

    override fun onPause() {
        super.onPause()
        isCameraActive = false
        stopContinuousLoop()
        endFollowUpWindow()
        cameraSetup.releaseCamera()
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        if (this::gestureRouter.isInitialized) gestureRouter.detach()

        val app = try {
            requireActivity().applicationContext as VyzeApplication
        } catch (e: Exception) { null }
        if (app?.coreController != coreController && this::coreController.isInitialized) {
            coreController.destroy()
        }

        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)

        if (this::ttsManager.isInitialized) ttsManager.onDestroy()
        if (this::hapticManager.isInitialized) hapticManager.cancel()

        cameraSetup.destroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cameraSetup.updateRotation(fragmentCameraBinding.viewFinder.display)
    }

    // ══════════════════════════════════════════════════════════════════
    // Camera Setup
    // ══════════════════════════════════════════════════════════════════

    private fun setUpCamera() {
        cameraSetup.setupCamera(
            context = requireContext(),
            lifecycleOwner = this,
            previewView = fragmentCameraBinding.viewFinder,
            flashlightMgr = flashlightManager
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // Barge-In + Capture
    // ══════════════════════════════════════════════════════════════════

    private fun bargeInAndCapture(query: String) {
        // Any new gesture always ends an in-progress voice audition first.
        stopVoiceAuditionIfActive()

        // ── DEBOUNCE: reject if triggered too recently ───────────
        // Prevents double-fire from gesture+click overlap or speech re-trigger.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTriggerTime < TRIGGER_DEBOUNCE_MS) {
            Log.d(TAG, "Barge-in debounced (${now - lastTriggerTime}ms < ${TRIGGER_DEBOUNCE_MS}ms)")
            return
        }
        lastTriggerTime = now

        // ── ATOMIC STATE CLAIM: lock before any async work ────────
        // Must happen synchronously on the main thread BEFORE calling
        // interruptTtsWithMicPause() (which may restart speech recognition).
        // This prevents a secondary speech callback from re-entering.
        if (appState == AppState.ANALYZING || appState == AppState.SPEAKING) {
            Log.d(TAG, "Barge-in: state=$appState — cancelling active work")
            coreController.resetForNewCapture()
        } else {
            coreController.resetForNewCapture()
        }
        // Immediately set ANALYZING — blocks any concurrent triggers
        // (speech callbacks, second taps) from passing the guard.
        appState = AppState.ANALYZING

        // ── TAP = TOUCH INPUT: end any hands-free follow-up window ──
        // A tap is a deliberate gesture. Close the conversation mic (and
        // revoke the mic grant in stopVoiceListening() so the cancelled
        // recognizer's late error/results are dropped at the source) and
        // mark this answer as one that must NOT reopen the mic on
        // completion. Previously the follow-up window stayed open under
        // tap analyses — its cancellation error surfaced as a phantom
        // "I did not catch that" before every result.
        keepMicOpenAfterAnswer = false
        endFollowUpWindow()

        val mainActivity = activity as? MainActivity
        mainActivity?.stopListening()
        // NOTE: do NOT reopen the mic here (interruptTtsWithMicPause would
        // restart listening ~100ms later). In a noisy room, other people's
        // conversation gets transcribed as a "query" while the VLM is still
        // analyzing the tap — cancelling the user's in-flight analysis
        // (resetForNewCapture) and replacing it with the overheard chat.
        // The mic stays closed for the whole ANALYZING/SPEAKING phase and
        // is reopened automatically when the app returns to IDLE.
        mainActivity?.interruptTts()
        // Explicit user action: clear any noise pause + backoff (Tier 1 L1/L3)
        // so the mic reopens normally once this analysis finishes.
        mainActivity?.resumeAfterNoisePause()

        // Now safe to extract frame — isInferring lock + ANALYZING state
        // will reject any duplicate triggerVlmSnapshot calls.
        triggerVlmSnapshot(query)
    }

    // ══════════════════════════════════════════════════════════════════
    // VLM Snapshot Trigger
    // ══════════════════════════════════════════════════════════════════

    private fun triggerVlmSnapshot(query: String) {
        CrashLogFile.log(TAG, "triggerVlmSnapshot: state=$appState, engineReady=${coreController.isEngineReady()}, inferring=${coreController.isCurrentlyInferring()}, capturing=${isCapturing.get()}")

        // ── CAPTURE LOCK: reject during async frame extraction ────
        if (!isCapturing.compareAndSet(false, true)) {
            Log.d(TAG, "Capture already in progress — silently dropping duplicate trigger")
            return
        }

        // ── STATE GUARD ──────────────────────────────────────────
        // bargeInAndCapture already sets appState = ANALYZING, so we
        // also allow ANALYZING to pass (it means bargeIn claimed it).
        // Speech callbacks set appState = IDLE before calling us.
        if (appState != AppState.IDLE && appState != AppState.LISTENING && appState != AppState.ANALYZING) {
            Log.d(TAG, "State=$appState — rejecting snapshot trigger")
            isCapturing.set(false)
            updateStatus("Busy...")
            return
        }

        if (!coreController.isEngineReady()) {
            Log.d(TAG, "VLM not ready yet")
            isCapturing.set(false)
            updateStatus("Model still loading...")
            return
        }

        if (coreController.isCurrentlyInferring()) {
            Log.d(TAG, "Engine busy — ignoring")
            isCapturing.set(false)
            updateStatus("Already analyzing...")
            return
        }

        hapticManager.vibrateTap()
        updateStatus("Capturing...")
        CrashLogFile.log(TAG, "Calling cameraSetup.takeSnapshot()")

        cameraSetup.takeSnapshot(
            onBitmap = { bitmap ->
                // ── SINGLE EXIT: guarantee isCapturing is cleared ──
                try {
                    // Validate bitmap — if recycled or corrupted, abort silently
                    if (bitmap.isRecycled) {
                        CrashLogFile.log(TAG, "Bitmap recycled — silently dropping")
                        // Do NOT speak error here — a newer trigger may have
                        // already started. Just reset state quietly.
                        activity?.runOnUiThread {
                            if (isAdded && _fragmentCameraBinding != null && appState == AppState.ANALYZING) {
                                appState = AppState.IDLE
                                updateStatus("Ready")
                            }
                        }
                        return@takeSnapshot
                    }

                    CrashLogFile.log(TAG, "onBitmap callback: ${bitmap.width}x${bitmap.height}")

                    // Double-check engine isn't already running a different inference
                    if (coreController.isCurrentlyInferring()) {
                        CrashLogFile.log(TAG, "Engine busy — recycling bitmap")
                        bitmap.recycle()
                        return@takeSnapshot
                    }

                    // ── INSTANT HAPTIC: acknowledge frame capture immediately ──
                    // This fires BEFORE VLM inference starts, giving the user
                    // tactile feedback that their input was registered (~5ms).
                    systemVibrator?.vibrate(
                        VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                    )

                    // State already ANALYZING from bargeInAndCapture — just update UI
                    activity?.runOnUiThread {
                        if (isAdded && _fragmentCameraBinding != null) {
                            if (appState != AppState.ANALYZING) appState = AppState.ANALYZING
                            updateStatus("Analyzing...")
                            (activity as? MainActivity)?.announceStatus("Analyzing scene...")
                        }
                    }

                    CrashLogFile.log(TAG, "Calling coreController.triggerSnapshot()")
                    coreController.triggerSnapshot(bitmap, query)
                } catch (e: Throwable) {
                    CrashLogFile.logError(TAG, "onBitmap error: ${e.javaClass.simpleName}: ${e.message}", e)
                    try { bitmap.recycle() } catch (_: Throwable) {}
                    activity?.runOnUiThread {
                        if (isAdded && _fragmentCameraBinding != null && appState == AppState.ANALYZING) {
                            appState = AppState.IDLE
                            updateStatus("Error: ${e.message}")
                        }
                    }
                } finally {
                    // ALWAYS release capture lock — even if bitmap was recycled
                    isCapturing.set(false)
                }
            },
            onError = { error ->
                CrashLogFile.logError(TAG, "Snapshot failed: $error")
                Log.e(TAG, "Snapshot failed: $error")
                isCapturing.set(false)
                // ── ALWAYS recover: reset state + speak error + restart mic.
                //    Never leave the user stuck on 'Capturing...' screen.
                activity?.runOnUiThread {
                    if (isAdded && _fragmentCameraBinding != null) {
                        appState = AppState.IDLE
                        updateStatus("Capture failed")
                        ttsManager.speakImmediate("Could not capture the scene. Please try again.")
                    }
                }
            }
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // Speech Recognition Integration
    // ══════════════════════════════════════════════════════════════════

    private fun wireSpeechCallbacks() {
        val activity = requireActivity() as? MainActivity ?: return

        activity.onSpeechResult = { spokenText, detectedLocale ->
            if (spokenText.isNotBlank()) {
                Log.i(TAG, "Speech result: \"$spokenText\" lang=$detectedLocale")
                if (voiceAuditionActive) {
                    handleVoiceAuditionCommand(spokenText)
                } else if (awaitingInstallVoiceAnswer &&
                    !isVoiceSettingsRequest(spokenText) &&
                    !isAccessibilitySettingsRequest(spokenText)
                ) {
                    // A voice-settings or accessibility-settings request always
                    // wins over the pending "install a better voice?" yes/no
                    // question — the user is asking for something else, so
                    // route them to that instead of parsing a yes/no answer.
                    handleVoiceInstallAnswer(spokenText)
                } else {
                    activity.interruptTts()
                    coreController.setUserLocale(detectedLocale)

                    when {
                        // ── REPORT MODE: speech is the report content ──
                        appState == AppState.REPORTING -> {
                            handleReportContent(spokenText)
                        }
                        // ── REPORT TRIGGER: enter report mode ─────────
                        reportManager.isReportTrigger(spokenText) -> {
                            enterReportMode()
                        }
                        // ── NORMAL VLM PIPELINE ──────────────────────
                        else -> {
                        // ── ACCESSIBILITY SETTINGS COMMAND ───────
                        // "open accessibility settings" takes the user to
                        // the system screen to disable TalkBack.
                        if (isAccessibilitySettingsRequest(spokenText)) {
                            openAccessibilitySettingsFlow()
                        } else if (isVoiceSettingsRequest(spokenText)) {
                            // "voice settings" / "change your voice" starts
                            // the hands-free voice audition (no screen needed).
                            startVoiceAudition()
                        } else if (appState == AppState.ANALYZING || appState == AppState.SPEAKING) {
                                // ── NOISE GATE ────────────────────────
                                // The recognizer can transcribe other people's
                                // conversation as a "query" in a noisy room.
                                // If the app is already analyzing or speaking,
                                // drop the result instead of cancelling the
                                // user's in-flight query — otherwise ambient
                                // chat makes the response come back "lost" and
                                // restarts the recognition beep loop.
                                Log.d(TAG, "Speech result during $appState — dropping (possible ambient noise)")
                            } else if (coreController.isTextOnlyQuery(spokenText)) {
                                // ── TEXT-ONLY Q&A ────────────────────────
                                // General-knowledge question ("what is
                                // paracetamol used for?") — no camera frame
                                // needed. Faster + cheaper than image inference.
                                Log.d(TAG, "Text-only query: \"$spokenText\"")
                                coreController.resetForNewCapture()
                                appState = AppState.ANALYZING
                                updateStatus("Answering...")
                                coreController.triggerTextQuery(spokenText)
                            } else {
                                coreController.resetForNewCapture()
                                appState = AppState.IDLE
                                updateStatus("Heard: \"$spokenText\"")
                                triggerVlmSnapshot(spokenText)
                            }
                        }
                    }
                }
            }
        }

        activity.onPartialSpeechResult = { partial ->
            if (isAdded && _fragmentCameraBinding != null) {
                if (partial.isNullOrBlank()) {
                    appState = AppState.LISTENING
                    updateStatus("Listening...")
                } else {
                    updateStatus("Listening: \"$partial\"")
                }
            }
        }

        activity.onSpeechError = { errorMsg ->
            if (isAdded && _fragmentCameraBinding != null) {
                Log.w(TAG, "Speech error: $errorMsg")

                if (voiceAuditionActive) {
                    // Voice audition: a silent/errored cycle just means the
                    // user didn't answer — re-hint and keep listening.
                    val mainActivity = activity as? MainActivity
                    if (mainActivity != null) {
                        mainActivity.speakThenCallback("Say next, use this, or cancel.") {
                            mainHandler.postDelayed({ startVoiceListening() }, VOICE_SESSION_OPEN_DELAY_MS)
                        }
                    }
                } else if (appState != AppState.LISTENING && appState != AppState.REPORTING) {
                    // ── STALE-ERROR GUARD ──────────────────────────────
                    // A recognizer error can arrive AFTER the listening session
                    // it belonged to was superseded (a tap started an analysis,
                    // an answer is speaking, the window already closed). Such an
                    // error must never force state changes or speak "I did not
                    // catch that" around a fresh result.
                    Log.d(TAG, "Speech error during state=$appState — stale, ignoring")
                } else {
                    // ── CONVERSATION WINDOW: a silent recognizer cycle inside
                    // the follow-up window just means the user paused. Quietly
                    // reopen the mic while the deadline is still valid; the
                    // watchdog closes the window when it expires.
                    val pausedInWindow = inConversationWindow &&
                        (errorMsg.contains("No speech") || errorMsg.contains("timed out"))
                    if (pausedInWindow) {
                        // NEVER clobber an in-flight tap/analysis/answer: if the
                        // app is not actually LISTENING, this error belongs to a
                        // stale cycle (e.g. the mic was cancelled by a tap that
                        // started an analysis). Close the window state quietly
                        // and let the analysis/answer finish on its own.
                        if (appState != AppState.LISTENING) {
                            Log.d(TAG, "Speech error during $appState — stale, closing window only")
                            endFollowUpWindow()
                        } else if (android.os.SystemClock.elapsedRealtime() < conversationDeadlineMs) {
                            appState = AppState.LISTENING
                            updateStatus("Listening...")
                            mainHandler.postDelayed({ startVoiceListening() }, FOLLOW_UP_RETRY_DELAY_MS)
                        } else {
                            endFollowUpWindow()
                        }
                    } else {
                        val wasVoiceSession = appState == AppState.LISTENING
                        appState = AppState.IDLE
                        updateStatus("Ready")
                        // Voice sessions are on-demand — an empty/failed session
                        // ends quietly instead of auto-restarting the mic forever.
                        if (wasVoiceSession) {
                            try {
                                ttsManager.speakQueued("I did not catch that. Double tap and try again.")
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }
        }

        // ── TIER 1 L3: NOISY-ROOM AUTO-ADAPT ─────────────────────
        // The recognizer kept committing ambient conversation. Announce
        // it and stay quiet until the user taps to analyze.
        activity.onNoiseDetected = {
            if (isAdded && _fragmentCameraBinding != null) {
                Log.w(TAG, "Noisy room detected — ending conversation window")
                stopVoiceAuditionIfActive()
                endFollowUpWindow()
                updateStatus("Noisy — tap or double tap to continue")
                try {
                    ttsManager.speakQueued("The room is noisy. Tap once to look, or double tap to ask.")
                } catch (_: Throwable) {}
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Voice-Driven Bug Reporting
    // ══════════════════════════════════════════════════════════════════

    /**
     * Enter report mode. The next speech result will be treated as
     * bug report content instead of a VLM query.
     */
    private fun enterReportMode() {
        stopVoiceAuditionIfActive()
        endFollowUpWindow()
        appState = AppState.REPORTING
        updateStatus("Report mode — speak your issue")
        Log.i(TAG, "Entering REPORTING mode")
        CrashLogFile.log(TAG, "Report mode activated")

        val mainActivity = activity as? MainActivity
        mainActivity?.speakThenCallback(
            "Report mode activated. Please describe the issue you want to report."
        ) {
            // After the prompt finishes, start listening for the report
            mainHandler.postDelayed({ startVoiceListening() }, 300L)
        }
    }

    /**
     * Handle the report content spoken by the user.
     * Saves to file, then launches email intent.
     */
    private fun handleReportContent(reportText: String) {
        Log.i(TAG, "Report content received: ${reportText.take(80)}...")
        CrashLogFile.log(TAG, "Report content: ${reportText.take(100)}")
        updateStatus("Saving report...")

        // Save report to local file
        val reportFile = reportManager.saveReport(reportText)
        if (reportFile == null) {
            appState = AppState.IDLE
            updateStatus("Report save failed")
            val mainActivity = activity as? MainActivity
            mainActivity?.speakThenCallback(
                "Failed to save report. Please try again."
            ) {}
            return
        }

        // Launch email intent
        val emailSent = reportManager.sendReportEmail(reportFile)

        if (emailSent) {
            appState = AppState.IDLE
            updateStatus("Report saved & email ready")
            val mainActivity = activity as? MainActivity
            mainActivity?.speakThenCallback(
                "Report saved. Email is ready — please tap Send to submit."
            ) {}
        } else {
            // Email client not available — report still saved locally
            appState = AppState.IDLE
            updateStatus("Report saved (no email client)")
            val mainActivity = activity as? MainActivity
            mainActivity?.speakThenCallback(
                "Report saved to Downloads folder. No email app found."
            ) {}
        }
    }

    /**
     * Wait for the TTS engine to finish playing all queued utterances.
     *
     * KEY FIX: Initial delay of 500ms before first poll. The final
     * flushRemainingSentenceBuffer() posts speak() to mainHandler, and
     * then onInferenceComplete also posts to mainHandler. When
     * waitForTtsDrain runs, the TTS engine may not have registered the
     * utterance as "speaking" yet — isSpeaking() returns false on the
     * first check, causing premature exit and audio cutoff.
     *
     * The 500ms initial delay gives the TTS engine time to transition
     * from "queued" to "active playback" before we start polling.
     */
    private fun waitForTtsDrain(onDone: () -> Unit) {
        // Use deterministic utterance ID tracking instead of isSpeaking() polling.
        // hasPendingSpeech() returns true iff pendingUtteranceIds is non-empty.
        // Each speak() call adds an ID; onDone/onError removes it.
        //
        // The final utterance in the queue includes a silent tail padding
        // (via TTSManager.playSilentUtterance) that keeps hasPendingSpeech()
        // true until the hardware AudioTrack buffer is fully drained.
        //
        // After hasPendingSpeech() == false, an additional 400ms grace
        // period ensures the speaker has finished emitting the last
        // audible phoneme before we release audio focus and restart mic.
        val checkRunnable = object : Runnable {
            override fun run() {
                if (ttsManager.hasPendingSpeech()) {
                    mainHandler.postDelayed(this, 150L)
                } else {
                    // All utterances (including silent tail) have completed.
                    // Add a final 400ms grace for AudioTrack hardware drain.
                    // Audio focus stays held for the entire session — only released on app destroy.
                    mainHandler.postDelayed({
                        onDone()
                    }, AUDIO_DRAIN_GRACE_MS)
                }
            }
        }
        mainHandler.postDelayed(checkRunnable, 300L)
    }

    // ══════════════════════════════════════════════════════════════════
    // Continuous Auto-Snapshot Mode
    // ══════════════════════════════════════════════════════════════════

    /**
     * Trigger a continuous-mode snapshot. Unlike [triggerVlmSnapshot],
     * this does NOT acquire the debounce or isCapturing locks — the
     * loop's own state guards prevent concurrent calls.
     */
    private fun triggerContinuousSnapshot() {
        if (!coreController.isEngineReady()) return
        if (coreController.isCurrentlyInferring()) return
        if (isCapturing.get()) return

        if (!isCapturing.compareAndSet(false, true)) return

        appState = AppState.ANALYZING
        // Continuous mode is touch/auto driven — never reopen the mic after it.
        keepMicOpenAfterAnswer = false
        updateStatus("Scanning...")

        cameraSetup.takeSnapshot(
            onBitmap = { bitmap ->
                try {
                    if (bitmap.isRecycled) {
                        isCapturing.set(false)
                        appState = AppState.IDLE
                        return@takeSnapshot
                    }

                    if (coreController.isCurrentlyInferring()) {
                        bitmap.recycle()
                        isCapturing.set(false)
                        return@takeSnapshot
                    }

                    coreController.triggerSnapshot(bitmap, null, continuousMode = true)
                } catch (e: Throwable) {
                    try { bitmap.recycle() } catch (_: Throwable) {}
                    appState = AppState.IDLE
                } finally {
                    isCapturing.set(false)
                }
            },
            onError = { _ ->
                isCapturing.set(false)
                if (appState == AppState.ANALYZING) appState = AppState.IDLE
            }
        )
    }

    /**
     * Toggle continuous auto-snapshot mode on/off.
     * When ON: captures and describes the scene every 4 seconds.
     * When OFF: returns to manual tap/voice triggers only.
     */
    fun toggleContinuousMode() {
        isContinuousMode = !isContinuousMode
        if (isContinuousMode) {
            continuousModeStartTime = System.currentTimeMillis()
            Log.d(TAG, "Continuous mode ON — auto-snapshot every ${AUTO_SNAPSHOT_INTERVAL_MS}ms (throttle after ${CONTINUOUS_MODE_THROTTLE_AFTER_MS / 1000}s)")
            updateStatus("Continuous mode ON")
            mainHandler.postDelayed(autoSnapshotRunnable, AUTO_SNAPSHOT_INTERVAL_MS)
        } else {
            Log.d(TAG, "Continuous mode OFF")
            mainHandler.removeCallbacks(autoSnapshotRunnable)
            updateStatus("Continuous mode OFF")
        }
    }

    private fun startContinuousLoop() {
        if (isContinuousMode && !mainHandler.hasCallbacks(autoSnapshotRunnable)) {
            mainHandler.postDelayed(autoSnapshotRunnable, AUTO_SNAPSHOT_INTERVAL_MS)
        }
    }

    private fun stopContinuousLoop() {
        mainHandler.removeCallbacks(autoSnapshotRunnable)
    }

    private fun startVoiceListening() {
        if (!isAdded) return
        try {
            val activity = requireActivity() as? MainActivity ?: return
            // Declare that the mic is genuinely wanted. MainActivity uses this
            // grant to drop stale recognizer callbacks once the user aborts the
            // session with a tap — otherwise the cancelled session's error would
            // surface as phantom "I did not catch that" speech around answers.
            activity.voiceSessionWanted = true
            activity.startListeningSafely()
        } catch (e: Throwable) {
            Log.w(TAG, "startVoiceListening failed: ${e.message}")
        }
    }

    /**
     * Double-tap "ask" session: barge-in, cue the user, then open the mic
     * for ONE question. The session ends when speech is recognized or the
     * recognizer errors — the mic never stays open at IDLE.
     */
    private fun openVoiceQuery() {
        if (!isAdded) return
        stopVoiceAuditionIfActive()

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTriggerTime < TRIGGER_DEBOUNCE_MS) {
            Log.d(TAG, "Voice query debounced")
            return
        }
        lastTriggerTime = now

        val mainActivity = activity as? MainActivity ?: return

        // Barge-in: cancel any in-flight analysis/response before asking
        coreController.resetForNewCapture()
        endFollowUpWindow()
        hapticManager.vibrateDoubleTap()
        mainActivity.interruptTts()
        mainActivity.resumeAfterNoisePause()

        // Open a fresh conversation session with a spoken cue. Answers to
        // voice queries reopen the mic automatically (maybeOpenFollowUpWindow)
        // so follow-ups flow hands-free until the user goes quiet. Single taps
        // flip this flag back to false — tap answers stay quiet at IDLE.
        keepMicOpenAfterAnswer = true
        startFollowUpWindow(cue = true)
    }

    /**
     * Long-press light check: report ambient light + flashlight status.
     * The torch itself is managed continuously by the auto-torch luminance
     * monitor, so this is a status readout on demand.
     */
    private fun performLightCheck() {
        if (!isAdded) return
        stopVoiceAuditionIfActive()
        hapticManager.vibrateTap()
        val dark = cameraSetup.isEnvironmentDark()
        val torchOn = flashlightManager.isTorchOn()
        Log.d(TAG, "Light check: dark=$dark torchOn=$torchOn")
        try {
            if (dark) {
                // If the auto-torch hasn't flipped yet, apply it now
                if (!torchOn) flashlightManager.toggleTorch(true)
                ttsManager.speakQueued("It is dark. Flashlight is on.")
            } else {
                ttsManager.speakQueued("Light is sufficient. Flashlight is off.")
            }
        } catch (_: Throwable) {}
        updateStatus(if (dark) "Dark — flashlight on" else "Light sufficient")
    }

    // ══════════════════════════════════════════════════════════════════
    // Better Voice Prompt (Tier 1)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Post-onboarding prompt (first run only): if the installed voice pack is
     * the robotic base quality, offer to open Google's voice installer.
     * Asked once per session; marked resolved forever once answered.
     */
    private fun maybePromptBetterVoice() {
        if (!isAdded || voicePromptHandledInSession || !onboardingSpoken) return
        if (awaitingInstallVoiceAnswer) return
        if (appState != AppState.IDLE) return
        voicePromptHandledInSession = true

        try {
            if (!ttsManager.isReady() || !ttsManager.isVoiceQualityLow()) return
            val prefs = requireContext()
                .getSharedPreferences(TTSManager.PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(TTSManager.KEY_VOICE_PROMPT_RESOLVED, false)) return

            Log.d(TAG, "Weak voice detected — offering better voice install")
            awaitingInstallVoiceAnswer = true
            val mainActivity = activity as? MainActivity ?: return
            mainActivity.speakThenCallback(
                "Your current voice sounds basic. A better voice is available for free from Google. " +
                "Say yes to open the voice installer, or say skip."
            ) {
                // Open the mic after the prompt so the user can answer
                mainHandler.postDelayed({
                    startVoiceListening()
                    // Close quietly if they never answer
                    mainHandler.postDelayed({
                        if (awaitingInstallVoiceAnswer) {
                            awaitingInstallVoiceAnswer = false
                            stopVoiceListening()
                        }
                    }, VOICE_INSTALL_WAIT_MS)
                }, VOICE_INSTALL_PROMPT_GAP_MS)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "maybePromptBetterVoice failed: ${e.message}")
            awaitingInstallVoiceAnswer = false
        }
    }

    /** Parse the user's yes/no answer to the better-voice question. */
    private fun handleVoiceInstallAnswer(spokenText: String) {
        awaitingInstallVoiceAnswer = false
        stopVoiceListening()
        val text = spokenText.trim().lowercase()
        val yes = listOf(
            "yes", "yeah", "yep", "yup", "sure", "ok", "okay",
            "install", "better", "ya", "boleh", "好", "是", "要"
        ).any { text.contains(it) }
        val no = listOf(
            "no", "nope", "skip", "not now", "later", "cancel",
            "tak", "tidak", "jangan", "不用", "不要", "跳过"
        ).any { text.contains(it) }

        val prefs = requireContext()
            .getSharedPreferences(TTSManager.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(TTSManager.KEY_VOICE_PROMPT_RESOLVED, true).apply()

        val mainActivity = activity as? MainActivity ?: return
        if (yes && !no) {
            Log.i(TAG, "User chose to install a better voice")
            pendingVoiceInstallConfirmation = true
            if (mainActivity.openTtsVoiceInstaller()) {
                ttsManager.speakQueued("Opening the voice installer. Pick a higher quality voice, then come back.")
            } else {
                pendingVoiceInstallConfirmation = false
                ttsManager.speakQueued("The voice installer is not available on this device. You can manage voices in Android TTS settings.")
            }
        } else {
            Log.i(TAG, "User skipped the better voice prompt")
            ttsManager.speakQueued("No problem. You can change my voice anytime in Voice Settings.")
        }
    }

    /**
     * After returning from the Google voice installer, re-check the voice
     * and confirm whether a better pack was installed.
     */
    private fun confirmVoiceInstallIfReturned() {
        if (!isAdded || !pendingVoiceInstallConfirmation) return
        pendingVoiceInstallConfirmation = false
        mainHandler.postDelayed({
            try {
                ttsManager.applySettings(requireContext())
                if (!ttsManager.isVoiceQualityLow()) {
                    ttsManager.speakQueued("Better voice installed. I will use it from now on.")
                } else {
                    ttsManager.speakQueued("I did not detect a new voice. You can pick one in Voice Settings.")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "confirmVoiceInstallIfReturned failed: ${e.message}")
            }
        }, VOICE_INSTALL_RECHECK_DELAY_MS)
    }

    private fun stopVoiceListening() {
        try {
            val activity = requireActivity() as? MainActivity ?: return
            activity.stopListening()
            // Revoke the mic grant: any recognizer callback still in flight
            // from this session is now stale and must be dropped at the source.
            activity.voiceSessionWanted = false
        } catch (e: Throwable) {
            Log.w(TAG, "stopVoiceListening failed: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Voice Settings Command
    // ══════════════════════════════════════════════════════════════════

    /** Spoken command phrases that start the hands-free voice audition. */
    private fun isVoiceSettingsRequest(text: String): Boolean {
        val t = text.trim().lowercase()
        if (VOICE_SETTINGS_PHRASES.any { t.contains(it) }) return true

        // Fallback 1 — recognizer variants the direct list can miss
        // ("voice setting" without the trailing s, "sound setting",
        // "set my voice"): a voice-word plus an intent-word anywhere.
        val hasVoiceWord = listOf("voice", "suara", "语音", "声音").any { t.contains(it) }
        val hasIntentWord = listOf(
            "setting", "settings", "setup", "set ", "change", "changing",
            "choose", "pick", "select", "option", "preference", "preferences",
            "tetapan", "tukar", "ganti", "ubah", "设置", "设定", "选择", "更换"
        ).any { t.contains(it) }
        if (hasVoiceWord && hasIntentWord) return true

        // Fallback 2 — a very short utterance that is basically the command
        // itself ("voice", "suara"), typically spoken right after the
        // listening cue that just mentioned "voice settings".
        val wordCount = t.split(Regex("\\s+")).size
        return wordCount <= 2 && hasVoiceWord
    }

    // ══════════════════════════════════════════════════════════════════
    // Accessibility Settings Command (disable TalkBack flow)
    // ══════════════════════════════════════════════════════════════════

    /**
     * True when the user asks to open the system Accessibility Settings
     * ("open accessibility settings", "turn off talkback", …). Vyze can't
     * pause TalkBack itself, so this is the guided path to disable it.
     */
    private fun isAccessibilitySettingsRequest(text: String): Boolean {
        val t = text.trim().lowercase()
        if (ACCESSIBILITY_SETTINGS_PHRASES.any { t.contains(it) }) return true

        // Fallback — a talkback-word plus an intent-word anywhere
        // ("talkback off", "turn talkback", "screen reader settings").
        val hasTalkBackWord = listOf(
            "talkback", "talk back", "screen reader", "screenreader",
            "kebolehaksesan", "aksesibiliti", "辅助", "无障碍"
        ).any { t.contains(it) }
        val hasIntentWord = listOf(
            "off", "disable", "turn", "stop", "close", "setting", "settings",
            "tutup", "matikan", "buka", "设置", "关闭"
        ).any { t.contains(it) }
        if (hasTalkBackWord && hasIntentWord) return true

        // Very short utterance that is basically the command itself
        // ("talkback"), spoken right after the listening cue.
        val wordCount = t.split(Regex("\\s+")).size
        return wordCount <= 2 && hasTalkBackWord
    }

    /**
     * Voice command handler: open the system Accessibility Settings screen
     * so the user can turn TalkBack off (TalkBack still works there, so they
     * can navigate it). On return, [confirmAccessibilityReturn] re-checks.
     */
    private fun openAccessibilitySettingsFlow() {
        if (!isAdded) return
        Log.i(TAG, "Voice command: opening Accessibility Settings")
        stopVoiceAuditionIfActive()
        stopVoiceListening()
        endFollowUpWindow()
        // Supersede the pending "install a better voice?" question if any.
        awaitingInstallVoiceAnswer = false
        coreController.resetForNewCapture()
        hapticManager.vibrateTap()

        val mainActivity = activity as? MainActivity ?: return
        pendingAccessibilityReturn = true
        if (mainActivity.openAccessibilitySettings()) {
            ttsManager.speakQueued(
                "Opening accessibility settings. Turn TalkBack off, then come back."
            )
        } else {
            pendingAccessibilityReturn = false
            ttsManager.speakQueued(
                "Accessibility settings are not available on this device. " +
                "Hold both volume keys for three seconds to turn TalkBack off."
            )
        }
    }

    /**
     * After returning from Accessibility Settings, re-check TalkBack and
     * confirm the outcome (or gently remind if still enabled).
     */
    private fun confirmAccessibilityReturn() {
        if (!isAdded || !pendingAccessibilityReturn) return
        pendingAccessibilityReturn = false
        mainHandler.postDelayed({
            try {
                val mainActivity = activity as? MainActivity ?: return@postDelayed
                if (!mainActivity.isTalkBackEnabled()) {
                    mainActivity.clearTalkBackDetected()
                    ttsManager.speakQueued("TalkBack is off. Enjoy hands-free use.")
                } else {
                    ttsManager.speakQueued(
                        "TalkBack is still on. Hold both volume keys for three seconds " +
                        "to turn it off, or say open accessibility settings to try again."
                    )
                }
            } catch (e: Throwable) {
                Log.w(TAG, "confirmAccessibilityReturn failed: ${e.message}")
            }
        }, ACCESSIBILITY_RETURN_RECHECK_DELAY_MS)
    }

    // ══════════════════════════════════════════════════════════════════
    // Hands-free Voice Audition (choose my voice by speaking)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Voice-driven voice picker. No screen, no tapping: the app cycles
     * through the installed voices for the current language, speaking a
     * sample in each one, and the user replies with voice commands.
     *
     * Say "next" → hear the next voice | "use this" → keep it | "cancel" → stop.
     */
    private fun startVoiceAudition() {
        if (!isAdded) return
        Log.i(TAG, "Voice command: starting hands-free voice audition")
        stopVoiceAuditionIfActive()
        stopVoiceListening()
        endFollowUpWindow()
        // If the "install a better voice?" question was still awaiting an
        // answer, this explicit request supersedes it (without resolving it).
        awaitingInstallVoiceAnswer = false
        coreController.resetForNewCapture()
        hapticManager.vibrateTap()

        voiceAuditionVoices.clear()
        voiceAuditionVoices.addAll(ttsManager.getInstalledVoicesForCurrentLanguage())
        voiceAuditionIndex = 0 // 0 = automatic (best available)
        voiceAuditionActive = true
        appState = AppState.LISTENING
        updateStatus("Voice selection — say next / use this / cancel")
        // The user has now used Voice Settings — the double-tap listening cue
        // no longer needs to teach the command on every session.
        markVoiceSettingsLearned()

        val mainActivity = activity as? MainActivity
        if (mainActivity == null) {
            voiceAuditionActive = false
            return
        }
        val total = voiceAuditionVoices.size + 1
        ttsManager.setVoiceByName(TTSManager.VOICE_AUTO)
        mainActivity.speakThenCallback(
            "Voice selection. ${total} choices, including automatic. " +
            "I will speak a sample in each voice. Say next for the next voice, " +
            "use this to keep the voice you just heard, or cancel to stop."
        ) {
            mainHandler.postDelayed({ if (voiceAuditionActive) auditionSpeakCurrent() }, 300L)
        }
    }

    /** Speak the current candidate's sample (in its own voice), then listen. */
    private fun auditionSpeakCurrent() {
        if (!isAdded || !voiceAuditionActive) return
        val mainActivity = activity as? MainActivity ?: return
        val total = voiceAuditionVoices.size + 1

        // Apply the candidate so the sample plays in THAT voice
        if (voiceAuditionIndex == 0) {
            ttsManager.setVoiceByName(TTSManager.VOICE_AUTO)
        } else {
            voiceAuditionVoices.getOrNull(voiceAuditionIndex - 1)?.name
                ?.let { ttsManager.setVoiceByName(it) }
        }

        val voiceLabel = if (voiceAuditionIndex == 0) {
            "Automatic voice, best available quality"
        } else {
            "Voice ${voiceAuditionIndex} of $total"
        }
        appState = AppState.LISTENING
        updateStatus(if (voiceAuditionIndex == 0) "Audition: Automatic" else "Audition: voice $voiceAuditionIndex of $total")
        mainActivity.speakThenCallback(
            "${auditionSampleText()} $voiceLabel. Say next, use this, or cancel."
        ) {
            mainHandler.postDelayed({ startVoiceListening() }, VOICE_SESSION_OPEN_DELAY_MS)
        }
    }

    /** Parse the user's spoken command during the audition. */
    private fun handleVoiceAuditionCommand(text: String) {
        if (!voiceAuditionActive) return
        val t = text.trim().lowercase()
        val total = voiceAuditionVoices.size + 1

        when {
            containsAny(t, AUDITION_USE_PHRASES) -> {
                // Save the currently auditioned voice (or automatic)
                stopVoiceListening()
                val name = if (voiceAuditionIndex == 0) TTSManager.VOICE_AUTO
                else voiceAuditionVoices[voiceAuditionIndex - 1].name
                requireContext().getSharedPreferences(TTSManager.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(TTSManager.KEY_VOICE_NAME, name).apply()
                markVoiceSettingsLearned()
                voiceAuditionActive = false
                appState = AppState.IDLE
                updateStatus("Voice selected")
                try {
                    ttsManager.speakImmediate(
                        if (voiceAuditionIndex == 0) {
                            "Automatic voice selected."
                        } else {
                            "Voice selected. You will hear me in this voice."
                        }
                    )
                } catch (_: Throwable) {}
            }
            containsAny(t, AUDITION_NEXT_PHRASES) -> {
                voiceAuditionIndex = ((voiceAuditionIndex + 1) % total + total) % total
                auditionSpeakCurrent()
            }
            containsAny(t, AUDITION_CANCEL_PHRASES) -> {
                stopVoiceListening()
                voiceAuditionActive = false
                appState = AppState.IDLE
                updateStatus("Ready")
                try {
                    ttsManager.speakImmediate("Voice selection cancelled.")
                } catch (_: Throwable) {}
            }
            else -> {
                // Unclear — re-hint inside the audition
                val mainActivity = activity as? MainActivity
                mainActivity?.speakThenCallback("I did not catch that. Say next, use this, or cancel.") {
                    mainHandler.postDelayed({ startVoiceListening() }, VOICE_SESSION_OPEN_DELAY_MS)
                }
            }
        }
    }

    /** End the audition quietly when another action takes over. */
    private fun stopVoiceAuditionIfActive() {
        if (voiceAuditionActive) {
            voiceAuditionActive = false
            stopVoiceListening()
            if (appState == AppState.LISTENING) {
                appState = AppState.IDLE
                updateStatus("Ready")
            }
        }
    }

    /** Short localized sample used during the audition. */
    private fun auditionSampleText(): String = when (ttsManager.getCurrentLanguageKey()) {
        TTSManager.LANGUAGE_MALAY -> "Ini adalah bagaimana saya berbunyi."
        TTSManager.LANGUAGE_CHINESE -> "这是我说话的声音。"
        else -> "This is how I sound."
    }

    private fun containsAny(text: String, phrases: List<String>): Boolean =
        phrases.any { text.contains(it) }

    // ══════════════════════════════════════════════════════════════════
    // Conversation Window (hands-free follow-ups)
    // ══════════════════════════════════════════════════════════════════
    // After a double-tap "ask", the mic reopens for CONVERSATION_WINDOW_MS
    // so follow-up questions flow hands-free without repeating the gesture.
    // Single-tap and continuous-mode answers deliberately do NOT reopen the
    // mic — the user chose touch input, so the mic stays closed at IDLE.
    // Each accepted query resets the deadline; the window closes quietly
    // after the deadline passes with no speech, or when a new action
    // (tap/report) takes over.

    /** True while the hands-free follow-up window is open. */
    @Volatile
    private var inConversationWindow = false

    /** Rolling deadline (elapsedRealtime) — extended on every accepted query. */
    @Volatile
    private var conversationDeadlineMs = 0L

    /**
     * True while the answer being produced came from a VOICE session
     * (double-tap or a hands-free follow-up question). Only voice answers
     * reopen the mic on completion. Tap/continuous answers keep the mic
     * closed at IDLE — an open mic left running after a tap was what let the
     * NEXT tap cancel an active recognizer, which surfaced its cancellation
     * error as phantom "I did not catch that" speech before the real answer.
     */
    @Volatile
    private var keepMicOpenAfterAnswer = false

    /** Periodically checks the deadline and closes the window when it expires. */
    private val conversationWatchdog = object : Runnable {
        override fun run() {
            if (!inConversationWindow || !isAdded) return
            if (android.os.SystemClock.elapsedRealtime() >= conversationDeadlineMs) {
                Log.d(TAG, "Conversation window expired — closing mic")
                endFollowUpWindow()
            } else {
                mainHandler.postDelayed(this, CONVERSATION_WINDOW_TICK_MS)
            }
        }
    }

    /**
     * Open (or extend) the hands-free conversation window and reopen the mic.
     * @param cue true when the user explicitly asked (double-tap) — a spoken
     *            "Listening" prompt is played; follow-up reopens stay silent.
     */
    private fun startFollowUpWindow(cue: Boolean) {
        if (!isAdded) return
        inConversationWindow = true
        conversationDeadlineMs = android.os.SystemClock.elapsedRealtime() + CONVERSATION_WINDOW_MS
        mainHandler.removeCallbacks(conversationWatchdog)
        mainHandler.postDelayed(conversationWatchdog, CONVERSATION_WINDOW_TICK_MS)

        stopVoiceListening() // clear any stale recognition session first
        appState = AppState.LISTENING
        updateStatus("Listening...")

        if (cue) {
            // Spoken cue. The voice-settings teaching hint is one-time: it
            // only appears until the user has used Voice Settings once (the
            // double-tap cue then stays short: "Listening. Ask your question.").
            val mainActivity = activity as? MainActivity
            if (mainActivity != null) {
                val hint = if (hasLearnedVoiceSettings()) "" else
                    ", or say voice settings to change how I sound"
                mainActivity.speakThenCallback(
                    "Listening. Ask your question$hint."
                ) {
                    // Open the mic AFTER the cue finishes so it is never captured.
                    mainHandler.postDelayed({ startVoiceListening() }, VOICE_SESSION_OPEN_DELAY_MS)
                }
                return
            }
        }
        // Follow-up reopens (and cue fallback): open the mic after a short
        // settle so the answer's last words are never captured as the query.
        mainHandler.postDelayed(
            { startVoiceListening() },
            FOLLOW_UP_OPEN_DELAY_MS
        )
    }

    /** Close the conversation window and the mic (stays closed at IDLE). */
    private fun endFollowUpWindow() {
        inConversationWindow = false
        mainHandler.removeCallbacks(conversationWatchdog)
        stopVoiceListening()
        if (appState == AppState.LISTENING) {
            appState = AppState.IDLE
            updateStatus("Ready")
        }
    }

    /**
     * After an answer finishes, reopen the hands-free follow-up mic ONLY for
     * voice-session answers. Tap and continuous answers return to IDLE with
     * the mic closed — the user is on touch input, and an open mic at rest is
     * what made switching between gestures noisy (stray "I did not catch that"
     * from cancelled listening sessions, ambient chat picked up after taps).
     */
    private fun maybeOpenFollowUpWindow() {
        if (!isAdded) return
        if (keepMicOpenAfterAnswer) {
            Log.d(TAG, "Voice answer done — reopening follow-up window")
            startFollowUpWindow(cue = false)
        } else {
            Log.d(TAG, "Tap answer done — staying IDLE, mic closed")
            endFollowUpWindow()
        }
    }

    /**
     * True once the voice-settings hint is no longer needed: the user has
     * opened Voice Settings at least once, OR already answered the one-time
     * "better voice?" prompt (they know the voice can be changed).
     */
    private fun hasLearnedVoiceSettings(): Boolean {
        return try {
            val prefs = requireContext()
                .getSharedPreferences(TTSManager.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getBoolean(TTSManager.KEY_VOICE_SETTINGS_KNOWN, false) ||
                prefs.getBoolean(TTSManager.KEY_VOICE_PROMPT_RESOLVED, false)
        } catch (_: Throwable) { false }
    }

    /** Persist that the user has opened Voice Settings (dismisses the cue hint). */
    private fun markVoiceSettingsLearned() {
        try {
            requireContext().getSharedPreferences(TTSManager.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(TTSManager.KEY_VOICE_SETTINGS_KNOWN, true).apply()
        } catch (_: Throwable) {}
    }

    // ══════════════════════════════════════════════════════════════════
    // Status Bar
    // ══════════════════════════════════════════════════════════════════

    private fun updateStatus(text: String) {
        activity?.runOnUiThread {
            if (isAdded && _fragmentCameraBinding != null) {
                fragmentCameraBinding.statusText.text = text
            }
        }
    }

    companion object {
        /**
         * Grace period (ms) after all pending utterances complete before
         * releasing audio focus. Compensates for Android AudioTrack
         * hardware drain latency after onDone() fires.
         */
        private const val AUDIO_DRAIN_GRACE_MS = 600L

        /** Spoken phrases that open Accessibility Settings (disable TalkBack). */
        private val ACCESSIBILITY_SETTINGS_PHRASES = listOf(
            "open accessibility settings", "accessibility settings", "accessibility setting",
            "talkback settings", "talkback setting", "talk back settings",
            "turn off talkback", "turn talkback off", "disable talkback",
            "stop talkback", "close talkback", "turn off talk back",
            "tetapan kebolehaksesan", "buka tetapan kebolehaksesan",
            "tetapan aksesibiliti", "tutup talkback", "matikan talkback",
            "辅助功能设置", "打开辅助功能设置", "无障碍设置", "关闭talkback",
            "关闭语音播报", "talkback设置"
        )

        /** Spoken phrases that open the voice audition ("double tap, then say…"). */
        private val VOICE_SETTINGS_PHRASES = listOf(
            "voice settings", "voice setting", "voice setup", "change your voice",
            "change my voice", "change voice", "change the voice", "set my voice",
            "set your voice", "voice selection", "choose a voice", "choose voice",
            "pick a voice", "pick my voice", "how you sound", "how i sound",
            "sound settings", "sound setting", "speech settings", "tts settings",
            "text to speech settings", "voice preferences", "tetapan suara",
            "tukar suara", "ubah suara", "语音设置", "设置语音", "更换语音",
            "换语音", "声音设置", "换声音", "选择语音"
        )

        /** Audition: move to the next voice. */
        private val AUDITION_NEXT_PHRASES = listOf(
            "next", "forward", "continue", "another", "other", "seterusnya",
            "下一个", "下一个语音", "下一种"
        )

        /** Audition: keep the voice that was just heard. */
        private val AUDITION_USE_PHRASES = listOf(
            "use this", "this one", "this voice", "keep it", "keep this",
            "select", "choose", "pick this", "yes", "ok", "guna", "pilih", "ini",
            "用这个", "就要这个", "好", "是", "要"
        )

        /** Audition: stop without changing the voice. */
        private val AUDITION_CANCEL_PHRASES = listOf(
            "cancel", "stop", "exit", "quit", "back", "skip", "done", "finish",
            "batal", "tamat", "取消", "停止", "退出", "完成"
        )

        /** Interval (ms) between auto-snapshot captures in continuous mode. */
        private const val AUTO_SNAPSHOT_INTERVAL_MS = 4000L

        /** Safety timeout (ms) — force IDLE if TTS doesn't finish in time. */
        private const val SPEAKING_TIMEOUT_MS = 10_000L

        /**
         * After continuous mode runs for this long, throttle the capture interval
         * to prevent SoC thermal throttling on mid-tier chipsets.
         */
        private const val CONTINUOUS_MODE_THROTTLE_AFTER_MS = 180_000L  // 3 minutes

        /**
         * Throttled capture interval (ms) after CONTINUOUS_MODE_THROTTLE_AFTER_MS.
         * Doubles the interval to reduce sustained GPU load.
         */
        private const val THERMALTHROTTLE_INTERVAL_MS = 8000L

        /** Delay (ms) between the "Listening" cue and opening the mic. */
        private const val VOICE_SESSION_OPEN_DELAY_MS = 700L

        /** Hands-free follow-up window: mic stays open this long after each answer. */
        private const val CONVERSATION_WINDOW_MS = 12_000L

        /** How often the conversation-window watchdog checks the deadline (ms). */
        private const val CONVERSATION_WINDOW_TICK_MS = 2_000L

        /** Delay before a silent follow-up reopen listens again (ms). */
        private const val FOLLOW_UP_RETRY_DELAY_MS = 500L

        /** Delay before the mic opens after an answer ends (ms). */
        private const val FOLLOW_UP_OPEN_DELAY_MS = 600L

        /** Delay between the better-voice prompt and opening the mic (ms). */
        private const val VOICE_INSTALL_PROMPT_GAP_MS = 600L

        /** How long to wait for the user's yes/skip answer (ms). */
        private const val VOICE_INSTALL_WAIT_MS = 12_000L

        /** Delay before re-checking the voice after returning from the installer (ms). */
        private const val VOICE_INSTALL_RECHECK_DELAY_MS = 1_800L

        /** Delay before re-checking TalkBack after returning from Accessibility Settings (ms). */
        private const val ACCESSIBILITY_RETURN_RECHECK_DELAY_MS = 1_800L
    }
}
