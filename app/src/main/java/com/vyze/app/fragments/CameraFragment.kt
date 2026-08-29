package com.vyze.app.fragments

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * - Touch anywhere to interrupt TTS and trigger a new capture
 *
 * ## State Machine
 * IDLE → listening → (speech result) → analyzing → (VLM result) → speaking → IDLE
 */
class CameraFragment : Fragment() {

    private val TAG = "CameraFragment"

    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private val viewModel: MainViewModel by activityViewModels()
    private val ttsViewModel: TtsViewModel by activityViewModels()
    private val splashViewModel: SplashViewModel by viewModels()

    // ── Delegates ─────────────────────────────────────────────────────

    private lateinit var cameraSetup: CameraSetupDelegate
    private lateinit var gestureRouter: GestureRouter

    // ── VLM Pipeline ─────────────────────────────────────────────────

    private lateinit var coreController: VyzeCoreController
    private lateinit var memoryDao: MemoryDao

    // ── Managers ──────────────────────────────────────────────────────

    private lateinit var ttsManager: TTSManager
    private lateinit var hapticManager: HapticManager
    private lateinit var flashlightManager: FlashlightManager

    // ── Executors & Handlers ──────────────────────────────────────────

    private lateinit var backgroundExecutor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── State Machine ────────────────────────────────────────────────

    /** Explicit states to prevent race conditions on rapid taps. */
    private enum class AppState { LOADING, IDLE, LISTENING, ANALYZING, SPEAKING }

    @Volatile
    private var appState = AppState.LOADING

    @Volatile
    private var isCameraActive = false

    /** Track whether onboarding has been spoken (one-time). */
    @Volatile
    private var onboardingSpoken = false

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

        // ── Initialize Managers ──────────────────────────────────────
        ttsManager = ttsViewModel.ttsManager
        hapticManager = HapticManager(requireContext().applicationContext)
        flashlightManager = FlashlightManager()

        // ── Initialize Memory + VLM Pipeline ─────────────────────────
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
                            // Debounce: skip if this is a duplicate of the last described object
                            if (coreController.isDuplicateDescription(response)) {
                                Log.d(TAG, "Duplicate description — skipping TTS, returning to IDLE")
                                appState = AppState.IDLE
                                mainHandler.postDelayed({ startVoiceListening() }, 300L)
                            } else {
                                // Speak result once, then auto-restart mic for next query
                                mainActivity.speakThenCallback(response) {
                                    // TTS finished — go to IDLE and reopen mic
                                    appState = AppState.IDLE
                                    Log.d(TAG, "Response spoken — returning to IDLE, restarting mic")
                                    mainHandler.postDelayed({ startVoiceListening() }, 300L)
                                }
                            }
                        } else {
                            appState = AppState.IDLE
                        }
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
                    appState = AppState.IDLE
                    updateStatus("Error: $error")
                    (activity as? MainActivity)?.announceStatus("Error: $error")
                    mainHandler.postDelayed({ startVoiceListening() }, 1000L)
                }
            }
        }

        // ── Deferred Onboarding: fire when VLM engine is ready ───────
        // Listen for engine ready state — speak onboarding once, then start mic
        coreController.onStatusUpdate = { msg ->
            activity?.runOnUiThread {
                if (isAdded && _fragmentCameraBinding != null) {
                    updateStatus(msg)
                    if (msg.startsWith("VLM ready") && !onboardingSpoken) {
                        onboardingSpoken = true
                        // Use speakThenCallback to auto-restart mic after onboarding finishes
                        val mainActivity = activity as? MainActivity
                        if (mainActivity != null && mainActivity.isTtsReady()) {
                            appState = AppState.IDLE
                            mainActivity.speakThenCallback(
                                "Vyze model ready. Tap anywhere or speak to ask a question, " +
                                "such as what is in front of me. Tap again to interrupt or ask a new question."
                            ) {
                                // TTS onboarding finished — start listening
                                Log.d(TAG, "Onboarding spoken — starting voice listening")
                                mainHandler.postDelayed({ startVoiceListening() }, 300L)
                            }
                        } else {
                            // TTS not ready yet — just start listening
                            startVoiceListening()
                        }
                    }
                }
            }
        }

        // Show engine status
        try {
            val backend = coreController.getEngineBackend()
            if (coreController.isEngineReady()) {
                updateStatus("Ready [$backend]")
                // VLM already ready — speak onboarding now, then start mic
                if (!onboardingSpoken) {
                    onboardingSpoken = true
                    val mainActivity = activity as? MainActivity
                    if (mainActivity != null && mainActivity.isTtsReady()) {
                        appState = AppState.IDLE
                        mainActivity.speakThenCallback(
                            "Vyze model ready. Tap anywhere or speak to ask a question, " +
                            "such as what is in front of me. Tap again to interrupt or ask a new question."
                        ) {
                            Log.d(TAG, "Onboarding spoken — starting voice listening")
                            mainHandler.postDelayed({ startVoiceListening() }, 300L)
                        }
                    } else {
                        startVoiceListening()
                    }
                }
            } else {
                updateStatus("Model still loading...")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Engine status UI error: ${e.message}")
        }

        // ── Wire Speech Recognition Callbacks ────────────────────────
        wireSpeechCallbacks()

        // ── Initialize Camera ────────────────────────────────────────
        cameraSetup = CameraSetupDelegate()
        cameraSetup.setContext(requireContext().applicationContext)

        // ── Initialize Gesture Router ────────────────────────────────
        gestureRouter = GestureRouter(
            context = requireContext(),
            ttsManager = ttsManager,
            hapticManager = hapticManager,
            colorAnalyzer = ColorAnalyzer(),
            scanRepository = ScanRepository(requireContext().applicationContext),
            mainHandler = mainHandler
        )

        // Single tap → barge-in (with mic pause) + force manual capture
        gestureRouter.onSingleTapAction = { x, y ->
            bargeInAndCapture("User tapped at position (${x.toInt()}, ${y.toInt()})")
        }

        // Double tap → barge-in + describe surroundings
        gestureRouter.onDoubleTapAction = {
            bargeInAndCapture("Describe what is in front of me and around me for navigation.")
        }

        // Long press → barge-in + detailed scene description
        gestureRouter.onLongPressAction = {
            bargeInAndCapture("Give me a detailed description of my surroundings including obstacles, furniture, and navigation paths.")
        }

        gestureRouter.attach(fragmentCameraBinding.cameraContainer)

        // ── Initialize Camera on Background Thread ───────────────────
        backgroundExecutor.execute {
            fragmentCameraBinding.viewFinder.post {
                setUpCamera()
            }
        }

        // ── Touch fallback — barge-in (with mic pause) + manual capture
        fragmentCameraBinding.viewFinder.setOnClickListener {
            Log.d(TAG, "Touch fallback — barge-in + manual capture")
            bargeInAndCapture("User tapped to capture scene.")
        }
    }

    override fun onResume() {
        super.onResume()

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

        // If VLM is already ready (e.g. returning from another activity),
        // go straight to IDLE and start listening. Otherwise stay LOADING
        // until onStatusUpdate("VLM ready") fires.
        if (coreController.isEngineReady() && onboardingSpoken) {
            appState = AppState.IDLE
            startVoiceListening()
        } else {
            appState = AppState.LOADING
        }
    }

    override fun onPause() {
        super.onPause()
        isCameraActive = false
        stopVoiceListening()
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

    /**
     * Unified barge-in handler: cancels any active TTS AND in-flight VLM
     * inference if we're in ANALYZING state, then triggers a fresh capture.
     * This prevents race conditions on rapid taps.
     */
    private fun bargeInAndCapture(query: String) {
        // 0. Cancel SpeechRecognizer to avoid hardware conflict with ImageCapture
        val mainActivity = activity as? MainActivity
        mainActivity?.stopListening()

        // 1. Fully reset the pipeline: cancel inference + clear debounce cache
        //    This prevents stale results from being processed after barge-in.
        if (appState == AppState.ANALYZING || appState == AppState.SPEAKING) {
            Log.d(TAG, "Barge-in during $appState — resetting pipeline")
            coreController.cancelAndReset()
            appState = AppState.IDLE
        }

        // 2. Stop TTS and pause mic to avoid tap sound capture
        mainActivity?.interruptTtsWithMicPause()

        // 3. Trigger fresh capture
        triggerVlmSnapshot(query)
    }

    // ══════════════════════════════════════════════════════════════════
    // VLM Snapshot Trigger
    // ══════════════════════════════════════════════════════════════════

    private fun triggerVlmSnapshot(query: String) {
        CrashLogFile.log(TAG, "triggerVlmSnapshot: state=$appState, engineReady=${coreController.isEngineReady()}, inferring=${coreController.isCurrentlyInferring()}")

        // Drop frames while TTS is actively speaking — prevents stale frame backlog
        if (ttsManager.isSpeaking()) {
            Log.d(TAG, "TTS speaking — dropping snapshot trigger")
            updateStatus("Speaking...")
            return
        }

        // State machine guard: reject if not IDLE or LISTENING
        if (appState != AppState.IDLE && appState != AppState.LISTENING) {
            Log.d(TAG, "State=$appState — rejecting snapshot trigger")
            updateStatus("Busy...")
            return
        }

        if (!coreController.isEngineReady()) {
            updateStatus("Model still loading...")
            Log.d(TAG, "VLM not ready yet")
            return
        }

        if (coreController.isCurrentlyInferring()) {
            updateStatus("Already analyzing...")
            Log.d(TAG, "Engine busy — ignoring")
            return
        }

        hapticManager.vibrateTap()
        updateStatus("Capturing...")
        CrashLogFile.log(TAG, "Calling cameraSetup.takeSnapshot()")

        cameraSetup.takeSnapshot(
            onBitmap = { bitmap ->
                try {
                    CrashLogFile.log(TAG, "onBitmap callback: ${bitmap.width}x${bitmap.height}")

                    if (coreController.isCurrentlyInferring()) {
                        CrashLogFile.log(TAG, "Engine busy — recycling bitmap")
                        bitmap.recycle()
                        activity?.runOnUiThread {
                            if (isAdded && _fragmentCameraBinding != null) {
                                updateStatus("Already analyzing...")
                            }
                        }
                        return@takeSnapshot
                    }

                    activity?.runOnUiThread {
                        if (isAdded && _fragmentCameraBinding != null) {
                            appState = AppState.ANALYZING
                            updateStatus("Analyzing...")
                            (activity as? MainActivity)?.announceStatus("Analyzing scene...")
                        }
                    }
                    CrashLogFile.log(TAG, "Calling coreController.triggerSnapshot()")
                    coreController.triggerSnapshot(bitmap, query)
                } catch (e: Throwable) {
                    CrashLogFile.logError(TAG, "onBitmap callback error: ${e.javaClass.simpleName}: ${e.message}", e)
                    try { bitmap.recycle() } catch (_: Throwable) {}
                }
            },
            onError = { error ->
                CrashLogFile.logError(TAG, "Snapshot failed: $error")
                Log.e(TAG, "Snapshot failed: $error")
                activity?.runOnUiThread {
                    if (isAdded && _fragmentCameraBinding != null) {
                        appState = AppState.IDLE
                        ttsManager.speakImmediate("Failed to capture image. $error")
                        updateStatus("Capture failed")
                        // Auto-restart mic after error
                        mainHandler.postDelayed({ startVoiceListening() }, 1000L)
                    }
                }
            }
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // Speech Recognition Integration
    // ══════════════════════════════════════════════════════════════════

    /**
     * Wire speech recognition callbacks from MainActivity to this fragment.
     */
    private fun wireSpeechCallbacks() {
        val activity = requireActivity() as? MainActivity ?: return

        // Final speech result → barge-in + trigger VLM
        activity.onSpeechResult = { spokenText ->
            if (spokenText.isNotBlank()) {
                Log.i(TAG, "Speech result: \"$spokenText\"")
                // Barge-in: silence TTS (mic is already open, no tap sound risk)
                activity.interruptTts()
                appState = AppState.IDLE  // reset from LISTENING
                updateStatus("Heard: \"$spokenText\"")
                triggerVlmSnapshot(spokenText)
            }
        }

        // Partial results → show live transcription in status bar
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

        // Speech errors → reset to IDLE and restart mic silently
        activity.onSpeechError = { errorMsg ->
            if (isAdded && _fragmentCameraBinding != null) {
                Log.w(TAG, "Speech error: $errorMsg")
                appState = AppState.IDLE
                updateStatus("Ready")
                mainHandler.postDelayed({ startVoiceListening() }, 300L)
            }
        }
    }

    private fun startVoiceListening() {
        if (!isAdded) return
        try {
            val activity = requireActivity() as? MainActivity ?: return
            activity.startListeningSafely()
        } catch (e: Throwable) {
            Log.w(TAG, "startVoiceListening failed: ${e.message}")
        }
    }

    private fun stopVoiceListening() {
        try {
            val activity = requireActivity() as? MainActivity ?: return
            activity.stopListening()
        } catch (e: Throwable) {
            Log.w(TAG, "stopVoiceListening failed: ${e.message}")
        }
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
}
