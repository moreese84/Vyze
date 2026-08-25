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
import android.os.BatteryManager
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

    // ── Delegates ─────────────────────────────────────────────────────

    private lateinit var cameraSetup: CameraSetupDelegate
    private lateinit var mlPipeline: MlPipelineManager
    private lateinit var gestureRouter: GestureRouter

    // ── Managers ──────────────────────────────────────────────────────

    private lateinit var ttsManager: TTSManager
    private lateinit var hapticManager: HapticManager
    private lateinit var flashlightManager: FlashlightManager
    private lateinit var voiceCommandManager: VoiceCommandManager
    private lateinit var sceneAggregator: SceneAggregator
    private lateinit var onboardingManager: OnboardingManager
    private lateinit var highContrastOverlay: HighContrastOverlayView
    private lateinit var scanRepository: ScanRepository

    // ── P2: Specialized Modes ─────────────────────────────────────────
    private lateinit var readingModeHelper: ReadingModeHelper
    private lateinit var medicineReaderHelper: MedicineReaderHelper
    @Volatile private var isReadingMode = false
    @Volatile private var isMedicineMode = false

    // ── Executors & Handlers ──────────────────────────────────────────

    private lateinit var backgroundExecutor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Cross-Thread State ────────────────────────────────────────────

    @Volatile private var isCameraActive = false

    // ── Battery Monitoring ────────────────────────────────────────────

    @Volatile private var lastBatteryWarningLevel: Int = -1

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
                checkBatteryLevel()
            }
            mainHandler.postDelayed(this, BATTERY_CHECK_INTERVAL_MS)
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
        sceneAggregator = SceneAggregator()
        scanRepository = ScanRepository(requireContext().applicationContext)

        // P2: Specialized mode helpers
        readingModeHelper = ReadingModeHelper()
        medicineReaderHelper = MedicineReaderHelper()

        highContrastOverlay = fragmentCameraBinding.highContrastOverlay

        // ── Initialize Delegates ─────────────────────────────────────
        cameraSetup = CameraSetupDelegate()

        mlPipeline = MlPipelineManager(requireContext(), backgroundExecutor)
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
                    ttsManager.speakImmediate("Text recognition failed.")
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
        gestureRouter.onSingleTapAction = { announceDetectedObject() }
        gestureRouter.onDoubleTapAction = { performOcrOnCurrentFrame() }
        gestureRouter.onLongPressAction = { triggerSceneSummary() }

        // ── Attach Gesture Router ────────────────────────────────────
        gestureRouter.attach(fragmentCameraBinding.cameraContainer)

        // ── Initialize ML Pipeline on background thread ───────────────
        backgroundExecutor.execute {
            mlPipeline.initialize(
                context = requireContext(),
                threshold = viewModel.currentThreshold,
                delegate = viewModel.currentDelegate,
                model = viewModel.currentModel,
                maxResults = viewModel.currentMaxResults,
                detectorListener = this
            )
            fragmentCameraBinding.viewFinder.post { setUpCamera() }
        }

        initBottomSheetControls()
        fragmentCameraBinding.overlay.setRunningMode(RunningMode.LIVE_STREAM)
        fragmentCameraBinding.highContrastOverlay.setRunningMode(RunningMode.LIVE_STREAM)

        // ── Onboarding ───────────────────────────────────────────────
        onboardingManager = OnboardingManager(
            requireContext().applicationContext, ttsManager, hapticManager
        )
        mainHandler.postDelayed({
            if (isAdded) onboardingManager.playTutorialIfFirstRun()
        }, ONBOARDING_DELAY_MS)

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
                mlPipeline.objectDetectorHelper.setupObjectDetector()
            }
        }

        if (cameraSetup.cameraProvider != null) {
            cameraSetup.rebindCamera(
                requireContext(), this,
                fragmentCameraBinding.viewFinder,
                backgroundExecutor,
                mlPipeline.createCompositeAnalyzer()
            )
        }

        isCameraActive = true
        mainHandler.postDelayed(sceneSummaryRunnable, SCENE_SUMMARY_INTERVAL_MS)
        mainHandler.postDelayed(batteryCheckRunnable, BATTERY_CHECK_INTERVAL_MS)
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
        voiceCommandManager.onOcrRequested = {
            activity?.runOnUiThread { if (isAdded) performOcrOnCurrentFrame() }
        }
        voiceCommandManager.onObjectDetectionRequested = {
            activity?.runOnUiThread { if (isAdded) announceDetectedObject() }
        }
        voiceCommandManager.onLightCheckRequested = {
            activity?.runOnUiThread { if (isAdded) announceLightConditions() }
        }
        voiceCommandManager.onListeningStateChanged = { listening ->
            activity?.runOnUiThread {
                if (isAdded) {
                    if (listening) {
                        ttsManager.stop()
                        ttsManager.speakImmediate(context?.getString(R.string.voice_listening) ?: "Listening for commands...")
                    } else {
                        ttsManager.speakImmediate(context?.getString(R.string.voice_stopped) ?: "Listening stopped.")
                    }
                }
            }
        }
        voiceCommandManager.onError = { error -> Log.w(TAG, "Voice command error: $error") }
        voiceCommandManager.onTextRecognized = { text ->
            if (text.isNotEmpty()) {
                activity?.runOnUiThread { if (isAdded) voiceCommandManager.parseAndTrigger(text) }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Actions (called by GestureRouter)
    // ══════════════════════════════════════════════════════════════════

    private fun performOcrOnCurrentFrame() {
        ttsManager.speakImmediate(context?.getString(R.string.ocr_reading) ?: "Reading text...")
        mlPipeline.requestOcr()
    }

    private fun announceDetectedObject() {
        if (!mlPipeline.isOdInitialized()) {
            ttsManager.speak(context?.getString(R.string.od_no_objects) ?: "No objects detected yet.")
            return
        }
        val resultBundle = mlPipeline.objectDetectorHelper.lastResultBundle ?: run {
            ttsManager.speak(context?.getString(R.string.od_no_objects) ?: "No objects detected yet.")
            return
        }

        val detections = resultBundle.results[0].detections()
        if (detections.isNullOrEmpty()) {
            ttsManager.speak(context?.getString(R.string.od_no_visible) ?: "No objects currently visible.")
            return
        }

        val announcements = mlPipeline.objectDetectorHelper.getAnnounceableDetections(
            resultBundle = resultBundle,
            frameWidth = resultBundle.inputImageWidth,
            frameHeight = resultBundle.inputImageHeight
        )

        if (announcements.isNotEmpty()) {
            ttsManager.speak(announcements.first())
        } else {
            val topDetection = detections.maxByOrNull { it.categories()[0].score() }
            if (topDetection != null) {
                val cat = topDetection.categories()[0]
                ttsManager.speak(context?.getString(R.string.od_detected, cat.categoryName(), (cat.score() * 100).toInt())
                    ?: "${cat.categoryName()}, confidence ${(cat.score() * 100).toInt()} percent.")
            } else {
                ttsManager.speak(context?.getString(R.string.od_no_detected) ?: "No objects detected.")
            }
        }
    }

    private fun triggerSceneSummary() {
        if (!mlPipeline.isOdInitialized()) {
            ttsManager.speak(context?.getString(R.string.scene_no_data) ?: "No scene data available.")
            return
        }
        val resultBundle = mlPipeline.objectDetectorHelper.lastResultBundle ?: run {
            ttsManager.speak(context?.getString(R.string.scene_no_data) ?: "No scene data available.")
            return
        }

        val detections = resultBundle.results[0].detections()
        if (detections.isNullOrEmpty()) {
            ttsManager.speak(context?.getString(R.string.scene_no_visible) ?: "No objects currently visible.")
            return
        }

        val spatialDetections = detections.mapNotNull { detection ->
            val category = detection.categories().firstOrNull() ?: return@mapNotNull null
            mlPipeline.objectDetectorHelper.computeSpatialInfo(
                boundingBox = detection.boundingBox(),
                frameWidth = resultBundle.inputImageWidth,
                frameHeight = resultBundle.inputImageHeight,
                categoryName = category.categoryName(),
                score = category.score()
            )
        }

        val summary = sceneAggregator.aggregate(spatialDetections)
        if (summary != null) {
            hapticManager.vibrateLongPress()
            ttsManager.speak(summary)
            viewModel.saveSceneScan(summary)
        } else {
            ttsManager.speak(context?.getString(R.string.scene_summary_not_available) ?: "Scene summary not available.")
        }
    }

    private fun announceLightConditions() {
        val meanLuminance = mlPipeline.latestMeanLuminance
        val isDark = mlPipeline.latestIsDark

        if (meanLuminance == 0.0 && !isDark) {
            ttsManager.speak(context?.getString(R.string.light_not_measured) ?: "Light level not yet measured.")
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
    // Battery Monitoring
    // ══════════════════════════════════════════════════════════════════

    private fun checkBatteryLevel() {
        val batteryManager = requireContext().getSystemService(android.content.Context.BATTERY_SERVICE) as? BatteryManager
        val batteryLevel = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return

        viewModel.updateBatteryLevel(batteryLevel)

        if (batteryLevel <= BATTERY_CRITICAL_THRESHOLD && lastBatteryWarningLevel > BATTERY_CRITICAL_THRESHOLD) {
            lastBatteryWarningLevel = BATTERY_CRITICAL_THRESHOLD
            viewModel.setLastBatteryWarningLevel(BATTERY_CRITICAL_THRESHOLD)
            hapticManager.vibrateWarning()
            ttsManager.speakImmediate(
                context?.getString(R.string.battery_critical_warning, batteryLevel)
                    ?: "Battery critical: $batteryLevel percent."
            )
        } else if (batteryLevel <= BATTERY_LOW_THRESHOLD && lastBatteryWarningLevel > BATTERY_LOW_THRESHOLD) {
            lastBatteryWarningLevel = BATTERY_LOW_THRESHOLD
            viewModel.setLastBatteryWarningLevel(BATTERY_LOW_THRESHOLD)
            ttsManager.speakImmediate(
                context?.getString(R.string.battery_low_warning, batteryLevel)
                    ?: "Battery low: $batteryLevel percent."
            )
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
        sceneAggregator.resetCooldown()
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
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {
                fragmentCameraBinding.bottomSheetLayout.inferenceTimeVal.text = String.format("%d ms", resultBundle.inferenceTime)
                val detectionResult = resultBundle.results[0]
                if (isAdded) {
                    fragmentCameraBinding.overlay.setResults(detectionResult, resultBundle.inputImageHeight, resultBundle.inputImageWidth, resultBundle.inputImageRotation)
                    fragmentCameraBinding.highContrastOverlay.setResults(detectionResult, resultBundle.inputImageHeight, resultBundle.inputImageWidth, resultBundle.inputImageRotation)
                }
                fragmentCameraBinding.overlay.invalidate()
                fragmentCameraBinding.highContrastOverlay.invalidate()
            }
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
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
        const val BATTERY_CHECK_INTERVAL_MS = 120_000L
        const val BATTERY_LOW_THRESHOLD = 15
        const val BATTERY_CRITICAL_THRESHOLD = 5
    }
}
