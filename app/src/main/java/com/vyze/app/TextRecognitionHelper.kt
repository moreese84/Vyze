package com.vyze.app

import android.graphics.Bitmap
import android.media.Image
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Helper class for on-device OCR text recognition using ML Kit.
 *
 * Initializes a TextRecognizer client and provides a function to process
 * CameraX ImageProxy frames, extracting recognized text blocks.
 *
 * **Memory management:** The intermediate [Bitmap] created from the ImageProxy
 * is explicitly [Bitmap.recycle]d after ML Kit has consumed it, preventing
 * frame-to-frame bitmap accumulation.
 */
class TextRecognitionHelper {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Processes an [ImageProxy] frame for text recognition.
     *
     * The CameraX pipeline is configured with RGBA_8888 output, so we convert
     * the image to a Bitmap and use [InputImage.fromBitmap] instead of
     * [InputImage.fromMediaImage] (which requires a YUV MediaImage that is
     * unavailable in RGBA mode).
     *
     * @param imageProxy The camera frame to analyze. Always closed in completion.
     * @param onSuccess  Callback with the concatenated recognized text (or a
     *                   "No text detected" message when empty).
     * @param onError    Callback invoked when recognition fails.
     */
    @OptIn(ExperimentalGetImage::class)
    fun processImageProxy(
        imageProxy: ImageProxy,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val mediaImage: Image? = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            onError(IllegalStateException("ImageProxy contains no image"))
            return
        }

        var bitmap: Bitmap? = null
        try {
            // Convert the RGBA_8888 ImageProxy to an InputImage via Bitmap
            bitmap = imageProxyToBitmap(imageProxy)

            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val inputImage = InputImage.fromBitmap(bitmap, rotationDegrees)

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val rawText = visionText.textBlocks.joinToString("\n\n") { block ->
                        block.lines.joinToString("\n") { it.text }
                    }

                    val cleanedText = rawText
                        .replace(Regex("\\n{3,}"), "\n\n")  // collapse excess newlines
                        .trim()

                    val resultText = if (cleanedText.isBlank()) {
                        "No text detected"
                    } else {
                        cleanedText
                    }

                    onSuccess(resultText)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Text recognition failed", e)
                    onError(e)
                }
                .addOnCompleteListener {
                    // Explicitly recycle bitmap to prevent frame-to-frame memory accumulation
                    bitmap?.let {
                        if (!it.isRecycled) {
                            it.recycle()
                        }
                    }
                    // Always close imageProxy to prevent memory leaks
                    imageProxy.close()
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image for text recognition", e)
            // Explicitly recycle bitmap on error path too
            bitmap?.let {
                if (!it.isRecycled) {
                    it.recycle()
                }
            }
            imageProxy.close()
            onError(e)
        }
    }

    /**
     * Converts a CameraX [ImageProxy] (RGBA_8888 format) to an Android [Bitmap].
     *
     * Since the pipeline uses [ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888],
     * the plane buffer contains raw RGBA pixel data. We create an ARGB_8888
     * Bitmap and copy the pixels directly.
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val buffer = imageProxy.planes[0].buffer
        val bitmap = Bitmap.createBitmap(
            imageProxy.width,
            imageProxy.height,
            Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    /**
     * Releases the ML Kit text recognizer resources.
     * Call this during lifecycle teardown (e.g., onDestroyView).
     */
    fun close() {
        recognizer.close()
    }

    companion object {
        private const val TAG = "TextRecognitionHelper"
    }
}
