package com.vyze.app.delegates

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.Display
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.vyze.app.FlashlightManager
import java.io.File
import java.util.concurrent.ExecutorService

/**
 * Handles all CameraX lifecycle and setup operations.
 *
 * Manages preview binding and ImageCapture for snapshot-mode VLM inference.
 * The VLM runs on-demand (triggered by tap/volume key), not continuously.
 */
class CameraSetupDelegate {

    private val TAG = "CameraSetupDelegate"

    var preview: Preview? = null
        private set
    var imageCapture: ImageCapture? = null
        private set
    var camera: Camera? = null
        private set
    var cameraProvider: ProcessCameraProvider? = null
        private set

    private lateinit var flashlightManager: FlashlightManager

    /**
     * Initializes the camera provider and binds use cases.
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun setupCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        flashlightMgr: FlashlightManager
    ) {
        this.flashlightManager = flashlightMgr

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindUseCases(context, lifecycleOwner, previewView)
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Rebinds camera use cases (called on resume after pause).
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun rebindCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        if (cameraProvider == null) return
        bindUseCases(context, lifecycleOwner, previewView)
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindUseCases(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        val provider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(previewView.display.rotation)
            .build()

        imageCapture = ImageCapture.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(previewView.display.rotation)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        provider.unbindAll()
        try {
            camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
            flashlightManager.camera = camera
            preview?.setSurfaceProvider(previewView.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    /**
     * Take a snapshot and return the bitmap via callback.
     * Used for on-demand VLM inference.
     *
     * @param onBitmap Callback with the captured bitmap (always on main thread).
     * @param onError  Callback with the error (always on main thread).
     */
    fun takeSnapshot(
        onBitmap: (android.graphics.Bitmap) -> Unit,
        onError: (String) -> Unit
    ) {
        val capture = imageCapture
        if (capture == null) {
            onError("ImageCapture not initialized")
            return
        }

        val tempFile = File.createTempFile("vyze_snapshot", ".jpg", context?.cacheDir)

        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context!!),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(tempFile.absolutePath)
                        tempFile.delete()
                        if (bitmap != null) {
                            onBitmap(bitmap)
                        } else {
                            onError("Failed to decode snapshot")
                        }
                    } catch (e: Exception) {
                        tempFile.delete()
                        onError("Snapshot decode failed: ${e.message}")
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    tempFile.delete()
                    onError("Snapshot capture failed: ${exception.message}")
                }
            }
        )
    }

    /** Reference to the application context for temp file creation. */
    private var context: Context? = null

    /** Set context for snapshot file creation. */
    fun setContext(context: Context) {
        this.context = context.applicationContext
    }

    /**
     * Updates the target rotation for the image capture.
     */
    fun updateRotation(display: Display) {
        imageCapture?.targetRotation = display.rotation
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
        context = null
    }
}
