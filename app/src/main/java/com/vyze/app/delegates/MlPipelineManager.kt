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
 * Thin lifecycle/state manager that delegates:
 * - Frame extraction -> [FrameExtractor]
 * - Frame dispatch to ML engines -> [FrameDispatcher]
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

    private lateinit var frameExtractor: FrameExtractor
    private lateinit var frameDispatcher: FrameDispatcher

    private val isAnalyzing = AtomicBoolean(false)
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
    var onOdComplete: (resultBundle: ObjectDetectorHelper.ResultBundle) -> Unit = { _ -> }
    var onModelsReady: () -> Unit = {}
    var onDiagnosticUpdate: ((String) -> Unit)? = null

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
            maxResults = maxResults,
            objectDetectorListener = detectorListener
        )

        frameExtractor = FrameExtractor()
        frameDispatcher = FrameDispatcher(
            barcodeAnalyzer = barcodeAnalyzer,
            faceDetectorHelper = faceDetectorHelper,
            objectDetectorHelper = objectDetectorHelper,
            textRecognitionHelper = textRecognitionHelper,
            backgroundExecutor = backgroundExecutor
        )

        frameDispatcher.onBarcodeDetected = onBarcodeDetected
        frameDispatcher.onFaceDetected = onFaceDetected
        frameDispatcher.onOcrComplete = { recognizedText, finalText ->
            val currencyAnnouncement = currencyAnalyzer.analyzeSingle(recognizedText)
            val finalTextWithCurrency = currencyAnnouncement ?: finalText
            onOcrComplete(recognizedText, finalTextWithCurrency)
        }
        frameDispatcher.onOcrFailed = onOcrFailed
        frameDispatcher.onDiagnosticUpdate = onDiagnosticUpdate

        onModelsReady()
    }

    fun createCompositeAnalyzer(): ImageAnalysis.Analyzer {
        val luminanceAnalyzer = LuminanceAnalyzer { isDark, meanLuminance ->
            latestIsDark = isDark
            latestMeanLuminance = meanLuminance
            onLuminanceChanged(isDark, meanLuminance)
        }

        return ImageAnalysis.Analyzer { imageProxy ->
            luminanceAnalyzer.analyzeLuminance(imageProxy)

            val count = frameCounter.incrementAndGet()
            if (count % FRAME_SKIP_RATIO != 0) {
                imageProxy.close()
                return@Analyzer
            }

            if (!isAnalyzing.compareAndSet(false, true)) {
                Log.d(TAG, "Frame dropped: isAnalyzing=true")
                imageProxy.close()
                return@Analyzer
            }

            val rotationDegrees = frameExtractor.getRotationDegrees(imageProxy)
            val frameBitmap = frameExtractor.extract(imageProxy)

            if (frameBitmap == null) {
                isAnalyzing.set(false)
                return@Analyzer
            }

            try {
                frameDispatcher.dispatch(
                    frameBitmap = frameBitmap,
                    rotationDegrees = rotationDegrees,
                    ocrRequested = ocrRequested.compareAndSet(true, false),
                    onComplete = { isAnalyzing.set(false) }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Composite analyzer error", e)
                isAnalyzing.set(false)
            } finally {
                if (!frameBitmap.isRecycled) frameBitmap.recycle()
            }
        }
    }

    fun isOdInitialized(): Boolean = ::objectDetectorHelper.isInitialized
    fun isCurrentlyAnalyzing(): Boolean = isAnalyzing.get()
    fun requestOcr() { ocrRequested.set(true) }
    fun isOcrRequested(): Boolean = ocrRequested.get()

    fun resetCooldowns() {
        currencyAnalyzer.clearCooldowns()
    }

    fun reset() {
        isAnalyzing.set(false)
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
        const val WATCHDOG_TIMEOUT_MS = 5000L
    }
}
