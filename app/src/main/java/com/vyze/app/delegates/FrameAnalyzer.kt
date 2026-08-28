package com.vyze.app.delegates

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.vyze.app.ObjectDetectorHelper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production-grade frame analyzer for the Vyze camera pipeline.
 *
 * ## Threading contract
 * - CameraX delivers frames on a single background executor.
 * - [isProcessing] is an AtomicBoolean gate: if already true, the frame
 *   is immediately closed and dropped — zero queuing, zero memory pressure.
 * - When the gate is acquired, the full pipeline runs synchronously.
 *
 * ## ImageProxy lifecycle (CRITICAL)
 * - [imageProxy.close()], [Bitmap.recycle()], and [isProcessing.set(false)]
 *   are ALL inside a strict **finally** block that executes regardless of
 *   exceptions in extraction, inference, or callbacks.
 * - This prevents CameraX from stalling due to unclosed proxies.
 */
class FrameAnalyzer(
    private val objectDetectorHelper: ObjectDetectorHelper,
    private val frameSkipRatio: Int = 3
) : ImageAnalysis.Analyzer {

    private val TAG = "FrameAnalyzer"

    /** Atomic gate — prevents frame queuing. */
    private val isProcessing = AtomicBoolean(false)

    /** Monotonically increasing frame counter. */
    @Volatile private var frameCount = 0L

    /** Frame skip counter (modulo frameSkipRatio). */
    private var skipCounter = 0

    // ── Callbacks ─────────────────────────────────────────────────

    /** Called on every analyzed frame with the result (even if empty). */
    var onResult: (ObjectDetectorHelper.ResultBundle) -> Unit = {}

    /** Called when a frame is dropped. */
    var onFrameDropped: (frameNum: Long) -> Unit = {}

    /** Called with diagnostic messages. */
    var onDiagnostic: (String) -> Unit = {}

    // ── Analyzer Implementation ───────────────────────────────────

    override fun analyze(imageProxy: ImageProxy) {
        // ── Frame skipping ────────────────────────────────────────
        skipCounter++
        if (skipCounter % frameSkipRatio != 0) {
            imageProxy.close()
            return
        }

        val frameNum = ++frameCount

        // ── Gate: drop if previous frame still processing ─────────
        if (!isProcessing.compareAndSet(false, true)) {
            onFrameDropped(frameNum)
            imageProxy.close()
            return
        }

        // ── Full pipeline with GUARANTEED cleanup in finally ──────
        var frameBitmap: Bitmap? = null
        try {
            // 1. Read metadata BEFORE closing proxy
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            // 2. Extract bitmap from proxy's pixel buffer
            frameBitmap = extractBitmap(imageProxy)

            if (frameBitmap == null) {
                onDiagnostic("Frame #$frameNum: null bitmap")
                return
            }

            Log.d(TAG, "Frame #$frameNum: ${frameBitmap.width}x${frameBitmap.height} rot=$rotationDegrees")

            // 3. Rotate to portrait
            val portrait = rotateToPortrait(frameBitmap, rotationDegrees)
            val portraitW = portrait.width
            val portraitH = portrait.height

            // 4. Letterbox to square for YOLO
            val letterboxed = letterboxToSquare(portrait)

            // 5. Run YOLO inference
            val result = objectDetectorHelper.detect(
                bitmap = letterboxed,
                originalWidth = portraitW,
                originalHeight = portraitH
            )

            // 6. Recycle intermediates (frameBitmap recycled in finally)
            if (letterboxed !== portrait && !letterboxed.isRecycled) letterboxed.recycle()
            if (portrait !== frameBitmap && !portrait.isRecycled) portrait.recycle()

            // 7. Callback
            onResult(result)
            onDiagnostic("OD: ${result.detections.size} det, ${result.inferenceTime}ms")

        } catch (e: Exception) {
            Log.e(TAG, "Frame #$frameNum error: ${e.message}", e)
            onDiagnostic("OD ERROR: ${e.message}")
        } finally {
            // ── ALWAYS: close proxy, recycle frame, release gate ──
            try { imageProxy.close() } catch (_: Exception) {}
            frameBitmap?.let { if (!it.isRecycled) it.recycle() }
            isProcessing.set(false)
        }
    }

    // ── ImageProxy → Bitmap ───────────────────────────────────────

    private fun extractBitmap(imageProxy: ImageProxy): Bitmap? {
        val w = imageProxy.width
        val h = imageProxy.height
        if (w < MIN_FRAME_SIZE || h < MIN_FRAME_SIZE) return null

        val planes = imageProxy.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buffer.rewind()

        val expected = w * h * 4
        if (buffer.remaining() >= expected && rowStride == w * pixelStride) {
            bitmap.copyPixelsFromBuffer(buffer)
        } else {
            val pixels = IntArray(w * h)
            for (row in 0 until h) {
                buffer.position(row * rowStride)
                for (col in 0 until w) {
                    val idx = row * rowStride + col * pixelStride
                    val r = buffer.get(idx).toInt() and 0xFF
                    val g = buffer.get(idx + 1).toInt() and 0xFF
                    val b = buffer.get(idx + 2).toInt() and 0xFF
                    val a = buffer.get(idx + 3).toInt() and 0xFF
                    pixels[row * w + col] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        }
        return bitmap
    }

    // ── Image Transformations ─────────────────────────────────────

    private fun rotateToPortrait(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun letterboxToSquare(source: Bitmap): Bitmap {
        val srcW = source.width
        val srcH = source.height
        if (srcW == srcH) return source

        val side = maxOf(srcW, srcH)
        val result = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.BLACK)
        val left = (side - srcW) / 2
        val top = (side - srcH) / 2
        canvas.drawBitmap(source, null, android.graphics.Rect(left, top, left + srcW, top + srcH), null)
        return result
    }

    // ── Control ───────────────────────────────────────────────────

    fun isCurrentlyProcessing(): Boolean = isProcessing.get()

    fun reset() {
        isProcessing.set(false)
        frameCount = 0L
        skipCounter = 0
    }

    companion object {
        private const val MIN_FRAME_SIZE = 32
    }
}
