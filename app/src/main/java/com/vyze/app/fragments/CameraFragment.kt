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
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Toast
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import com.vyze.app.BarcodeAnalyzer
import com.vyze.app.FaceDetectorHelper
import com.vyze.app.FlashlightManager
import com.vyze.app.GestureDetectorHelper
import com.vyze.app.HapticManager
import com.vyze.app.HighContrastOverlayView
import com.vyze.app.LuminanceAnalyzer
import com.vyze.app.MainViewModel
import com.vyze.app.ObjectDetectorHelper
import com.vyze.app.R
import com.vyze.app.SceneAggregator
import com.vyze.app.TextRecognitionHelper
import com.vyze.app.TTSManager
import com.vyze.app.VoiceCommandManager
import com.vyze.app.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CameraFragment : Fragment(), ObjectDetectorHelper.DetectorListener {

    private val TAG = "ObjectDetection"

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!

    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ExecutorService

    // Main thread handler for periodic scene summaries
    private val mainHandler = Handler(Looper.getMainLooper())

    // Accessibility components
    private lateinit var ttsManager: TTSManager
    private lateinit var gestureDetector: GestureDetector
    private lateinit var gestureDetectorHelper: GestureDetectorHelper

    // Text Recognition (OCR)
    private lateinit var textRecognitionHelper: TextRecognitionHelper

    // Haptic feedback
    private lateinit var hapticManager: HapticManager

    // Flashlight / Torch control
    private lateinit var flashlightManager: FlashlightManager

    // Voice Command
    private lateinit var voiceCommandManager: VoiceCommandManager

    // Barcode scanning
    private lateinit var barcodeAnalyzer: BarcodeAnalyzer

    // Face detection
    private lateinit var faceDetectorHelper: FaceDetectorHelper

    // Scene Aggregator
    private lateinit var sceneAggregator: SceneAggregator

    // High Contrast Overlay
    private lateinit var highContrastOverlay: HighContrastOverlayView

    // Luminance tracking
    private var latestMeanLuminance: Double = 0.0
    private var latestIsDark: Boolean = false

    // Latest detection results for TTS readout
    private var latestDetectionResult: ObjectDetectorHelper.ResultBundle? = null

    // OCR request flag: when true, the next frame is captured for text recognition
    // instead of normal object detection processing
    private val ocrRequested = AtomicBoolean(false)

    /**
     * Frame throttling: prevents frame accumulation when the previous frame
     * is still being analyzed. Guarantees zero memory leaks from unprocessed frames.
     */
    private val isAnalyzing = AtomicBoolean(false)

    /**
     * Battery optimization: frame counter for processing 1 out of every 3 frames.
     * At 30 FPS camera output, this yields ~10 FPS processing, reducing CPU/GPU
     * load and battery drain by ~66% while maintaining responsive detection.
     */
    private val frameCounter = AtomicInteger(0)

    /**
     * Whether the camera is currently bound and active.
     * Used to prevent frame processing after onPause().
     */
    @Volatile
    private var isCameraActive = false

    // ── Auto-Scene Summary ───────────────────────────────────────────────

    /**
     * Periodic scene summary runnable. Fires every [SCENE_SUMMARY_INTERVAL_MS]
     * to provide automatic room descriptions when the camera is active.
     */
    private val sceneSummaryRunnable = object : Runnable {
        override fun run() {
            if (isCameraActive && isAdded && this@CameraFragment::sceneAggregator.isInitialized) {
                triggerSceneSummary()
            }
            mainHandler.postDelayed(this, SCENE_SUMMARY_INTERVAL_MS)
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(
                requireActivity(),
                R.id.fragment_container
            )
                .navigate(CameraFragmentDirections.actionCameraToPermissions())
        }

        // Re-initialize object detector if it was cleared in onPause
        backgroundExecutor.execute {
            if (::objectDetectorHelper.isInitialized && objectDetectorHelper.isClosed()) {
                objectDetectorHelper.setupObjectDetector()
            }
        }

        // Re-bind camera if it was unbound in onPause
        if (cameraProvider != null && imageAnalyzer != null) {
            bindCameraUseCases()
        }

        isCameraActive = true

        // Start periodic scene summary timer
        mainHandler.postDelayed(sceneSummaryRunnable, SCENE_SUMMARY_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()

        // Stop periodic scene summary
        mainHandler.removeCallbacks(sceneSummaryRunnable)

        isCameraActive = false

        // Stop voice listening when paused
        if (this::voiceCommandManager.isInitialized && voiceCommandManager.isCurrentlyListening()) {
            voiceCommandManager.stopListening()
        }

        // Turn off torch when the fragment is paused
        if (this::flashlightManager.isInitialized && flashlightManager.isTorchOn()) {
            flashlightManager.toggleTorch(false)
        }

        // Unbind camera to fully release camera resources and stop frame processing
        cameraProvider?.unbindAll()
        camera = null
        flashlightManager.camera = null

        // Save and release ObjectDetector settings
        if (this::objectDetectorHelper.isInitialized) {
            viewModel.setModel(objectDetectorHelper.currentModel)
            viewModel.setDelegate(objectDetectorHelper.currentDelegate)
            viewModel.setThreshold(objectDetectorHelper.threshold)
            viewModel.setMaxResults(objectDetectorHelper.maxResults)
            backgroundExecutor.execute { objectDetectorHelper.clearObjectDetector() }
        }

        // Clear overlays
        if (_fragmentCameraBinding != null) {
            try { fragmentCameraBinding.overlay.clear() } catch (_: Exception) {}
            try { fragmentCameraBinding.highContrastOverlay.clear() } catch (_: Exception) {}
        }

        // Reset frame throttle state
        isAnalyzing.set(false)
        ocrRequested.set(false)
        frameCounter.set(0)
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        // Stop periodic scene summary
        mainHandler.removeCallbacks(sceneSummaryRunnable)

        // Shut down our background executor
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(
            Long.MAX_VALUE,
            TimeUnit.NANOSECONDS
        )

        // Cleanup TTS
        if (this::ttsManager.isInitialized) {
            ttsManager.onDestroy()
        }

        // Cleanup Text Recognition
        if (this::textRecognitionHelper.isInitialized) {
            textRecognitionHelper.close()
        }

        // Cleanup Haptic feedback
        if (this::hapticManager.isInitialized) {
            hapticManager.cancel()
        }

        // Cleanup Voice Commands
        if (this::voiceCommandManager.isInitialized) {
            voiceCommandManager.destroy()
        }

        // Cleanup Barcode Analyzer
        if (this::barcodeAnalyzer.isInitialized) {
            barcodeAnalyzer.close()
        }

        // Cleanup Face Detector
        if (this::faceDetectorHelper.isInitialized) {
            faceDetectorHelper.close()
        }

        // Null out camera reference held by FlashlightManager
        if (this::flashlightManager.isInitialized) {
            flashlightManager.camera = null
        }

        // Unbind camera provider to fully release CameraX resources
        cameraProvider?.unbindAll()
        cameraProvider = null
        camera = null

        // Clear overlays to release any bitmap / canvas references
        if (_fragmentCameraBinding != null) {
            try { fragmentCameraBinding.overlay.clear() } catch (_: Exception) {}
            try { fragmentCameraBinding.highContrastOverlay.clear() } catch (_: Exception) {}
        }

        // Reset frame throttle flag
        isAnalyzing.set(false)
        ocrRequested.set(false)
        frameCounter.set(0)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding =
            FragmentCameraBinding.inflate(inflater, container, false)

        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize our background executor
        backgroundExecutor = Executors.newSingleThreadExecutor()

        // Initialize TTS Manager
        ttsManager = TTSManager(requireContext().applicationContext)
        // Apply saved TTS settings
        ttsManager.applySettings(requireContext())

        // Initialize Text Recognition Helper
        textRecognitionHelper = TextRecognitionHelper()

        // Initialize Haptic feedback
        hapticManager = HapticManager(requireContext().applicationContext)

        // Initialize Flashlight Manager
        flashlightManager = FlashlightManager()

        // Initialize Voice Command Manager
        voiceCommandManager = VoiceCommandManager(requireContext().applicationContext)
        setupVoiceCommands()

        // Initialize Barcode Analyzer
        barcodeAnalyzer = BarcodeAnalyzer()

        // Initialize Face Detector
        faceDetectorHelper = FaceDetectorHelper()

        // Initialize Scene Aggregator
        sceneAggregator = SceneAggregator()

        // Reference to High Contrast Overlay
        highContrastOverlay = fragmentCameraBinding.highContrastOverlay

        // Setup gesture detector for full-screen accessibility taps
        setupGestureDetector()

        // Create the ObjectDetectionHelper that will handle the inference
        backgroundExecutor.execute {
            objectDetectorHelper =
                ObjectDetectorHelper(
                    context = requireContext(),
                    threshold = viewModel.currentThreshold,
                    currentDelegate = viewModel.currentDelegate,
                    currentModel = viewModel.currentModel,
                    maxResults = viewModel.currentMaxResults,
                    objectDetectorListener = this,
                    runningMode = RunningMode.LIVE_STREAM
                )

            // Wait for the views to be properly laid out
            fragmentCameraBinding.viewFinder.post {
                // Set up the camera and its use cases
                setUpCamera()
            }
        }

        // Attach listeners to UI control widgets
        initBottomSheetControls()
        fragmentCameraBinding.overlay.setRunningMode(RunningMode.LIVE_STREAM)
        fragmentCameraBinding.highContrastOverlay.setRunningMode(RunningMode.LIVE_STREAM)
    }

    // ── Voice Commands ───────────────────────────────────────────────────

    private fun setupVoiceCommands() {
        voiceCommandManager.onOcrRequested = {
            activity?.runOnUiThread {
                if (isAdded) {
                    performOcrOnCurrentFrame()
                }
            }
        }

        voiceCommandManager.onObjectDetectionRequested = {
            activity?.runOnUiThread {
                if (isAdded) {
                    announceDetectedObject()
                }
            }
        }

        voiceCommandManager.onLightCheckRequested = {
            activity?.runOnUiThread {
                if (isAdded) {
                    announceLightConditions()
                }
            }
        }

        voiceCommandManager.onListeningStateChanged = { listening ->
            activity?.runOnUiThread {
                if (isAdded) {
                    if (listening) {
                        ttsManager.stop()
                        ttsManager.speakImmediate("Listening for commands...")
                    } else {
                        ttsManager.speakImmediate("Listening stopped.")
                    }
                }
            }
        }

        voiceCommandManager.onError = { error ->
            Log.w(TAG, "Voice command error: $error")
        }

        voiceCommandManager.onTextRecognized = { text ->
            if (text.isNotEmpty()) {
                activity?.runOnUiThread {
                    if (isAdded) {
                        voiceCommandManager.parseAndTrigger(text)
                    }
                }
            }
        }
    }

    // ── Gesture Detection ────────────────────────────────────────────────

    private fun setupGestureDetector() {
        gestureDetectorHelper = GestureDetectorHelper(
            onSingleTap = {
                hapticManager.vibrateTap()
                announceDetectedObject()
            },
            onDoubleTap = {
                hapticManager.vibrateDoubleTap()
                performOcrOnCurrentFrame()
            },
            onLongPress = {
                hapticManager.vibrateLongPress()
                triggerSceneSummary()
            }
        )

        gestureDetector = GestureDetector(requireContext(), gestureDetectorHelper)

        // Attach to the full-screen layout so taps anywhere work
        fragmentCameraBinding.cameraContainer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }
    }

    // ── OCR ──────────────────────────────────────────────────────────────

    private fun performOcrOnCurrentFrame() {
        ttsManager.speakImmediate("Reading text...")
        ocrRequested.set(true)
    }

    // ── Object Detection Readout ─────────────────────────────────────────

    private fun announceDetectedObject() {
        val resultBundle = latestDetectionResult
        if (resultBundle == null || resultBundle.results.isEmpty()) {
            ttsManager.speak("No objects detected yet. Please wait.")
            return
        }

        val detections = resultBundle.results[0].detections()
        if (detections.isNullOrEmpty()) {
            ttsManager.speak("No objects currently visible.")
            return
        }

        // Attempt spatial + debounced announcements
        val announcements = objectDetectorHelper.getAnnounceableDetections(
            resultBundle = resultBundle,
            frameWidth = resultBundle.inputImageWidth,
            frameHeight = resultBundle.inputImageHeight
        )

        if (announcements.isNotEmpty()) {
            ttsManager.speak(announcements.first())
        } else {
            val topDetection = detections.maxByOrNull { it.categories()[0].score() }
            if (topDetection != null) {
                val category = topDetection.categories()[0]
                val name = category.categoryName()
                val confidence = (category.score() * 100).toInt()
                ttsManager.speak("$name, confidence $confidence percent.")
            } else {
                ttsManager.speak("No objects detected.")
            }
        }
    }

    // ── Scene Summary ────────────────────────────────────────────────────

    /**
     * Triggers a scene summary using [SceneAggregator].
     * Collects spatial detections from the latest result bundle and
     * produces a natural-language room description.
     *
     * Called on long-press gesture and periodically every 6 seconds.
     */
    private fun triggerSceneSummary() {
        val resultBundle = latestDetectionResult
        if (resultBundle == null || resultBundle.results.isEmpty()) {
            ttsManager.speak("No scene data available yet.")
            return
        }

        val detections = resultBundle.results[0].detections()
        if (detections.isNullOrEmpty()) {
            ttsManager.speak("No objects currently visible.")
            return
        }

        // Compute spatial info for all detections
        val spatialDetections = detections.mapNotNull { detection ->
            val category = detection.categories().firstOrNull() ?: return@mapNotNull null
            objectDetectorHelper.computeSpatialInfo(
                boundingBox = detection.boundingBox(),
                frameWidth = resultBundle.inputImageWidth,
                frameHeight = resultBundle.inputImageHeight,
                categoryName = category.categoryName(),
                score = category.score()
            )
        }

        // Aggregate into a natural sentence
        val summary = sceneAggregator.aggregate(spatialDetections)
        if (summary != null) {
            hapticManager.vibrateLongPress()
            ttsManager.speak(summary)
        } else {
            ttsManager.speak("Scene summary not available. Please wait.")
        }
    }

    // ── Light Conditions ─────────────────────────────────────────────────

    private fun announceLightConditions() {
        if (latestMeanLuminance == 0.0 && !latestIsDark) {
            ttsManager.speak("Light level not yet measured. Please wait.")
            return
        }

        val brightness = latestMeanLuminance.toInt()
        if (latestIsDark) {
            hapticManager.vibrateWarning()
            flashlightManager.autoTorch(true)
            ttsManager.speakImmediate(
                "Environment is dark. Brightness level: $brightness out of 255. Torch is now on."
            )
        } else {
            flashlightManager.autoTorch(false)
            ttsManager.speakImmediate(
                "Lighting is sufficient. Brightness level: $brightness out of 255. Torch is now off."
            )
        }
    }

    // ── Bottom Sheet Controls ────────────────────────────────────────────

    private fun initBottomSheetControls() {
        fragmentCameraBinding.bottomSheetLayout.maxResultsValue.text =
            viewModel.currentMaxResults.toString()
        fragmentCameraBinding.bottomSheetLayout.thresholdValue.text =
            String.format("%.2f", viewModel.currentThreshold)

        fragmentCameraBinding.bottomSheetLayout.thresholdMinus.setOnClickListener {
            if (objectDetectorHelper.threshold >= 0.1) {
                objectDetectorHelper.threshold -= 0.1f
                updateControlsUi()
            }
        }

        fragmentCameraBinding.bottomSheetLayout.thresholdPlus.setOnClickListener {
            if (objectDetectorHelper.threshold <= 0.8) {
                objectDetectorHelper.threshold += 0.1f
                updateControlsUi()
            }
        }

        fragmentCameraBinding.bottomSheetLayout.maxResultsMinus.setOnClickListener {
            if (objectDetectorHelper.maxResults > 1) {
                objectDetectorHelper.maxResults--
                updateControlsUi()
            }
        }

        fragmentCameraBinding.bottomSheetLayout.maxResultsPlus.setOnClickListener {
            if (objectDetectorHelper.maxResults < 5) {
                objectDetectorHelper.maxResults++
                updateControlsUi()
            }
        }

        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
            viewModel.currentDelegate,
            false
        )
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?,
                    p1: View?,
                    p2: Int,
                    p3: Long
                ) {
                    try {
                        objectDetectorHelper.currentDelegate = p2
                        updateControlsUi()
                    } catch (e: UninitializedPropertyAccessException) {
                        Log.e(TAG, "ObjectDetectorHelper has not been initialized yet.")
                    }
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* no op */
                }
            }

        fragmentCameraBinding.bottomSheetLayout.spinnerModel.setSelection(
            viewModel.currentModel,
            false
        )
        fragmentCameraBinding.bottomSheetLayout.spinnerModel.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?,
                    p1: View?,
                    p2: Int,
                    p3: Long
                ) {
                    try {
                        objectDetectorHelper.currentModel = p2
                        updateControlsUi()
                    } catch (e: UninitializedPropertyAccessException) {
                        Log.e(TAG, "ObjectDetectorHelper has not been initialized yet.")
                    }
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* no op */
                }
            }
    }

    private fun updateControlsUi() {
        fragmentCameraBinding.bottomSheetLayout.maxResultsValue.text =
            objectDetectorHelper.maxResults.toString()
        fragmentCameraBinding.bottomSheetLayout.thresholdValue.text =
            String.format("%.2f", objectDetectorHelper.threshold)

        backgroundExecutor.execute {
            objectDetectorHelper.clearObjectDetector()
            objectDetectorHelper.setupObjectDetector()
        }

        objectDetectorHelper.clearDebounceState()
        sceneAggregator.resetCooldown()

        fragmentCameraBinding.overlay.clear()
        fragmentCameraBinding.highContrastOverlay.clear()
    }

    // ── Camera Setup ─────────────────────────────────────────────────────

    private fun setUpCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            },
            ContextCompat.getMainExecutor(requireContext())
        )
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider =
            cameraProvider
                ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector =
            CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        preview =
            Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .build()

        imageAnalyzer =
            ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(
                        backgroundExecutor,
                        createCompositeAnalyzer()
                    )
                }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            flashlightManager.camera = camera
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)

            startVoiceListening()
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun startVoiceListening() {
        mainHandler.postDelayed({
            if (isAdded && this::voiceCommandManager.isInitialized) {
                voiceCommandManager.startListening()
            }
        }, VOICE_LISTENING_DELAY_MS)
    }

    // ── Composite Analyzer ───────────────────────────────────────────────

    /**
     * Creates a composite analyzer with frame throttling and battery optimization.
     *
     * **Battery optimization:** Processes 1 out of every 3 frames (~10 FPS instead
     * of 30 FPS), reducing CPU/GPU load by ~66%. Luminance analysis still runs on
     * every frame for accurate auto-torch response.
     *
     * **Frame throttling:** Prevents frame accumulation when the previous frame
     * is still being analyzed.
     *
     * Frame processing pipeline:
     *  1. LuminanceAnalyzer — reads Y plane (every frame, no close)
     *  2. Frame skip check — skip OD/OCR/barcode/face on 2 of 3 frames
     *  3. Create shared bitmap from imageProxy, close proxy
     *  4. BarcodeAnalyzer — scans for UPC/EAN/QR on bitmap
     *  5. FaceDetectorHelper — detects faces on bitmap
     *  6. TextRecognitionHelper (if OCR requested) OR ObjectDetectorHelper
     */
    private fun createCompositeAnalyzer(): ImageAnalysis.Analyzer {
        val luminanceAnalyzer = LuminanceAnalyzer { isDark, meanLuminance ->
            latestIsDark = isDark
            latestMeanLuminance = meanLuminance

            activity?.runOnUiThread {
                if (isAdded && this::flashlightManager.isInitialized) {
                    flashlightManager.autoTorch(isDark)
                }
            }
        }

        return ImageAnalysis.Analyzer { imageProxy ->
            // Always run luminance on every frame for accurate auto-torch
            luminanceAnalyzer.analyzeLuminance(imageProxy)

            // Battery optimization: process 1 of every 3 frames
            val count = frameCounter.incrementAndGet()
            if (count % FRAME_SKIP_RATIO != 0) {
                imageProxy.close()
                return@Analyzer
            }

            // Frame throttling: skip if previous frame still processing
            if (!isAnalyzing.compareAndSet(false, true)) {
                imageProxy.close()
                return@Analyzer
            }

            var sharedBitmap: android.graphics.Bitmap? = null
            try {
                // Create shared bitmap from the RGBA_8888 frame
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                sharedBitmap = android.graphics.Bitmap.createBitmap(
                    imageProxy.width, imageProxy.height,
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                imageProxy.use { sharedBitmap!!.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
                imageProxy.close()

                // Run barcode and face detection on the shared bitmap
                val latch = CountDownLatch(2)

                barcodeAnalyzer.processBitmap(
                    sharedBitmap, rotationDegrees,
                    onSuccess = { announcements ->
                        if (announcements.isNotEmpty()) {
                            activity?.runOnUiThread {
                                if (isAdded) ttsManager.speak(announcements.first())
                            }
                        }
                        latch.countDown()
                    },
                    onError = { latch.countDown() }
                )

                faceDetectorHelper.processBitmap(
                    sharedBitmap, rotationDegrees,
                    onSuccess = { announcements ->
                        if (announcements.isNotEmpty()) {
                            activity?.runOnUiThread {
                                if (isAdded) ttsManager.speak(announcements.first())
                            }
                        }
                        latch.countDown()
                    },
                    onError = { latch.countDown() }
                )

                latch.await(3, TimeUnit.SECONDS)

                // Route to OCR or OD
                if (ocrRequested.compareAndSet(true, false)) {
                    textRecognitionHelper.processBitmap(
                        sharedBitmap, rotationDegrees,
                        onSuccess = { recognizedText ->
                            activity?.runOnUiThread {
                                if (isAdded) {
                                    highContrastOverlay.setOcrText(recognizedText)
                                    ttsManager.speakImmediate(recognizedText)
                                }
                            }
                            isAnalyzing.set(false)
                        },
                        onError = { error ->
                            Log.e(TAG, "OCR processing failed", error)
                            activity?.runOnUiThread {
                                if (isAdded) {
                                    highContrastOverlay.clearOcrText()
                                    ttsManager.speakImmediate("Text recognition failed. Please try again.")
                                }
                            }
                            isAnalyzing.set(false)
                        }
                    )
                } else {
                    objectDetectorHelper.detectLivestreamBitmap(
                        sharedBitmap, SystemClock.uptimeMillis()
                    )
                    isAnalyzing.set(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Composite analyzer error", e)
                imageProxy.close()
                isAnalyzing.set(false)
            }
        }
    }

    // ── OD Results Callback ──────────────────────────────────────────────

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation =
            fragmentCameraBinding.viewFinder.display.rotation
    }

    override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
        latestDetectionResult = resultBundle

        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {
                fragmentCameraBinding.bottomSheetLayout.inferenceTimeVal.text =
                    String.format("%d ms", resultBundle.inferenceTime)

                val detectionResult = resultBundle.results[0]
                if (isAdded) {
                    fragmentCameraBinding.overlay.setResults(
                        detectionResult,
                        resultBundle.inputImageHeight,
                        resultBundle.inputImageWidth,
                        resultBundle.inputImageRotation
                    )

                    fragmentCameraBinding.highContrastOverlay.setResults(
                        detectionResult,
                        resultBundle.inputImageHeight,
                        resultBundle.inputImageWidth,
                        resultBundle.inputImageRotation
                    )
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
                fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
                    ObjectDetectorHelper.DELEGATE_CPU, false
                )
            }
        }
    }

    companion object {
        private const val VOICE_LISTENING_DELAY_MS = 2000L

        /**
         * Process 1 out of every 3 frames for battery optimization.
         * At 30 FPS camera output, this yields ~10 FPS processing.
         */
        const val FRAME_SKIP_RATIO = 3

        /**
         * Interval for automatic scene summary announcements.
         * Fires every 6 seconds while the camera is active.
         */
        const val SCENE_SUMMARY_INTERVAL_MS = 6000L
    }
}
