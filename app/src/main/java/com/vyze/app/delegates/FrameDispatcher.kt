package com.vyze.app.delegates

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import com.vyze.app.BarcodeAnalyzer
import com.vyze.app.FaceDetectorHelper
import com.vyze.app.ObjectDetectorHelper
import com.vyze.app.TextRecognitionHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Routes camera frame bitmaps to the appropriate ML engines.
 *
 * Extracted from [MlPipelineManager] to separate frame dispatch logic
 * from pipeline state management. This class has NO dependency on:
 * - CameraX ImageAnalysis lifecycle
 * - UI state or Fragment
 * - TTS or Haptics
 *
 * ## Responsibilities
 * 1. Create independent bitmap copies for each ML engine (prevents
 *    concurrent unsynchronized reads of the same native pixel buffer)
 * 2. Run barcode + face detection in parallel with a timeout
 * 3. Route to OCR or object detection based on request flags
 * 4. Apply rotation + letterboxing before object detection
 *
 * ## Memory Safety
 * - Each ML engine receives its own bitmap copy
 * - The original frame bitmap is NOT recycled here (caller manages it)
 * - Rotated + letterboxed copies are recycled after inference
 */
class FrameDispatcher(
    private val barcodeAnalyzer: BarcodeAnalyzer,
    private val faceDetectorHelper: FaceDetectorHelper,
    private val objectDetectorHelper: ObjectDetectorHelper,
    private val textRecognitionHelper: TextRecognitionHelper,
    private val backgroundExecutor: ExecutorService
) {

    private val TAG = "FrameDispatcher"

    // ── Callbacks ─────────────────────────────────────────────────

    var onBarcodeDetected: (announcements: List<String>) -> Unit = { _ -> }
    var onFaceDetected: (announcements: List<String>) -> Unit = { _ -> }
    var onOcrComplete: (recognizedText: String, finalText: String) -> Unit = { _, _ -> }
    var onOcrFailed: (error: Exception) -> Unit = { _ -> }
    var onDiagnosticUpdate: ((String) -> Unit)? = null

    // ── Core Dispatch ─────────────────────────────────────────────

    /**
     * Dispatch a frame bitmap to all ML engines.
     *
     * The caller provides:
     * - A fresh bitmap from [FrameExtractor]
     * - The rotation degrees from the original ImageProxy
     * - Whether OCR was requested
     *
     * This method handles:
     * - Creating independent copies for barcode, face, and OD/OCR
     * - Running barcode + face in parallel (3s timeout)
     * - Routing the OD copy to either OCR or object detection
     * - Applying rotation + letterboxing for OD
     *
     * @param frameBitmap      The extracted frame bitmap (caller retains ownership).
     * @param rotationDegrees  Sensor rotation from ImageProxy.
     * @param ocrRequested     If true, route the OD copy to OCR instead.
     * @param onComplete       Called when all engines finish (OD pipeline complete).
     */
    fun dispatch(
        frameBitmap: Bitmap,
        rotationDegrees: Int,
        ocrRequested: Boolean,
        onComplete: () -> Unit
    ) {
        var barcodeBitmap: Bitmap? = null
        var faceBitmap: Bitmap? = null
        var odBitmap: Bitmap? = null

        try {
            // ── Independent bitmap copies ─────────────────────────
            barcodeBitmap = frameBitmap.copy(Bitmap.Config.ARGB_8888, false)
            faceBitmap    = frameBitmap.copy(Bitmap.Config.ARGB_8888, false)
            odBitmap      = frameBitmap.copy(Bitmap.Config.ARGB_8888, false)

            // Run barcode + face in parallel (3s timeout)
            val latch = CountDownLatch(2)

            barcodeAnalyzer.processBitmap(
                barcodeBitmap, 0,
                onSuccess = { announcements ->
                    if (announcements.isNotEmpty()) {
                        onBarcodeDetected(announcements)
                    }
                    latch.countDown()
                },
                onError = { latch.countDown() }
            )

            faceDetectorHelper.processBitmap(
                faceBitmap, 0,
                onSuccess = { announcements ->
                    if (announcements.isNotEmpty()) {
                        onFaceDetected(announcements)
                    }
                    latch.countDown()
                },
                onError = { latch.countDown() }
            )

            latch.await(3, TimeUnit.SECONDS)

            // Route to OCR or OD (uses its own independent copy)
            if (ocrRequested) {
                processOcr(odBitmap)
            } else {
                processOd(odBitmap, rotationDegrees)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame dispatch error", e)
            onComplete()
        } finally {
            barcodeBitmap?.let { if (!it.isRecycled) it.recycle() }
            faceBitmap?.let { if (!it.isRecycled) it.recycle() }
            odBitmap?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    // ── OD Pipeline ───────────────────────────────────────────────

    private fun processOd(bitmap: Bitmap, rotationDegrees: Int) {
        var portrait: Bitmap? = null
        var letterboxed: Bitmap? = null
        try {
            // Rotate to portrait THEN letterbox — fresh bitmaps each time
            portrait = rotateToPortrait(bitmap, rotationDegrees)
            val portraitW = portrait.width
            val portraitH = portrait.height
            letterboxed = letterboxBitmap(portrait)

            Log.d(TAG, "OD input: portrait=${portrait.width}x${portrait.height}, " +
                "letterboxed=${letterboxed.width}x${letterboxed.height}")

            val frameTime = SystemClock.uptimeMillis()
            val resultBundle = objectDetectorHelper.detectLivestreamBitmap(
                letterboxed, frameTime,
                originalWidth = portraitW,
                originalHeight = portraitH
            )

            val detCount = resultBundle.detections.size
            Log.d(TAG, "OD: $detCount detections in ${resultBundle.inferenceTime}ms")
            onDiagnosticUpdate?.invoke("OD: $detCount det, ${resultBundle.inferenceTime}ms")

            // Post to main thread so the overlay and UI update correctly
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                objectDetectorHelper.objectDetectorListener?.onResults(resultBundle)
            }
        } catch (e: Exception) {
            Log.e(TAG, "OD error: ${e.javaClass.simpleName}: ${e.message}", e)
            onDiagnosticUpdate?.invoke("OD ERROR: ${e.message}")
        } finally {
            // Recycle AFTER detectLivestreamBitmap has fully returned
            // (the ByteBuffer snapshot is independent of these Bitmaps)
            portrait?.let { if (!it.isRecycled) it.recycle() }
            letterboxed?.let { if (!it.isRecycled && it !== portrait) it.recycle() }
        }
    }

    // ── OCR Pipeline ──────────────────────────────────────────────

    private fun processOcr(bitmap: Bitmap) {
        textRecognitionHelper.processBitmap(
            bitmap, 0,
            onSuccess = { recognizedText -> onOcrComplete(recognizedText, recognizedText) },
            onError = { error ->
                Log.e(TAG, "OCR processing failed", error)
                onOcrFailed(error)
            }
        )
    }

    // ── Image Transformations ─────────────────────────────────────

    /**
     * Rotates a bitmap to upright portrait orientation.
     * CameraX delivers frames in sensor orientation (typically landscape
     * for portrait-held phones).
     */
    private fun rotateToPortrait(source: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return source
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Letterboxes a bitmap into a square canvas by scaling it to fit
     * while preserving aspect ratio. Black padding fills the remaining space.
     */
    private fun letterboxBitmap(source: Bitmap): Bitmap {
        val srcW = source.width
        val srcH = source.height
        if (srcW == srcH) return source

        val side = maxOf(srcW, srcH)
        val scale = side.toFloat() / maxOf(srcW, srcH)
        val scaledW = (srcW * scale).toInt()
        val scaledH = (srcH * scale).toInt()

        val letterboxed = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxed)
        canvas.drawColor(Color.BLACK)

        val left = (side - scaledW) / 2
        val top = (side - scaledH) / 2
        val destRect = android.graphics.Rect(left, top, left + scaledW, top + scaledH)
        canvas.drawBitmap(source, null, destRect, null)

        return letterboxed
    }
}
