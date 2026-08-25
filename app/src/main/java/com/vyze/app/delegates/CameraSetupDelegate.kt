package com.vyze.app.delegates

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.Display
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.vyze.app.FlashlightManager
import java.util.concurrent.ExecutorService

/**
 * Handles all CameraX lifecycle and setup operations.
 *
 * Extracted from CameraFragment to keep camera setup isolated and testable.
 * Manages preview binding, image analysis setup, and torch/flashlight control.
 */
class CameraSetupDelegate {

    private val TAG = "CameraSetupDelegate"

    var preview: Preview? = null
        private set
    var imageAnalyzer: ImageAnalysis? = null
        private set
    var camera: Camera? = null
        private set
    var cameraProvider: ProcessCameraProvider? = null
        private set

    private lateinit var flashlightManager: FlashlightManager

    /**
     * Initializes the camera provider and binds use cases.
     *
     * @param context       Application context.
     * @param lifecycleOwner The fragment/activity lifecycle owner.
     * @param previewView   The PreviewView for camera preview.
     * @param backgroundExecutor Executor for image analysis.
     * @param analyzer      The composite ImageAnalysis.Analyzer.
     * @param flashlightManager FlashlightManager to control torch.
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun setupCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        backgroundExecutor: ExecutorService,
        analyzer: ImageAnalysis.Analyzer,
        flashlightMgr: FlashlightManager
    ) {
        this.flashlightManager = flashlightMgr


        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindUseCases(context, lifecycleOwner, previewView, backgroundExecutor, analyzer)
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Rebinds camera use cases (called on resume after pause).
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun rebindCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        backgroundExecutor: ExecutorService,
        analyzer: ImageAnalysis.Analyzer
    ) {
        if (cameraProvider == null) return
        bindUseCases(context, lifecycleOwner, previewView, backgroundExecutor, analyzer)
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindUseCases(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        backgroundExecutor: ExecutorService,
        analyzer: ImageAnalysis.Analyzer
    ) {
        val provider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(previewView.display.rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(previewView.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(backgroundExecutor, analyzer) }

        provider.unbindAll()
        try {
            camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalyzer)
            flashlightManager.camera = camera
            preview?.setSurfaceProvider(previewView.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    /**
     * Updates the target rotation for the image analyzer.
     */
    fun updateRotation(display: Display) {
        imageAnalyzer?.targetRotation = display.rotation
    }

    /**
     * Releases the camera and unbinds all use cases.
     */
    fun releaseCamera() {
        cameraProvider?.unbindAll()
        camera = null
        flashlightManager.camera = null
    }

    /**
     * Fully destroys the camera provider reference.
     */
    fun destroy() {
        releaseCamera()
        cameraProvider = null
    }
}
