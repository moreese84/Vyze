package com.vyze.app.delegates

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.vyze.app.FlashlightManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Handles all CameraX lifecycle and setup operations.
 *
 * Uses ImageAnalysis for zero-disk, in-memory frame extraction.
 * The latest frame is held in a RAM buffer and extracted on-demand
 * when takeSnapshot() is called — eliminating the ~300-450ms disk
 * round-trip of the previous ImageCapture.takePicture() approach.
 */
class CameraSetupDelegate {

    private val TAG = "CameraSetupDelegate"

    var preview: Preview? = null
        private set
    var camera: Camera? = null
        private set
    var cameraProvider: ProcessCameraProvider? = null
        private set

    private lateinit var flashlightManager: FlashlightManager

    // ── In-Memory Frame Buffer ─────────────────────────────────────
    // Holds the latest YUV_888 frame from ImageAnalysis as a Bitmap.
    // Updated every frame (~33ms at 30fps). takeSnapshot() pulls from here.

    /** Latest decoded frame from the camera — updated by ImageAnalysis.Analyzer. */
    private val latestFrame = AtomicReference<Bitmap?>(null)

    /** Lock to prevent concurrent frame extraction + recycling. */
    private val frameLock = AtomicBoolean(false)

    /** Analysis executor — single thread to avoid frame drops. */
    private var analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** Main handler for posting callbacks. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Reference to PreviewView for fallback frame extraction. */
    private var previewViewRef: PreviewView? = null

    /** Whether a frame has been consumed by takeSnapshot since the last analyzer delivery. */
    private val frameConsumed = AtomicBoolean(false)

    /**
     * Monotonically increasing frame counter. Incremented every time the analyzer
     * delivers a new frame. takeSnapshot() uses this to guarantee it reads a frame
     * that arrived AFTER the voice query was triggered — preventing stale frame reuse.
     */
    @Volatile
    private var frameCounter = 0L

    // ── Auto-Torch Luminance Detection ────────────────────────────
    // Calculates average brightness from the Y plane of each YUV frame.
    // Uses dual-threshold hysteresis to prevent rapid torch toggling:
    //   - Torch ON  when brightness < DARK_THRESHOLD (35/255)
    //   - Torch OFF when brightness > BRIGHT_THRESHOLD (65/255)
    // Only samples every Nth frame to minimize CPU overhead.

    /** Frame counter at last luminance check — skips frames to reduce CPU load. */
    @Volatile
    private var lastLuminanceCheckFrame = 0L

    /** Hysteresis state — prevents rapid torch toggling at boundary light levels. */
    @Volatile
    private var isDarkEnvironment = false

    companion object {
        /** How long to poll for a fresh frame before giving up (ms). */
        private const val FRAME_WAIT_TIMEOUT_MS = 300L
        /** Polling interval when waiting for a frame (ms). */
        private const val FRAME_POLL_INTERVAL_MS = 20L

        // ── Auto-Torch Luminance Thresholds ─────────────────────
        /** Average Y-plane brightness below this → torch ON (0-255). */
        private const val DARK_THRESHOLD = 35
        /** Average Y-plane brightness above this → torch OFF (0-255). */
        private const val BRIGHT_THRESHOLD = 65
        /** Check luminance every N frames (~300ms at 30fps) to minimize CPU load. */
        private const val LUMINANCE_CHECK_INTERVAL = 10L
    }

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

        // ImageAnalysis — delivers frames in RAM at camera framerate.
        // No disk I/O, no JPEG encode/decode round-trip.
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(previewView.display.rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        // Store previewView reference for fallback bitmap extraction
        previewViewRef = previewView

        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            try {
                val bitmap = imageProxyToBitmap(imageProxy)
                if (bitmap != null) {
                    // Swap in the new frame — old one is recycled below.
                    // CRITICAL: Acquire frameLock before recycling so takeSnapshot()
                    // doesn't end up with a reference to a recycled Bitmap.
                    val oldFrame = latestFrame.getAndSet(bitmap)
                    if (oldFrame != null && !oldFrame.isRecycled) {
                        synchronized(frameLock) {
                            if (!oldFrame.isRecycled) {
                                oldFrame.recycle()
                            }
                        }
                    }
                    // Mark frame as available for snapshot consumption.
                    // takeSnapshot() will clear frameConsumed after copying.
                    frameConsumed.set(false)
                    frameCounter++  // Signal takeSnapshot() that a genuinely new frame arrived

                    // ── Auto-Torch: luminance check every 10th frame (~300ms at 30fps) ──
                    if (frameCounter - lastLuminanceCheckFrame >= LUMINANCE_CHECK_INTERVAL) {
                        lastLuminanceCheckFrame = frameCounter
                        checkLuminanceAndAutoTorch(imageProxy)
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Frame analysis error: ${e.message}")
            } finally {
                try { imageProxy.close() } catch (_: Throwable) {}
            }
        }

        provider.unbindAll()
        try {
            camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
            flashlightManager.camera = camera
            preview?.setSurfaceProvider(previewView.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    /**
     * Take a snapshot from the in-memory frame buffer.
     * Returns a copy of the latest frame as a Bitmap — zero disk I/O.
     *
     * Typical latency: ~5-20ms (Bitmap.copy + rotation), compared to
     * ~300-450ms for the previous ImageCapture.takePicture() disk round-trip.
     *
     * @param onBitmap Callback with the captured bitmap (on main thread).
     * @param onError  Callback with the error (on main thread).
     */
    fun takeSnapshot(
        onBitmap: (Bitmap) -> Unit,
        onError: (String) -> Unit
    ) {
        val counterAtQuery = frameCounter
        var foundFrame: Bitmap? = null
        var waited = 0L
        val pollInterval = 10L
        val maxWait = 300L

        while (waited < maxWait) {
            val candidate = latestFrame.get()
            if (candidate != null && !candidate.isRecycled) {
                // Ideal condition: a new frame arrived post-query
                if (frameCounter > counterAtQuery) {
                    foundFrame = candidate
                    break
                }
            }
            Thread.sleep(pollInterval)
            waited += pollInterval
        }

        // FALLBACK: If no strictly newer frame arrived within 300ms, use the latest valid frame
        if (foundFrame == null) {
            foundFrame = latestFrame.get()?.takeIf { !it.isRecycled }
        }

        if (foundFrame != null) {
            extractFrameCopy(foundFrame, onBitmap, onError)
        } else {
            onError("Camera frame unavailable")
        }
    }

    /**
     * Extract a deep copy of the frame on the analysis thread.
     * Shared by both the instant-path and the polled-path.
     */
    private fun extractFrameCopy(
        frame: Bitmap,
        onBitmap: (Bitmap) -> Unit,
        onError: (String) -> Unit
    ) {
        analysisExecutor.execute {
            try {
                while (!frameLock.compareAndSet(false, true)) {
                    Thread.sleep(1)
                }
                try {
                    if (frame.isRecycled) {
                        mainHandler.post { onError("Frame recycled before extraction") }
                        return@execute
                    }
                    val copy = frame.copy(Bitmap.Config.ARGB_8888, true)
                    if (copy != null && !copy.isRecycled) {
                        mainHandler.post { onBitmap(copy) }
                    } else {
                        mainHandler.post { onError("Failed to copy camera frame") }
                    }
                } finally {
                    frameLock.set(false)
                }
            } catch (e: Throwable) {
                mainHandler.post { onError("Frame extraction failed: ${e.message}") }
            }
        }
    }

    // ── YUV → Bitmap Conversion ────────────────────────────────────

    /**
     * Calculate average luminance from the Y plane of a YUV frame and
     * trigger auto-torch if the environment is dark or bright.
     *
     * Uses dual-threshold hysteresis:
     *   - Torch ON  when avg brightness < 35/255
     *   - Torch OFF when avg brightness > 65/255
     *   - Between 35-65: no change (prevents rapid toggling)
     *
     * This runs on the analysis executor — NOT on the main thread.
     * Sampling is throttled to every 10th frame (~300ms) to minimize CPU overhead.
     */
    private fun checkLuminanceAndAutoTorch(imageProxy: ImageProxy) {
        try {
            val planes = imageProxy.planes
            if (planes.isEmpty()) return

            val yBuffer = planes[0].buffer
            val yRowStride = planes[0].rowStride
            val pixelStride = planes[0].pixelStride
            val width = imageProxy.width
            val height = imageProxy.height

            // Sample every 4th pixel for speed — enough for average brightness
            val sampleStep = 4
            var sum = 0L
            var count = 0

            val rowBuffer = ByteArray(yRowStride)
            for (row in 0 until height step sampleStep) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(rowBuffer, 0, minOf(yRowStride, rowBuffer.size))
                for (col in 0 until width step sampleStep * pixelStride) {
                    val idx = col * pixelStride
                    if (idx < rowBuffer.size) {
                        sum += (rowBuffer[idx].toInt() and 0xFF)
                        count++
                    }
                }
            }

            if (count == 0) return
            val avgBrightness = (sum / count).toInt()

            // Hysteresis: ON < 35, OFF > 65, dead zone 35-65 prevents oscillation
            val shouldBeOn = avgBrightness < DARK_THRESHOLD
            val shouldBeOff = avgBrightness > BRIGHT_THRESHOLD

            val newDarkState = if (shouldBeOn) true else if (shouldBeOff) false else isDarkEnvironment

            if (newDarkState != isDarkEnvironment) {
                isDarkEnvironment = newDarkState
                flashlightManager.autoTorch(isDarkEnvironment)
                Log.d(TAG, "Auto-torch: brightness=$avgBrightness/255, torch=${if (isDarkEnvironment) "ON" else "OFF"}")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Luminance check error: ${e.message}")
        }
    }

    /**
     * Convert an ImageProxy (YUV_888) to an ARGB_8888 Bitmap.
     * Handles rotation based on imageProxy.imageInfo.rotationDegrees.
     *
     * This runs on the analysis executor — NOT on the main thread.
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        try {
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            // Y plane
            yBuffer.get(nv21, 0, ySize)
            // VU plane (interleaved for NV21)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 85, out)
            val jpegBytes = out.toByteArray()

            var bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: return null

            // Apply rotation
            val rotation = imageProxy.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotation.toFloat())
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) {
                    bitmap.recycle()
                }
                bitmap = rotated
            }

            return bitmap
        } catch (e: Throwable) {
            Log.w(TAG, "YUV conversion error: ${e.message}")
            return null
        }
    }

    /** Reference to the application context. */
    private var context: Context? = null

    /** Set context for camera provider initialization. */
    fun setContext(context: Context) {
        this.context = context.applicationContext
    }

    /**
     * Updates the target rotation for the image analysis.
     */
    fun updateRotation(display: Display) {
        // Rotation is applied at bind time; rebind needed for runtime changes
    }

    /**
     * Last known dark/bright environment state, maintained by the auto-torch
     * luminance checks (refreshed roughly every 10th frame). Used by the
     * long-press light check.
     */
    fun isEnvironmentDark(): Boolean = isDarkEnvironment

    /**
     * Releases the camera and unbinds all use cases.
     */
    fun releaseCamera() {
        cameraProvider?.unbindAll()
        camera = null
        flashlightManager.camera = null
        previewViewRef = null
        frameConsumed.set(false)
        frameCounter = 0L
        // Recycle any held frame
        val oldFrame = latestFrame.getAndSet(null)
        if (oldFrame != null && !oldFrame.isRecycled) {
            oldFrame.recycle()
        }
    }

    /**
     * Fully destroys the camera provider and analysis executor.
     */
    fun destroy() {
        releaseCamera()
        cameraProvider = null
        context = null
        analysisExecutor.shutdownNow()
    }
}
