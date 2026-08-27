package com.vyze.app.delegates

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Extracts an ARGB_8888 [Bitmap] from a CameraX [ImageProxy].
 *
 * Extracted from [MlPipelineManager] to isolate the critical frame
 * extraction logic from pipeline orchestration. This class has NO
 * dependency on ML engines, TTS, or UI state.
 *
 * ## Responsibilities
 * 1. Rewind the image buffer before reading (prevents blank/corrupted bitmaps)
 * 2. Fast path: contiguous RGBA data → `copyPixelsFromBuffer`
 * 3. Slow path: stride-aware copy for devices with row padding
 * 4. Safety check: skip frames smaller than 32×32
 *
 * ## Thread Safety
 * The returned Bitmap is a fresh allocation — safe to pass to multiple
 * ML engines concurrently (after copying). The ImageProxy is closed
 * before returning.
 *
 * ## Usage
 * ```kotlin
 * val extractor = FrameExtractor()
 * val bitmap = extractor.extract(imageProxy)
 * if (bitmap != null) {
 *     // process bitmap...
 *     bitmap.recycle()
 * }
 * ```
 */
class FrameExtractor {

    /**
     * Extract a Bitmap from an ImageProxy.
     *
     * @param imageProxy  The CameraX frame to extract from.
     * @return An ARGB_8888 Bitmap, or null if the frame is too small
     *         or extraction fails. The ImageProxy is always closed.
     */
    fun extract(imageProxy: ImageProxy): Bitmap? {
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        val planes = imageProxy.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val w = imageProxy.width
        val h = imageProxy.height

        var bitmap: Bitmap? = null
        try {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

            buffer.rewind()
            val expectedSize = w * h * 4
            if (buffer.remaining() >= expectedSize && rowStride == w * pixelStride) {
                // Fast path: contiguous RGBA data, no row padding
                bitmap.copyPixelsFromBuffer(buffer)
            } else {
                // Slow path: stride-aware copy for devices with row padding
                val pixels = IntArray(w * h)
                for (row in 0 until h) {
                    buffer.position(row * rowStride)
                    for (col in 0 until w) {
                        val bufIdx = row * rowStride + col * pixelStride
                        val r = buffer.get(bufIdx).toInt() and 0xFF
                        val g = buffer.get(bufIdx + 1).toInt() and 0xFF
                        val b = buffer.get(bufIdx + 2).toInt() and 0xFF
                        val a = buffer.get(bufIdx + 3).toInt() and 0xFF
                        pixels[row * w + col] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame extraction failed: ${e.message}", e)
            bitmap?.recycle()
            bitmap = null
        } finally {
            imageProxy.close()
        }

        // Safety check: skip frames that are too small for ML inference
        if (bitmap != null && (bitmap.width < MIN_SIZE || bitmap.height < MIN_SIZE)) {
            Log.d(TAG, "Frame too small: ${bitmap.width}x${bitmap.height}")
            bitmap.recycle()
            return null
        }

        return bitmap
    }

    /**
     * Get the rotation degrees from an ImageProxy without extracting pixels.
     * Useful when callers need to know rotation for downstream processing.
     */
    fun getRotationDegrees(imageProxy: ImageProxy): Int {
        return imageProxy.imageInfo.rotationDegrees
    }

    companion object {
        private const val TAG = "FrameExtractor"

        /** Minimum frame dimension (width or height) for ML inference. */
        private const val MIN_SIZE = 32
    }
}
