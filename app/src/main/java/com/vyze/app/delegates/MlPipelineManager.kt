package com.vyze.app.delegates

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.vyze.app.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates all ML inference pipelines on shared camera frames.
 *
 * Extracted from CameraFragment to keep the fragment focused on lifecycle
 * and to make the ML pipeline testable in isolation.
 *
 * ## Pipeline Flow
 * 1. LuminanceAnalyzer processes every frame (for auto-torch).
 * 2. Frame throttling: only 1 of every [FRAME_SKIP_RATIO] frames enters ML.
 * 3. A shared bitmap is extracted and passed to:
 *    - BarcodeAnalyzer (async)
 *    - FaceDetectorHelper (async)
 *    - TextRecognitionHelper (on OCR request) OR ObjectDetectorHelper (default)
 * 4. CurrencyAnalyzer intercepts OCR results for currency denominations.
 *
 * ## Memory Safety
 * - AtomicBoolean prevents concurrent frame processing.
 * - Bitmap is recycled after all pipelines complete.
 * - ImageProxy is closed immediately after bitmap extraction.
 */
class MlPipelineManager(
    private val context: Context,
    private val backgroundExecutor: ExecutorService
) {

    private val TAG = "MlPipelineManager"

    // ── Pipeline Components ───────────────────────────────────────────

    lateinit var textRecognitionHelper: TextRecognitionHelper
    lateinit var barcodeAnalyzer: BarcodeAnalyzer
    lateinit var faceDetectorHelper: FaceDetectorHelper
    lateinit var currencyAnalyzer: CurrencyAnalyzer
    lateinit var objectDetectorHelper: ObjectDetectorHelper

    // ── Frame Processing Flags ────────────────────────────────────────

    private val isAnalyzing = AtomicBoolean(false)
    private val frameCounter = AtomicInteger(0)
    private val ocrRequested = AtomicBoolean(false)

    // ── Luminance State ───────────────────────────────────────────────

    @Volatile var latestMeanLuminance: Double = 0.0
        private set
    @Volatile var latestIsDark: Boolean = false
        private set

    // ── Callbacks ─────────────────────────────────────────────────────

    /** Called when luminance changes. */
    var onLuminanceChanged: (isDark: Boolean, meanLuminance: Double) -> Unit = { _, _ -> }

    /** Called when barcode/QR is detected. */
    var onBarcodeDetected: (announcements: List<String>) -> Unit = { _ -> }

    /** Called when face is detected. */
    var onFaceDetected: (announcements: List<String>) -> Unit = { _ -> }

    /** Called when OCR completes. */
    var onOcrComplete: (recognizedText: String, finalText: String) -> Unit = { _, _ -> }

    /** Called when OCR fails. */
    var onOcrFailed: (error: Exception) -> Unit = { _ -> }

    /** Called when OD completes. */
    var onOdComplete: (resultBundle: ObjectDetectorHelper.ResultBundle) -> Unit = { _ -> }

    // ── Initialization ────────────────────────────────────────────────

    /**
     * Initializes all ML pipeline components.
     * Must be called on a background thread.
     */
    fun initialize(
        context: Context,
        threshold: Float,
        delegate: Int,
        model: Int,
        maxResults: Int,
        detectorListener: ObjectDetectorHelper.DetectorListener
    ) {
        textRecognitionHelper = TextRecognitionHelper()
        barcodeAnalyzer = BarcodeAnalyzer()
        faceDetectorHelper = FaceDetectorHelper()
        currencyAnalyzer = CurrencyAnalyzer()

        objectDetectorHelper = ObjectDetectorHelper(
            context = context,
            threshold = threshold,
            currentDelegate = delegate,
            currentModel = model,
            maxResults = maxResults,
            objectDetectorListener = detectorListener,
            runningMode = com.google.mediapipe.tasks.vision.core.RunningMode.LIVE_STREAM
        )
    }

    // ── Frame Processing ──────────────────────────────────────────────

    /**
     * Creates the composite ImageAnalysis.Analyzer that processes all ML
     * pipelines on each camera frame.
     */
    fun createCompositeAnalyzer(): ImageAnalysis.Analyzer {
        val luminanceAnalyzer = LuminanceAnalyzer { isDark, meanLuminance ->
            latestIsDark = isDark
            latestMeanLuminance = meanLuminance
            onLuminanceChanged(isDark, meanLuminance)
        }

        return ImageAnalysis.Analyzer { imageProxy ->
            // Always analyze luminance (every frame for accurate auto-torch)
            luminanceAnalyzer.analyzeLuminance(imageProxy)

            // Frame skip for ML pipelines
            val count = frameCounter.incrementAndGet()
            if (count % FRAME_SKIP_RATIO != 0) {
                imageProxy.close()
                return@Analyzer
            }

            // Prevent concurrent processing
            if (!isAnalyzing.compareAndSet(false, true)) {
                imageProxy.close()
                return@Analyzer
            }

            var sharedBitmap: Bitmap? = null
            try {
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                sharedBitmap = Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
                imageProxy.use {
                    sharedBitmap!!.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
                }
                imageProxy.close()

                // Run barcode + face in parallel
                val latch = CountDownLatch(2)

                barcodeAnalyzer.processBitmap(
                    sharedBitmap, rotationDegrees,
                    onSuccess = { announcements ->
                        if (announcements.isNotEmpty()) {
                            onBarcodeDetected(announcements)
                        }
                        latch.countDown()
                    },
                    onError = { latch.countDown() }
                )

                faceDetectorHelper.processBitmap(
                    sharedBitmap, rotationDegrees,
                    onSuccess = { announcements ->
                        if (announcements.isNotEmpty()) {
                            onFaceDetected(announcements)
                        }
                        latch.countDown()
                    },
                    onError = { latch.countDown() }
                )

                latch.await(3, TimeUnit.SECONDS)

                // Route to OCR or OD
                if (ocrRequested.compareAndSet(true, false)) {
                    processOcr(sharedBitmap, rotationDegrees)
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

    /**
     * Processes OCR on the given bitmap and routes through CurrencyAnalyzer.
     */
    private fun processOcr(bitmap: Bitmap, rotationDegrees: Int) {
        textRecognitionHelper.processBitmap(
            bitmap, rotationDegrees,
            onSuccess = { recognizedText ->
                val currencyAnnouncement = currencyAnalyzer.analyzeSingle(recognizedText)
                val finalText = currencyAnnouncement ?: recognizedText
                onOcrComplete(recognizedText, finalText)
                isAnalyzing.set(false)
            },
            onError = { error ->
                Log.e(TAG, "OCR processing failed", error)
                onOcrFailed(error)
                isAnalyzing.set(false)
            }
        )
    }

    // ── Public Controls ───────────────────────────────────────────────

    /** Returns true if the ML pipeline and ObjectDetectorHelper have been initialized. */
    fun isOdInitialized(): Boolean = ::objectDetectorHelper.isInitialized

    /** Request OCR on the next processed frame. */
    fun requestOcr() {
        ocrRequested.set(true)
    }

    /** Check if OCR is currently requested. */
    fun isOcrRequested(): Boolean = ocrRequested.get()

    /** Reset all cooldowns and debounce states. */
    fun resetCooldowns() {
        objectDetectorHelper.clearDebounceState()
        currencyAnalyzer.clearCooldowns()
    }

    /** Clear all flags and counters. */
    fun reset() {
        isAnalyzing.set(false)
        ocrRequested.set(false)
        frameCounter.set(0)
    }

    // ── Cleanup ───────────────────────────────────────────────────────

    /**
     * Releases all ML resources. Must be called on background thread.
     */
    fun shutdown() {
        if (::textRecognitionHelper.isInitialized) textRecognitionHelper.close()
        if (::barcodeAnalyzer.isInitialized) barcodeAnalyzer.close()
        if (::faceDetectorHelper.isInitialized) faceDetectorHelper.close()
        if (::objectDetectorHelper.isInitialized) objectDetectorHelper.clearObjectDetector()
        reset()
    }

    /**
     * Clears OD detector for model/delegate changes.
     */
    fun reinitializeOd() {
        if (::objectDetectorHelper.isInitialized) {
            objectDetectorHelper.clearObjectDetector()
            objectDetectorHelper.setupObjectDetector()
        }
    }

    companion object {
        /** Process 1 of every N frames for ML (battery optimization). */
        const val FRAME_SKIP_RATIO = 3
    }
}
