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

    private lateinit var cameraSetup: CameraSetupDelegate
    private lateinit var gestureRouter: GestureRouter

    private lateinit var coreController: VyzeCoreController
    private lateinit var memoryDao: MemoryDao

    private lateinit var ttsManager: TTSManager
    private lateinit var hapticManager: HapticManager
    private var systemVibrator: Vibrator? = null
    private lateinit var flashlightManager: FlashlightManager

    private lateinit var backgroundExecutor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())

    private enum class AppState { LOADING, IDLE, LISTENING, ANALYZING, SPEAKING }

    @Volatile
    private var appState = AppState.LOADING

    @Volatile
    private var isCameraActive = false

    @Volatile
    private var onboardingSpoken = false

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

    private val autoSnapshotRunnable = object : Runnable {
        override fun run() {
            if (!isContinuousMode || !isAdded) return

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
                mainHandler.postDelayed(this, AUTO_SNAPSHOT_INTERVAL_MS)
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
                                // In continuous mode, the auto-loop handles the next capture
                                if (!isContinuousMode) {
                                    mainHandler.postDelayed({ startVoiceListening() }, 300L)
                                }
                            } else if (coreController.isStreamingActive()) {
                                Log.d(TAG, "Streaming active — waiting for TTS queue to drain")
                                waitForTtsDrain {
                                    appState = AppState.IDLE
                                    Log.d(TAG, "TTS drain complete — returning to IDLE")
                                    if (!isContinuousMode) {
                                        mainHandler.postDelayed({ startVoiceListening() }, 300L)
                                    }
                                }
                            } else {
                                mainActivity.speakThenCallback(response) {
                                    appState = AppState.IDLE
                                    Log.d(TAG, "Response spoken — returning to IDLE")
                                    if (!isContinuousMode) {
                                        mainHandler.postDelayed({ startVoiceListening() }, 300L)
                                    }
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
                    mainHandler.postDelayed({ startVoiceListening() }, 1000L)
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

        gestureRouter.onSingleTapAction = { x, y ->
            bargeInAndCapture("User tapped at position (${x.toInt()}, ${y.toInt()})")
        }

        gestureRouter.onDoubleTapAction = {
            bargeInAndCapture("Describe what is in front of me and around me for navigation.")
        }

        gestureRouter.onLongPressAction = {
            bargeInAndCapture("Give me a detailed description of my surroundings including obstacles, furniture, and navigation paths.")
        }

        gestureRouter.attach(fragmentCameraBinding.cameraContainer)

        backgroundExecutor.execute {
            fragmentCameraBinding.viewFinder.post {
                setUpCamera()
            }
        }

        fragmentCameraBinding.viewFinder.setOnClickListener {
            Log.d(TAG, "Touch fallback — barge-in + manual capture")
            bargeInAndCapture("User tapped to capture scene.")
        }
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
            appState = AppState.IDLE
            startVoiceListening()
            startContinuousLoop()
        } else {
            appState = AppState.LOADING
        }
    }

    override fun onPause() {
        super.onPause()
        isCameraActive = false
        stopContinuousLoop()
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

    private fun bargeInAndCapture(query: String) {
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

        val mainActivity = activity as? MainActivity
        mainActivity?.stopListening()
        mainActivity?.interruptTtsWithMicPause()

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
                                mainHandler.postDelayed({ startVoiceListening() }, 500L)
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
                            mainHandler.postDelayed({ startVoiceListening() }, 1000L)
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
                        ttsManager.speakImmediate("Camera frame unavailable. Please try again.")
                        mainHandler.postDelayed({ startVoiceListening() }, 1500L)
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
                activity.interruptTts()
                // ── FULL PIPELINE RESET for speech-triggered captures ──
                // Reset state + increment session before triggering.
                // Must happen synchronously before triggerVlmSnapshot.
                coreController.resetForNewCapture()
                // Lock TTS + prompt language to detected user language
                coreController.setUserLocale(detectedLocale)
                appState = AppState.IDLE
                updateStatus("Heard: \"$spokenText\"")
                triggerVlmSnapshot(spokenText)
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
                appState = AppState.IDLE
                updateStatus("Ready")
                mainHandler.postDelayed({ startVoiceListening() }, 300L)
            }
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
                    mainHandler.postDelayed({
                        ttsManager.abandonFocus()
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
            Log.d(TAG, "Continuous mode ON — auto-snapshot every ${AUTO_SNAPSHOT_INTERVAL_MS}ms")
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

    companion object {
        /**
         * Grace period (ms) after all pending utterances complete before
         * releasing audio focus. Compensates for Android AudioTrack
         * hardware drain latency after onDone() fires.
         */
        private const val AUDIO_DRAIN_GRACE_MS = 400L

        /** Interval (ms) between auto-snapshot captures in continuous mode. */
        private const val AUTO_SNAPSHOT_INTERVAL_MS = 4000L
    }
}
