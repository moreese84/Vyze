package com.vyze.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Helper class for on-device OCR text recognition using ML Kit.
 *
 * Provides structured text hierarchy by:
 *  - **Noise filtering** — removes isolated symbols, single-character artifacts,
 *    pure non-alphanumeric text, and small floating noise (bbox area < 2% of frame).
 *  - **Structural sorting** — orders [Text.TextBlock]s by bounding-box area
 *    (largest / most prominent first) so headline text is read before secondary lines.
 *  - **Top-3 limiting** — caps spoken output to the 3 most prominent blocks to
 *    keep TTS concise for visually impaired users.
 *
 * **Memory safety:** Intermediate [Bitmap]s are explicitly recycled in both
 * success and failure paths; [ImageProxy] is always closed in `addOnCompleteListener`.
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
     * @param onSuccess  Callback with the structured, filtered text output.
     *                   Returns "No text detected" when no valid blocks remain.
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

            // Capture dimensions for noise filtering before ML Kit closes the image
            val imageWidth = imageProxy.width
            val imageHeight = imageProxy.height

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val structuredText = processVisionText(
                        visionText = visionText,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight
                    )

                    val resultText = if (structuredText.isBlank()) {
                        "No text detected"
                    } else {
                        structuredText
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

    // ── Noise Filtering & Structural Sorting ────────────────────────────────

    /**
     * Processes raw ML Kit [Text] output into a structured, noise-filtered
     * string suitable for TTS readout.
     *
     * Pipeline:
     *  1. **Filter blocks** — drop blocks that are pure non-alphanumeric noise,
     *     single-character artifacts, or isolated symbols.
     *  2. **Filter small noise** — drop blocks whose bounding-box area is < 2%
     *     of the total image area (floating noise artifacts).
     *  3. **Sort by prominence** — order remaining blocks by bounding-box area
     *     (descending) so headline text comes first.
     *  4. **Limit to top 3** — keep only the 3 most prominent blocks for concise TTS.
     *  5. **Format output** — headline block first, then secondary lines separated
     *     by "Next, " for natural TTS pacing.
     *
     * @param visionText  The raw ML Kit recognition result.
     * @param imageWidth  Width of the source image in pixels.
     * @param imageHeight Height of the source image in pixels.
     * @return A formatted string ready for TTS, or empty if nothing valid remains.
     */
    fun processVisionText(
        visionText: Text,
        imageWidth: Int,
        imageHeight: Int
    ): String {
        val totalImageArea = imageWidth.toLong() * imageHeight.toLong()
        if (totalImageArea <= 0) return ""

        val blocks = visionText.textBlocks

        // Step 1 & 2: Filter noise blocks
        val cleanBlocks = blocks.filter { block ->
            isNotNoise(block) && !isSmallFloatingNoise(block, totalImageArea)
        }

        if (cleanBlocks.isEmpty()) return ""

        // Step 3: Sort by bounding-box area (largest first = most prominent)
        val sortedBlocks = cleanBlocks.sortedByDescending { block ->
            blockArea(block)
        }

        // Step 4: Limit to top 3 most prominent blocks
        val topBlocks = sortedBlocks.take(MAX_PROMINENT_BLOCKS)

        // Step 5: Format output with structural hierarchy
        return formatStructuredOutput(topBlocks)
    }

    /**
     * Determines whether a [Text.TextBlock] is NOT noise.
     *
     * A block is considered noise if:
     *  - Its trimmed text is blank
     *  - It contains only a single character
     *  - It consists entirely of non-alphanumeric symbols (regex: no word characters)
     */
    private fun isNotNoise(block: Text.TextBlock): Boolean {
        val text = block.text.trim()

        // Empty or blank
        if (text.isEmpty()) return false

        // Single-character artifact (e.g. stray "|", ".", "1", "!")
        if (text.length == 1) return false

        // Pure non-alphanumeric noise (e.g. "---", "///", "***", "...")
        // A block passes if it contains at least one word character (letter or digit)
        if (!text.any { it.isLetterOrDigit() }) return false

        return true
    }

    /**
     * Determines whether a [Text.TextBlock] is a small floating noise artifact.
     *
     * Noise artifacts are typically tiny text fragments detected at edges or in
     * texture. Their bounding-box area is < 2% of the total image area.
     */
    private fun isSmallFloatingNoise(block: Text.TextBlock, totalImageArea: Long): Boolean {
        val box = block.boundingBox ?: return true // null bbox = treat as noise
        val boxArea = box.width().toLong() * box.height().toLong()
        val fraction = boxArea.toFloat() / totalImageArea.toFloat()
        return fraction < NOISE_AREA_THRESHOLD
    }

    /**
     * Returns the bounding-box area of a [Text.TextBlock].
     * Falls back to 0 if the bounding box is null.
     */
    private fun blockArea(block: Text.TextBlock): Long {
        val box = block.boundingBox ?: return 0L
        return box.width().toLong() * box.height().toLong()
    }

    /**
     * Formats the top blocks into a structured string for TTS.
     *
     * The first block is the "headline" (largest / most prominent).
     * Subsequent blocks are introduced with "Next, " to signal a transition
     * to the listener.
     *
     * Example output:
     * ```
     * OPEN HOUSE SATURDAY 10AM TO 4PM
     * Next, 123 Main Street, Springfield
     * Next, Call 555-0123 for info
     * ```
     */
    private fun formatStructuredOutput(blocks: List<Text.TextBlock>): String {
        if (blocks.isEmpty()) return ""

        val parts = mutableListOf<String>()

        blocks.forEachIndexed { index, block ->
            // Clean block text: collapse internal newlines into single line,
            // trim whitespace
            val cleanText = block.text
                .replace(Regex("\\s+"), " ")
                .trim()

            if (cleanText.isNotEmpty()) {
                if (index == 0) {
                    parts.add(cleanText)
                } else {
                    parts.add("Next, $cleanText")
                }
            }
        }

        return parts.joinToString(". ")
    }

    /**
     * Processes a [Bitmap] for text recognition without needing an ImageProxy.
     * Used by the composite analyzer when the bitmap has already been extracted
     * from the imageProxy and shared across multiple analyzers.
     *
     * @param bitmap          The camera frame as a Bitmap (ARGB_8888).
     * @param rotationDegrees Rotation applied to the image (from ImageProxy.imageInfo).
     * @param onSuccess       Callback with the structured, filtered text output.
     * @param onError         Callback invoked when recognition fails.
     */
    fun processBitmap(
        bitmap: Bitmap,
        rotationDegrees: Int,
        onSuccess: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val inputImage = InputImage.fromBitmap(bitmap, rotationDegrees)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val structuredText = processVisionText(
                    visionText = visionText,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height
                )
                val resultText = if (structuredText.isBlank()) "No text detected" else structuredText
                onSuccess(resultText)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Text recognition failed", e)
                onError(e)
            }
    }

    // ── Bitmap Conversion ───────────────────────────────────────────────────

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

        /** Maximum number of text blocks to include in TTS output. */
        const val MAX_PROMINENT_BLOCKS = 3

        /**
         * Minimum fraction of total image area a text block's bounding box
         * must occupy to be considered real text (not floating noise).
         * 2% = 0.02f.
         */
        const val NOISE_AREA_THRESHOLD = 0.02f
    }
}
