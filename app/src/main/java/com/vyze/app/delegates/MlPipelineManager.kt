package com.vyze.app.delegates

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageAnalysis
import com.vyze.app.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates all ML inference pipelines on shared camera frames.
 *
 * Uses [FrameAnalyzer] for the core OD pipeline:
 * - AtomicBoolean frame locking (no queuing, no stalls)
 * - Guaranteed imageProxy.close() in finally block
 * - Fresh bitmap extraction per frame
 * - Deterministic ResultBundle callback on every frame
 */
class MlPipelineManager(
    private val context: Context,
    private val backgroundExecutor: ExecutorService
) {

    private val TAG = "MlPipelineManager"

    lateinit var textRecognitionHelper: TextRecognitionHelper
    lateinit var barcodeAnalyzer: BarcodeAnalyzer
    lateinit var faceDetectorHelper: FaceDetectorHelper
    lateinit var currencyAnalyzer: CurrencyAnalyzer
    lateinit var objectDetectorHelper: ObjectDetectorHelper

    private val frameCounter = AtomicInteger(0)
    private val ocrRequested = AtomicBoolean(false)

    @Volatile var latestMeanLuminance: Double = 0.0
        private set
    @Volatile var latestIsDark: Boolean = false
        private set

    var onLuminanceChanged: (isDark: Boolean, meanLuminance: Double) -> Unit = { _, _ -> }
    var onBarcodeDetected: (announcements: List<String>) -> Unit = { _ -> }
    var onFaceDetected: (announcements: List<String>) -> Unit = { _ -> }
    var onOcrComplete: (recognizedText: String, finalText: String) -> Unit = { _, _ -> }
    var onOcrFailed: (error: Exception) -> Unit = { _ -> }
    var onModelsReady: () -> Unit = {}
    var onDiagnosticUpdate: ((String) -> Unit)? = null

    fun initialize(
        context: Context,
        threshold: Float,
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
            maxResults = maxResults,
            objectDetectorListener = detectorListener
        )

        onModelsReady()
    }

    /**
     * Create the ImageAnalysis.Analyzer for CameraX.
     *
     * Delegates to [FrameAnalyzer] for the core OD pipeline.
     * Luminance analysis runs on every frame (including skipped ones).
     */
    fun createCompositeAnalyzer(): ImageAnalysis.Analyzer {
        val luminanceAnalyzer = LuminanceAnalyzer { isDark, meanLuminance ->
            latestIsDark = isDark
            latestMeanLuminance = meanLuminance
            onLuminanceChanged(isDark, meanLuminance)
        }

        val frameAnalyzer = FrameAnalyzer(
            objectDetectorHelper = objectDetectorHelper
        )

        frameAnalyzer.onResult = { resultBundle ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                objectDetectorHelper.objectDetectorListener?.onResults(resultBundle)
            }
        }

        frameAnalyzer.onDiagnostic = { msg ->
            onDiagnosticUpdate?.invoke(msg)
        }

        frameAnalyzer.onFrameDropped = { frameNum ->
            Log.d(TAG, "Frame #$frameNum dropped (processing)")
        }

        return ImageAnalysis.Analyzer { imageProxy ->
            luminanceAnalyzer.analyzeLuminance(imageProxy)
            frameCounter.incrementAndGet()
            frameAnalyzer.analyze(imageProxy)
        }
    }

    fun isOdInitialized(): Boolean = ::objectDetectorHelper.isInitialized
    fun isCurrentlyAnalyzing(): Boolean = frameCounter.get() > 0
    fun requestOcr() { ocrRequested.set(true) }
    fun isOcrRequested(): Boolean = ocrRequested.get()

    fun resetCooldowns() {
        currencyAnalyzer.clearCooldowns()
    }

    fun reset() {
        ocrRequested.set(false)
        frameCounter.set(0)
    }

    fun shutdown() {
        if (::textRecognitionHelper.isInitialized) textRecognitionHelper.close()
        if (::barcodeAnalyzer.isInitialized) barcodeAnalyzer.close()
        if (::faceDetectorHelper.isInitialized) faceDetectorHelper.close()
        if (::objectDetectorHelper.isInitialized) objectDetectorHelper.clearObjectDetector()
        reset()
    }

    fun reinitializeOd() {
        if (::objectDetectorHelper.isInitialized) {
            objectDetectorHelper.clearObjectDetector()
            objectDetectorHelper.setupObjectDetector()
        }
    }

    companion object {
        const val FRAME_SKIP_RATIO = 3
    }
}
