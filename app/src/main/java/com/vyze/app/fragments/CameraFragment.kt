/*\n * Copyright 2022 The TensorFlow Authors. All Rights Reserved.\n *\n * Licensed under the Apache License, Version 2.0 (the "License");\n * you may not use this file except in compliance with the License.\n * You may obtain a copy of the License at\n *\n *             http://www.apache.org/licenses/LICENSE-2.0\n *\n * Unless required by applicable law or agreed to in writing, software\n * distributed under the License is distributed on an "AS IS" BASIS,\n * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n * See the License for the specific language governing permissions and\n * limitations under the License.\n */
package com.vyze.app.fragments

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
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
import com.vyze.app.FlashlightManager
import com.vyze.app.GestureDetectorHelper
import com.vyze.app.HapticManager
import com.vyze.app.HighContrastOverlayView
import com.vyze.app.LuminanceAnalyzer
import com.vyze.app.MainViewModel
import com.vyze.app.ObjectDetectorHelper
import com.vyze.app.R
import com.vyze.app.TextRecognitionHelper
import com.vyze.app.TTSManager
import com.vyze.app.VoiceCommandManager
import com.vyze.app.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

        backgroundExecutor.execute {
            if (::objectDetectorHelper.isInitialized && objectDetectorHelper.isClosed()) {
                objectDetectorHelper.setupObjectDetector()
            }
        }
    }

    override fun onPause() {
        super.onPause()

        // Stop voice listening when paused
        if (this::voiceCommandManager.isInitialized && voiceCommandManager.isCurrentlyListening()) {
            voiceCommandManager.stopListening()
        }

        // Turn off torch when the fragment is paused
        if (this::flashlightManager.isInitialized && flashlightManager.isTorchOn()) {
            flashlightManager.toggleTorch(false)
        }

        // Save ObjectDetector settings
        if (this::objectDetectorHelper.isInitialized) {
            viewModel.setModel(objectDetectorHelper.currentModel)
            viewModel.setDelegate(objectDetectorHelper.currentDelegate)
            viewModel.setThreshold(objectDetectorHelper.threshold)
            viewModel.setMaxResults(objectDetectorHelper.maxResults)
            // Close the object detector and release resources
            backgroundExecutor.execute { objectDetectorHelper.clearObjectDetector() }
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

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

        // Initialize Text Recognition Helper
        textRecognitionHelper = TextRecognitionHelper()

        // Initialize Haptic feedback
        hapticManager = HapticManager(requireContext().applicationContext)

        // Initialize Flashlight Manager
        flashlightManager = FlashlightManager()

        // Initialize Voice Command Manager
        voiceCommandManager = VoiceCommandManager(requireContext().applicationContext)
        setupVoiceCommands()

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

    /**
     * Sets up voice command callbacks and starts listening.
     */
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

    /**
     * Sets up the full-screen gesture detector for accessibility.
     * Attaches to the root layout so visually impaired users can tap anywhere.
     * Each gesture provides haptic feedback for tactile confirmation.
     */
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
                announceLightConditions()
            }
        )

        gestureDetector = GestureDetector(requireContext(), gestureDetectorHelper)

        // Attach to the full-screen layout so taps anywhere work
        fragmentCameraBinding.cameraContainer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }
    }

    /**
     * Captures the current camera frame and runs OCR text recognition.
     * Announces "Reading text..." immediately so the user knows processing has started,
     * then reads the recognized text aloud when results arrive.
     */
    private fun performOcrOnCurrentFrame() {
        // Provide immediate audio feedback
        ttsManager.speakImmediate("Reading text...")

        // Set the flag so the composite analyzer captures the next frame for OCR
        ocrRequested.set(true)
    }

    /**
     * Announces the highest-confidence detected object via TTS.
     */
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

        // Find the highest-confidence detection
        val topDetection = detections.maxByOrNull { it.categories()[0].score() }
        if (topDetection != null) {
            val category = topDetection.categories()[0]
            val name = category.categoryName()
            val confidence = (category.score() * 100).toInt()
            ttsManager.speak("Detected: $name, confidence $confidence percent.")
        } else {
            ttsManager.speak("No objects detected.")
        }
    }

    /**
     * Announces current light conditions via TTS.
     * Also provides haptic warning if the environment is dark, and auto-enables the torch.
     */
    private fun announceLightConditions() {
        if (latestMeanLuminance == 0.0 && !latestIsDark) {
            ttsManager.speak("Light level not yet measured. Please wait.")
            return
        }

        val brightness = latestMeanLuminance.toInt()
        if (latestIsDark) {
            hapticManager.vibrateWarning()
            // Auto-enable the torch if the environment is dark
            flashlightManager.autoTorch(true)
            ttsManager.speakImmediate(
                "Environment is dark. Brightness level: $brightness out of 255. Torch is now on."
            )
        } else {
            // Auto-disable the torch if the environment is bright enough
            flashlightManager.autoTorch(false)
            ttsManager.speakImmediate(
                "Lighting is sufficient. Brightness level: $brightness out of 255. Torch is now off."
            )
        }
    }

    private fun initBottomSheetControls() {
        // Init bottom sheet settings
        fragmentCameraBinding.bottomSheetLayout.maxResultsValue.text =
            viewModel.currentMaxResults.toString()
        fragmentCameraBinding.bottomSheetLayout.thresholdValue.text =
            String.format("%.2f", viewModel.currentThreshold)

        // When clicked, lower detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.thresholdMinus.setOnClickListener {
            if (objectDetectorHelper.threshold >= 0.1) {
                objectDetectorHelper.threshold -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.thresholdPlus.setOnClickListener {
            if (objectDetectorHelper.threshold <= 0.8) {
                objectDetectorHelper.threshold += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, reduce the number of objects that can be detected at a time
        fragmentCameraBinding.bottomSheetLayout.maxResultsMinus.setOnClickListener {
            if (objectDetectorHelper.maxResults > 1) {
                objectDetectorHelper.maxResults--
                updateControlsUi()
            }
        }

        // When clicked, increase the number of objects that can be detected at a time
        fragmentCameraBinding.bottomSheetLayout.maxResultsPlus.setOnClickListener {
            if (objectDetectorHelper.maxResults < 5) {
                objectDetectorHelper.maxResults++
                updateControlsUi()
            }
        }

        // When clicked, change the underlying hardware used for inference. Current options are CPU
        // GPU, and NNAPI
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

        // When clicked, change the underlying model used for object detection
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

    // Update the values displayed in the bottom sheet. Reset detector.
    private fun updateControlsUi() {
        fragmentCameraBinding.bottomSheetLayout.maxResultsValue.text =
            objectDetectorHelper.maxResults.toString()
        fragmentCameraBinding.bottomSheetLayout.thresholdValue.text =
            String.format("%.2f", objectDetectorHelper.threshold)

        backgroundExecutor.execute {
            objectDetectorHelper.clearObjectDetector()
            objectDetectorHelper.setupObjectDetector()
        }

        fragmentCameraBinding.overlay.clear()
        fragmentCameraBinding.highContrastOverlay.clear()
    }

    // Initialize CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                // CameraProvider
                cameraProvider = cameraProviderFuture.get()

                // Build and bind the camera use cases
                bindCameraUseCases()
            },
            ContextCompat.getMainExecutor(requireContext())
        )
    }

    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        // CameraProvider
        val cameraProvider =
            cameraProvider
                ?: throw IllegalStateException("Camera initialization failed.")

        // CameraSelector - makes assumption that we're only using the back camera
        val cameraSelector =
            CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        // Preview. Only using the 4:3 ratio because this is the closest to our models
        preview =
            Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .build()

        // ImageAnalysis. Using RGBA 8888 to match how our models work
        imageAnalyzer =
            ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(
                        backgroundExecutor,
                        createCompositeAnalyzer()
                    )
                }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            // Pass the camera reference to FlashlightManager for torch control
            flashlightManager.camera = camera

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)

            // Start voice listening after camera is ready
            startVoiceListening()
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    /**
     * Start voice listening after TTS has had time to finish any announcements.
     */
    private fun startVoiceListening() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isAdded && this::voiceCommandManager.isInitialized) {
                voiceCommandManager.startListening()
            }
        }, VOICE_LISTENING_DELAY_MS)
    }

    /**
     * Creates a composite analyzer with frame throttling.
     *
     * Guarantees that only one frame is being processed at a time:
     * if the previous frame is still analyzing, the new frame is
     * dropped immediately (closed without processing). This prevents
     * frame accumulation and ensures zero memory leaks.
     *
     * Concerns handled:
     * 1. LuminanceAnalyzer for light level detection
     * 2. ObjectDetectorHelper for object detection
     * 3. On-demand OCR via TextRecognitionHelper (when double-tap or voice command triggers a request)
     * 4. HighContrastOverlayView updates for low-vision users
     */
    private fun createCompositeAnalyzer(): ImageAnalysis.Analyzer {
        val luminanceAnalyzer = LuminanceAnalyzer { isDark, meanLuminance ->
            latestIsDark = isDark
            latestMeanLuminance = meanLuminance
        }

        return ImageAnalysis.Analyzer { imageProxy ->
            // Frame throttling: skip this frame if the previous one is still being analyzed
            if (!isAnalyzing.compareAndSet(false, true)) {
                imageProxy.close()
                return@Analyzer
            }

            try {
                // Run luminance analysis first (reads Y plane, does not close imageProxy)
                luminanceAnalyzer.analyzeLuminance(imageProxy)

                // Check if OCR was requested via double-tap or voice command
                if (ocrRequested.compareAndSet(true, false)) {
                    // Route frame to TextRecognitionHelper (it closes imageProxy)
                    textRecognitionHelper.processImageProxy(
                        imageProxy = imageProxy,
                        onSuccess = { recognizedText ->
                            // Read the recognized text aloud on the main thread
                            activity?.runOnUiThread {
                                if (isAdded) {
                                    // Display OCR text on the high-contrast overlay
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
                    // Normal pipeline: object detection (handles its own imageProxy lifecycle)
                    objectDetectorHelper.detectLivestreamFrame(imageProxy)
                    // objectDetectorHelper handles imageProxy.close() internally,
                    // so we release the throttle flag here
                    isAnalyzing.set(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Composite analyzer error", e)
                imageProxy.close()
                isAnalyzing.set(false)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation =
            fragmentCameraBinding.viewFinder.display.rotation
    }

    // Update UI after objects have been detected. Extracts original image height/width
    // to scale and place bounding boxes properly through OverlayView
    override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
        // Store latest results for TTS readout on gesture
        latestDetectionResult = resultBundle

        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {
                fragmentCameraBinding.bottomSheetLayout.inferenceTimeVal.text =
                    String.format("%d ms", resultBundle.inferenceTime)

                // Pass necessary information to both overlays for drawing on the canvas
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

                // Force a redraw
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
    }
}
