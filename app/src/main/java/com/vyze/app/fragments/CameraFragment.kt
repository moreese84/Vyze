/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
import android.widget.AdapterView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import com.vyze.app.*
import com.vyze.app.data.ScanRepository
import com.vyze.app.delegates.CameraSetupDelegate
import com.vyze.app.delegates.GestureRouter
import com.vyze.app.delegates.MlPipelineManager
import com.vyze.app.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import androidx.fragment.app.viewModels
import kotlinx.coroutines.launch

/**
 * Main camera fragment for Vyze accessibility app.
 *
 * ## Architecture (Post P1 Refactoring)
 * This fragment is now a thin lifecycle shell that delegates all heavy
 * lifting to focused, testable components:
 *
 * - **[CameraSetupDelegate]** — CameraX lifecycle, preview binding, torch
 * - **[MlPipelineManager]** — Frame throttle, bitmap sharing, OCR/OD/barcode/face/luminance
 * - **[GestureRouter]** — Gesture-to-action routing (tap/double-tap/long-press/triple-tap/SOS)
 * - **[MainViewModel]** — Shared state with SavedStateHandle persistence
 * - **[TtsViewModel]** — Singleton TTSManager across fragments
 * - **[ScanRepository]** — Room-backed scan history
 */
class CameraFragment : Fragment(), ObjectDetectorHelper.DetectorListener {

    private val TAG = "ObjectDetection"

    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private val viewModel: MainViewModel by activityViewModels()
    private val ttsViewModel: TtsViewModel by activityViewModels()
    private val splashViewModel: SplashViewModel by viewModels()

    // ── Delegates ─────────────────────────────────────────────────────

    private lateinit var cameraSetup: CameraSetupDelegate
    private lateinit var mlPipeline: MlPipelineManager
    private lateinit var gestureRouter: GestureRouter

    // ── Managers ──────────────────────────────────────────────────────

    private lateinit var ttsManager: TTSManager
    private lateinit var hapticManager: HapticManager
    private lateinit var flashlightManager: FlashlightManager
    private lateinit var voiceCommandManager: VoiceCommandManager
    private lateinit var onboardingManager: OnboardingManager
    private lateinit var highContrastOverlay: HighContrastOverlayView
    private lateinit var scanRepository: ScanRepository

    // ── Agent Engine (Gemini Nano / Rule-Based Fallback) ───────────────

    private lateinit var agentEngine: AgentEngine
    private lateinit var contextBuilder: AgentContextBuilder

    // ── P2: Specialized Modes ─────────────────────────────────────────
    private lateinit var readingModeHelper: ReadingModeHelper
    private lateinit var medicineReaderHelper: MedicineReaderHelper
    @Volatile private var isReadingMode = false
    @Volatile private var isMedicineMode = false

    // ── Agent State ──────────────────────────────────────────────────
    @Volatile private var latestOcrText: String = ""
    @Volatile private var agentQueryInProgress = false

    // ── Executors & Handlers ──────────────────────────────────────────

    private lateinit var backgroundExecutor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Cross-Thread State ────────────────────────────────────────────

    @Volatile private var isCameraActive = false

    // ── Diagnostic Overlay ─────────────────────────────────────────────
    private lateinit var diagnosticOverlay: android.widget.TextView

    // ── Extracted Components ───────────────────────────────────────
    private lateinit var announcementCoordinator: AnnouncementCoordinator
    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var diagnosticManager: DiagnosticOverlayManager
    private lateinit var spatialAnnouncer: SpatialAnnouncer

    // ── Auto-Scene Summary ────────────────────────────────────────────

    private val sceneSummaryRunnable = object : Runnable {
        override fun run() {
            if (isCameraActive && isAdded) {
                triggerSceneSummary()
            }
            mainHandler.postDelayed(this, SCENE_SUMMARY_INTERVAL_MS)
        }
    }

    // ── Battery Check Runnable ────────────────────────────────────────

    private val batteryCheckRunnable = object : Runnable {
        override fun run() {
            if (isCameraActive && isAdded) {
                batteryMonitor.checkBatteryLevel()
            }
            mainHandler.postDelayed(this, BatteryMonitor.CHECK_INTERVAL_MS)
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

        // ── Initialize Managers ──────────────────────────────────────
        ttsManager = ttsViewModel.ttsManager
        hapticManager = HapticManager(requireContext().applicationContext)
        flashlightManager = FlashlightManager()
        voiceCommandManager = VoiceCommandManager(requireContext().applicationContext)
        scanRepository = ScanRepository(requireContext().applicationContext)

        // Agent engine + context builder
        agentEngine = AgentEngine(requireContext().applicationContext)
        contextBuilder = AgentContextBuilder()

        // P2: Specialized mode helpers
        readingModeHelper = ReadingModeHelper()
        medicineReaderHelper = MedicineReaderHelper()

        highContrastOverlay = fragmentCameraBinding.highContrastOverlay

        // Extracted components
        spatialAnnouncer = SpatialAnnouncer()
        announcementCoordinator = AnnouncementCoordinator(ttsManager)
        batteryMonitor = BatteryMonitor(
            context = requireContext().applicationContext,
            onLowBattery = { level ->
                activity?.runOnUiThread {
                    if (isAdded) ttsManager.speakImmediate(
                        context?.getString(R.string.battery_low_warning, level)
                            ?: "Battery low: $level percent."
                    )
                }
            },
            onCriticalBattery = { level ->
                activity?.runOnUiThread {
                    if (isAdded) {
                        hapticManager.vibrateWarning()
                        ttsManager.speakImmediate(
                            context?.getString(R.string.battery_critical_warning, level)
                                ?: "Battery critical: $level percent."
                        )
                    }
                }
            },
            onBatteryLevelChanged = { level -> viewModel.updateBatteryLevel(level) }
        )

        // ── Initialize Delegates ─────────────────────────────────────
        cameraSetup = CameraSetupDelegate()

        mlPipeline = MlPipelineManager(requireContext(), backgroundExecutor)
        diagnosticManager = DiagnosticOverlayManager(
            diagnosticView = fragmentCameraBinding.diagnosticOverlay,
            mlPipeline = mlPipeline
        )
        diagnosticManager.update("init: bg exec ready")
        mlPipeline.onDiagnosticUpdate = { msg -> activity?.runOnUiThread { diagnosticManager.update(msg) } }
        mlPipeline.onLuminanceChanged = { isDark, meanLuminance ->
            activity?.runOnUiThread {
                if (isAdded) flashlightManager.autoTorch(isDark)
            }
        }
        mlPipeline.onBarcodeDetected = { announcements ->
            activity?.runOnUiThread {
                if (isAdded) {
                    ttsManager.speak(announcements.first())
                    viewModel.saveBarcodeScan(announcements.first())
                }
            }
        }
        mlPipeline.onFaceDetected = { announcements ->
            activity?.runOnUiThread {
                if (isAdded) ttsManager.speak(announcements.first())
            }
        }
        mlPipeline.onOcrComplete = { recognizedText, finalText ->
            activity?.runOnUiThread {
                if (isAdded) {
                    latestOcrText = finalText
                    highContrastOverlay.setOcrText(finalText)
                    ttsManager.speakImmediate(finalText)
                    viewModel.saveOcrScan(finalText)
                }
            }
        }
        mlPipeline.onOcrFailed = { _ ->
            activity?.runOnUiThread {
                if (isAdded) {
                    highContrastOverlay.clearOcrText()
                    announcementCoordinator.rateLimitSpeak("Text recognition failed.")
                }
            }
        }
        mlPipeline.onModelsReady = {
            activity?.runOnUiThread {
                if (isAdded) {
                    diagnosticManager.update("models: READY")
                    ttsManager.speakImmediate("Vyze ready")
                }
            }
        }

        gestureRouter = GestureRouter(
            context = requireContext(),
            ttsManager = ttsManager,
            hapticManager = hapticManager,
            colorAnalyzer = ColorAnalyzer(),
            scanRepository = scanRepository,
            mainHandler = mainHandler
        )
        gestureRouter.onSingleTapAction = {
            // Cancel any pending scene summary to prevent interleaving
            mainHandler.removeCallbacks(sceneSummaryRunnable)
            // Suppress voice-command TTS so it doesn't override OD announcements
            announcementCoordinator.suppressVoiceAnnouncements()
            if (this::voiceCommandManager.isInitialized && voiceCommandManager.isCurrentlyListening()) {
                voiceCommandManager.stopListening()
            }
            announceDetectedObject()
        }
        gestureRouter.onDoubleTapAction = {
            mainHandler.removeCallbacks(sceneSummaryRunnable)
            announcementCoordinator.suppressVoiceAnnouncements()
            if (this::voiceCommandManager.isInitialized && voiceCommandManager.isCurrentlyListening()) {
                voiceCommandManager.stopListening()
            }
            performOcrOnCurrentFrame()
        }
        gestureRouter.onLongPressAction = {
            mainHandler.removeCallbacks(sceneSummaryRunnable)
            announcementCoordinator.suppressVoiceAnnouncements()
            if (this::voiceCommandManager.isInitialized && voiceCommandManager.isCurrentlyListening()) {
                voiceCommandManager.stopListening()
            }
            triggerSceneSummary()
        }

        // ── Attach Gesture Router ────────────────────────────────────
        gestureRouter.attach(fragmentCameraBinding.cameraContainer)

        // ── Initialize ML Pipeline + Agent on background thread ────────
        backgroundExecutor.execute {
            mlPipeline.initialize(
                context = requireContext(),
                threshold = viewModel.currentThreshold,
                delegate = viewModel.currentDelegate,
                model = viewModel.currentModel,
                maxResults = viewModel.currentMaxResults,
                detectorListener = this
            )

            // Initialize agent engine (checks AICore / Gemini Nano availability)
            val agentReady = kotlinx.coroutines.runBlocking {
                agentEngine.initialize()
            }
            Log.d(TAG, "Agent engine available: $agentReady")

            fragmentCameraBinding.viewFinder.post {
                setUpCamera()
                // Signal that ML is ready → dismisses the splash screen.
                // Must be called AFTER setUpCamera so the user sees the
                // live preview immediately when the splash lifts.
                splashViewModel.markMlReady()
            }
        }

        initBottomSheetControls()
        fragmentCameraBinding.overlay.runningMode = RunningMode.LIVE_STREAM
        fragmentCameraBinding.highContrastOverlay.runningMode = RunningMode.LIVE_STREAM

        // ── Onboarding ───────────────────────────────────────────────
        onboardingManager = OnboardingManager(
            requireContext().applicationContext, ttsManager, hapticManager
        )
        if (onboardingManager.isFirstLaunch()) {
            // Show the onboarding overlay (dimmed camera behind)
            fragmentCameraBinding.onboardingOverlay.visibility = View.VISIBLE

            // Play the consolidated welcome message
            mainHandler.postDelayed({
                if (isAdded) onboardingManager.playWelcomeMessage()
            }, ONBOARDING_DELAY_MS)

            // Tap anywhere on the overlay to dismiss
            fragmentCameraBinding.onboardingOverlay.setOnClickListener {
                onboardingManager.markOnboardingComplete()
                fragmentCameraBinding.onboardingOverlay.visibility = View.GONE
                ttsManager.speakImmediate("Vyze ready.")
            }
        }

        // ── Voice Commands ───────────────────────────────────────────
        setupVoiceCommands()
    }

    override fun onResume() {
        super.onResume()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(CameraFragmentDirections.actionCameraToPermissions())
        }

        backgroundExecutor.execute {
            if (mlPipeline.isOdInitialized() && mlPipeline.objectDetectorHelper.isClosed()) {
                Log.w(TAG, "onResume: detector was closed, reinitializing")
                diagnosticManager.update("reinit: detector closed")
                mlPipeline.objectDetectorHelper.setupObjectDetector()
            }
        }

        if (cameraSetup.cameraProvider != null) {
            Log.d(TAG, "onResume: rebindCamera")
            diagnosticManager.update("rebind: camera")
            cameraSetup.rebindCamera(
                requireContext(), this,
                fragmentCameraBinding.viewFinder,
                backgroundExecutor,
                mlPipeline.createCompositeAnalyzer()
            )
        }

        isCameraActive = true
        mainHandler.postDelayed(sceneSummaryRunnable, SCENE_SUMMARY_INTERVAL_MS)
        mainHandler.postDelayed(batteryCheckRunnable, BatteryMonitor.CHECK_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(sceneSummaryRunnable)
        mainHandler.removeCallbacks(batteryCheckRunnable)
        isCameraActive = false

        if (this::voiceCommandManager.isInitialized && voiceCommandManager.isCurrentlyListening()) {
            voiceCommandManager.stopListening()
        }
        if (this::flashlightManager.isInitialized && flashlightManager.isTorchOn()) {
            flashlightManager.toggleTorch(false)
        }
        if (this::onboardingManager.isInitialized) onboardingManager.cancelTutorial()
        // Hide onboarding overlay if still visible during pause
        if (_fragmentCameraBinding != null) {
            fragmentCameraBinding.onboardingOverlay.visibility = View.GONE
        }

        cameraSetup.releaseCamera()

        if (mlPipeline.isOdInitialized()) {
            viewModel.setModel(mlPipeline.objectDetectorHelper.currentModel)
            viewModel.setDelegate(mlPipeline.objectDetectorHelper.currentDelegate)
            viewModel.setThreshold(mlPipeline.objectDetectorHelper.threshold)
            viewModel.setMaxResults(mlPipeline.objectDetectorHelper.maxResults)
            backgroundExecutor.execute { mlPipeline.objectDetectorHelper.clearObjectDetector() }
        }

        clearOverlays()
        mlPipeline.reset()
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        mainHandler.removeCallbacks(sceneSummaryRunnable)
        mainHandler.removeCallbacks(batteryCheckRunnable)
        if (this::onboardingManager.isInitialized) onboardingManager.cancelTutorial()
        if (this::gestureRouter.isInitialized) gestureRouter.detach()

        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)

        if (this::ttsManager.isInitialized) ttsManager.onDestroy()
        if (this::hapticManager.isInitialized) hapticManager.cancel()
        if (this::voiceCommandManager.isInitialized) voiceCommandManager.destroy()
        if (this::mlPipeline.isInitialized) mlPipeline.shutdown()
        if (this::readingModeHelper.isInitialized) readingModeHelper.exitReadingMode()
        if (this::medicineReaderHelper.isInitialized) medicineReaderHelper.exitMedicineMode()
        if (this::agentEngine.isInitialized) agentEngine.close()

        cameraSetup.destroy()

        clearOverlays()
        mlPipeline.reset()
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
            backgroundExecutor = backgroundExecutor,
            analyzer = mlPipeline.createCompositeAnalyzer(),
            flashlightMgr = flashlightManager
        )
        startVoiceListening()
    }

    private fun startVoiceListening() {
        mainHandler.postDelayed({
            if (isAdded && this::voiceCommandManager.isInitialized) {
                voiceCommandManager.startListening()
            }
        }, VOICE_LISTENING_DELAY_MS)
    }

    // ══════════════════════════════════════════════════════════════════
    // Voice Commands
    // ══════════════════════════════════════════════════════════════════

    private fun setupVoiceCommands() {
        voiceCommandManager.onFreeformQuery = { text ->
            activity?.runOnUiThread {
                if (isAdded) processFreeformAgentQuery(text)
            }
        }
        voiceCommandManager.onListeningStateChanged = { listening ->
            activity?.runOnUiThread {
                if (isAdded) {
                    if (listening) {
                        if (announcementCoordinator.shouldAnnounceListening()) {
                            announcementCoordinator.markListeningAnnounced()
                            ttsManager.speakImmediate(
                                context?.getString(R.string.voice_listening)
                                    ?: "Listening for commands..."
                            )
                        }
                        // Do NOT call ttsManager.stop() — this would kill
                        // in-progress OD/OCR announcements
                    } else {
                        val suppressed = announcementCoordinator.onListeningStopped()
                        if (!suppressed) {
                            ttsManager.speakImmediate(
                                context?.getString(R.string.voice_stopped)
                                    ?: "Listening stopped."
                            )
                        }
                    }
                }
            }
        }
        voiceCommandManager.onError = { error -> Log.w(TAG, "Voice command error: $error") }
    }

    // ══════════════════════════════════════════════════════════════════
    // Actions (called by GestureRouter)
    // ══════════════════════════════════════════════════════════════════

    private fun performOcrOnCurrentFrame() {
        // User-initiated → always speak, bypass continuous-frame debounce
        ttsManager.speakImmediate(context?.getString(R.string.ocr_reading) ?: "Reading text...")
        mlPipeline.requestOcr()
    }

    private fun announceDetectedObject() {
        if (!mlPipeline.isOdInitialized()) {
            ttsManager.speakImmediate(
                context?.getString(R.string.od_no_objects) ?: "No objects detected yet."
            )
            return
        }

        // Read directly from the latest ResultBundle that is currently
        // drawn on screen.  This is the SAME object passed to onResults()
        // and used by the overlay — guaranteed in-sync with what the user sees.
        val resultBundle = mlPipeline.objectDetectorHelper.lastResultBundle
        if (resultBundle == null) {
            ttsManager.speakImmediate(
                context?.getString(R.string.od_no_objects) ?: "No objects detected yet."
            )
            return
        }

        val detections = resultBundle.detections
        if (detections.isEmpty()) {
            ttsManager.speakImmediate(
                context?.getString(R.string.od_no_visible)
                    ?: "No objects currently visible. Try pointing the camera at an object."
            )
            return
        }

        // Try spatial announcements first (includes debounce + proximity)
        val announcements = try {
            spatialAnnouncer.getAnnounceableDetections(
                detections = resultBundle.detections,
                frameWidth = resultBundle.inputImageWidth,
                frameHeight = resultBundle.inputImageHeight
            )
        } catch (e: Exception) {
            Log.e(TAG, "getAnnounceableDetections failed", e)
            emptyList()
        }

        if (announcements.isNotEmpty()) {
            ttsManager.speakImmediate(announcements.first())
            return
        }

        // Fallback: speak the highest-confidence detection directly.
        try {
            val topDetection = detections.maxByOrNull {
                it.categories.firstOrNull()?.score ?: 0f
            }
            val cat = topDetection?.categories?.firstOrNull()
            if (cat != null) {
                ttsManager.speakImmediate(
                    context?.getString(
                        R.string.od_detected, cat.label,
                        (cat.score * 100).toInt()
                    ) ?: "${cat.label}, confidence ${(cat.score * 100)} percent."
                )
            } else {
                ttsManager.speakImmediate(
                    context?.getString(R.string.od_no_detected)
                        ?: "No objects detected."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Detection fallback failed", e)
            ttsManager.speakImmediate(
                context?.getString(R.string.od_no_detected) ?: "No objects detected."
            )
        }
    }

    private fun triggerSceneSummary() {
        if (!mlPipeline.isOdInitialized()) {
            ttsManager.speakImmediate(
                context?.getString(R.string.scene_no_data)
                    ?: "No scene data available yet. The camera is still loading."
            )
            return
        }
        val resultBundle = mlPipeline.objectDetectorHelper.lastResultBundle ?: run {
            ttsManager.speakImmediate(
                context?.getString(R.string.scene_no_data)
                    ?: "No scene data available yet. Try again in a moment."
            )
            return
        }

        val detections = resultBundle.detections
        if (detections.isEmpty()) {
            ttsManager.speakImmediate(
                context?.getString(R.string.scene_no_visible)
                    ?: "No objects currently visible. Try pointing the camera around the room."
            )
            return
        }

        hapticManager.vibrateLongPress()

        // Use agent for scene summary if available
        if (this::agentEngine.isInitialized && agentEngine.isAgentAvailable()) {
            val sceneContext = contextBuilder.buildContextFromResultBundle(resultBundle, latestOcrText)
            kotlinx.coroutines.MainScope().launch {
                val response = agentEngine.processQuery(
                    userQuery = "Describe my surroundings for navigation",
                    mode = AgentEngine.Mode.SCENE_NAVIGATION,
                    sceneContext = sceneContext
                )
                ttsManager.speakImmediate(response)
                viewModel.saveSceneScan(response)
            }
        } else {
            // Fallback: use agent's built-in rule-based scene summarizer
            val sceneContext = contextBuilder.buildContextFromResultBundle(resultBundle, latestOcrText)
            kotlinx.coroutines.MainScope().launch {
                val response = agentEngine.processQuery(
                    userQuery = "Describe my surroundings for navigation",
                    mode = AgentEngine.Mode.SCENE_NAVIGATION,
                    sceneContext = sceneContext
                )
                ttsManager.speakImmediate(response)
                viewModel.saveSceneScan(response)
            }
        }
    }

    private fun announceLightConditions() {
        val meanLuminance = mlPipeline.latestMeanLuminance
        val isDark = mlPipeline.latestIsDark

        if (meanLuminance == 0.0 && !isDark) {
            announcementCoordinator.rateLimitSpeak(context?.getString(R.string.light_not_measured) ?: "Light level not yet measured.")
            return
        }

        val brightness = meanLuminance.toInt()
        if (isDark) {
            hapticManager.vibrateWarning()
            flashlightManager.autoTorch(true)
            ttsManager.speakImmediate(context?.getString(R.string.light_dark, brightness) ?: "Environment is dark.")
        } else {
            flashlightManager.autoTorch(false)
            ttsManager.speakImmediate(context?.getString(R.string.light_sufficient, brightness) ?: "Lighting is sufficient.")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Agent Engine (Freeform Voice Queries)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Routes a freeform voice query to the AgentEngine with the
     * appropriate mode and scene context.
     *
     * Mode auto-detection heuristic:
     * - Contains "where" / "find" / "search" → TARGET_SEARCH
     * - Contains "read" / "sign" / "document" → DOCUMENT_READER
     * - Contains "medicine" / "drug" / "pill" / "dosage" → MEDICINE_READER
     * - Everything else → SCENE_NAVIGATION (general "what's around me")
     */
    private fun processFreeformAgentQuery(text: String) {
        if (agentQueryInProgress) {
            Log.d(TAG, "Agent query already in progress, ignoring: $text")
            return
        }

        val mode = detectAgentMode(text)
        Log.d(TAG, "Freeform query: '$text' → mode=$mode")

        agentQueryInProgress = true
        announcementCoordinator.suppressVoiceAnnouncements()

        // Build scene context from the latest detection snapshot
        val sceneContext = if (this::mlPipeline.isInitialized && mlPipeline.isOdInitialized()) {
            val bundle = mlPipeline.objectDetectorHelper.lastResultBundle
            if (bundle != null) {
                contextBuilder.buildContextFromResultBundle(bundle, latestOcrText)
            } else {
                contextBuilder.buildContext(emptyList(), 640, 480, latestOcrText)
            }
        } else {
            contextBuilder.buildContext(emptyList(), 640, 480, latestOcrText)
        }

        // Run agent query on background thread
        kotlinx.coroutines.MainScope().launch {
            try {
                val response = agentEngine.processQuery(
                    userQuery = text,
                    mode = mode,
                    sceneContext = sceneContext,
                    ocrText = latestOcrText
                )

                activity?.runOnUiThread {
                    if (isAdded) {
                        ttsManager.speakImmediate(response)
                    }
                    agentQueryInProgress = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Agent query failed", e)
                activity?.runOnUiThread {
                    if (isAdded) {
                        ttsManager.speakImmediate("Sorry, I couldn't process that request.")
                    }
                    agentQueryInProgress = false
                }
            }
        }
    }

    /**
     * Auto-detects the agent mode from freeform voice text.
     */
    private fun detectAgentMode(text: String): AgentEngine.Mode {
        val lower = text.lowercase()
        return when {
            SEARCH_KEYWORDS.any { lower.contains(it) } -> AgentEngine.Mode.TARGET_SEARCH
            DOC_KEYWORDS.any { lower.contains(it) } -> AgentEngine.Mode.DOCUMENT_READER
            MEDICINE_KEYWORDS.any { lower.contains(it) } -> AgentEngine.Mode.MEDICINE_READER
            else -> AgentEngine.Mode.SCENE_NAVIGATION
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Bottom Sheet Controls
    // ══════════════════════════════════════════════════════════════════

    private fun initBottomSheetControls() {
        fragmentCameraBinding.bottomSheetLayout.maxResultsValue.text = viewModel.currentMaxResults.toString()
        fragmentCameraBinding.bottomSheetLayout.thresholdValue.text = String.format("%.2f", viewModel.currentThreshold)

        fragmentCameraBinding.bottomSheetLayout.thresholdMinus.setOnClickListener {
            if (mlPipeline.objectDetectorHelper.threshold >= 0.1) {
                mlPipeline.objectDetectorHelper.threshold -= 0.1f; updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.thresholdPlus.setOnClickListener {
            if (mlPipeline.objectDetectorHelper.threshold <= 0.8) {
                mlPipeline.objectDetectorHelper.threshold += 0.1f; updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.maxResultsMinus.setOnClickListener {
            if (mlPipeline.objectDetectorHelper.maxResults > 1) {
                mlPipeline.objectDetectorHelper.maxResults--; updateControlsUi()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.maxResultsPlus.setOnClickListener {
            if (mlPipeline.objectDetectorHelper.maxResults < 5) {
                mlPipeline.objectDetectorHelper.maxResults++; updateControlsUi()
            }
        }

        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(viewModel.currentDelegate, false)
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                    try {
                        mlPipeline.objectDetectorHelper.currentDelegate = p2; updateControlsUi()
                    } catch (e: UninitializedPropertyAccessException) {
                        Log.e(TAG, "OD not initialized.")
                    }
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }

        fragmentCameraBinding.bottomSheetLayout.spinnerModel.setSelection(viewModel.currentModel, false)
        fragmentCameraBinding.bottomSheetLayout.spinnerModel.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
                    try {
                        mlPipeline.objectDetectorHelper.currentModel = p2; updateControlsUi()
                    } catch (e: UninitializedPropertyAccessException) {
                        Log.e(TAG, "OD not initialized.")
                    }
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
    }

    private fun updateControlsUi() {
        fragmentCameraBinding.bottomSheetLayout.maxResultsValue.text = mlPipeline.objectDetectorHelper.maxResults.toString()
        fragmentCameraBinding.bottomSheetLayout.thresholdValue.text = String.format("%.2f", mlPipeline.objectDetectorHelper.threshold)
        backgroundExecutor.execute { mlPipeline.reinitializeOd() }
        mlPipeline.resetCooldowns()
        spatialAnnouncer.clearDebounceState()
        clearOverlays()
    }

    private fun clearOverlays() {
        if (_fragmentCameraBinding != null) {
            try { fragmentCameraBinding.overlay.clear() } catch (_: Exception) {}
            try { fragmentCameraBinding.highContrastOverlay.clear() } catch (_: Exception) {}
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // OD Results Callback
    // ══════════════════════════════════════════════════════════════════

    override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
        val detectionCount = resultBundle.detections.size
        Log.d(TAG, "onResults: $detectionCount detections, inference=${resultBundle.inferenceTime}ms")
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {
                diagnosticManager.update("OD: $detectionCount det, ${resultBundle.inferenceTime}ms")
                fragmentCameraBinding.bottomSheetLayout.inferenceTimeVal.text = String.format("%d ms", resultBundle.inferenceTime)
                if (isAdded) {
                    fragmentCameraBinding.overlay.setResults(
                        resultBundle.detections,
                        resultBundle.inputImageHeight,
                        resultBundle.inputImageWidth,
                        resultBundle.letterboxPadX,
                        resultBundle.letterboxPadY
                    )
                    fragmentCameraBinding.highContrastOverlay.setResults(
                        resultBundle.detections,
                        resultBundle.inputImageHeight,
                        resultBundle.inputImageWidth,
                        resultBundle.letterboxPadX,
                        resultBundle.letterboxPadY
                    )
                }
                fragmentCameraBinding.overlay.invalidate()
                fragmentCameraBinding.highContrastOverlay.invalidate()
            }
        }
    }

    override fun onError(error: String, errorCode: Int) {
        Log.e(TAG, "OD onError: $error (code=$errorCode)")
        activity?.runOnUiThread {
            diagnosticManager.update("OD ERROR: $error")
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            if (errorCode == ObjectDetectorHelper.GPU_ERROR) {
                fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(ObjectDetectorHelper.DELEGATE_CPU, false)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // P2: Specialized Modes
    // ══════════════════════════════════════════════════════════════════

    /**
     * Toggles reading mode on/off.
     * In reading mode, the camera pipeline uses ReadingModeHelper for
     * continuous line tracking and page detection.
     */
    fun toggleReadingMode() {
        if (isReadingMode) {
            isReadingMode = false
            readingModeHelper.exitReadingMode()
            ttsManager.speakImmediate(context?.getString(R.string.reading_mode_deactivated) ?: "Reading mode off.")
        } else {
            // Disable medicine mode if active
            if (isMedicineMode) toggleMedicineMode()
            isReadingMode = true
            readingModeHelper.enterReadingMode()
            ttsManager.speakImmediate(context?.getString(R.string.reading_mode_activated) ?: "Reading mode on.")
        }
    }

    /**
     * Toggles medicine reader mode on/off.
     * In medicine mode, the camera pipeline uses MedicineReaderHelper for
     * prescription label parsing with dosage warnings.
     */
    fun toggleMedicineMode() {
        if (isMedicineMode) {
            isMedicineMode = false
            medicineReaderHelper.exitMedicineMode()
            ttsManager.speakImmediate(context?.getString(R.string.medicine_mode_deactivated) ?: "Medicine mode off.")
        } else {
            // Disable reading mode if active
            if (isReadingMode) toggleReadingMode()
            isMedicineMode = true
            medicineReaderHelper.enterMedicineMode()
            ttsManager.speakImmediate(context?.getString(R.string.medicine_mode_activated) ?: "Medicine mode on.")
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Companion Constants
    // ══════════════════════════════════════════════════════════════════

    companion object {
        private const val VOICE_LISTENING_DELAY_MS = 2000L
        const val SCENE_SUMMARY_INTERVAL_MS = 6000L
        const val ONBOARDING_DELAY_MS = 3000L

        // ── Agent Mode Detection Keywords ────────────────────────────
        private val SEARCH_KEYWORDS = listOf("where", "find", "search", "locate", "looking for")
        private val DOC_KEYWORDS = listOf("read this", "document", "sign", "letter", "note")
        private val MEDICINE_KEYWORDS = listOf("medicine", "drug", "pill", "dosage", "medication", "prescription")
    }
}
