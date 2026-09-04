package com.vyze.app

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * On-device OCR helper using Google ML Kit Text Recognition.
 *
 * ## Supported Scripts
 * - **Latin**: English, Malay, Indonesian, Vietnamese, Turkish, European languages
 * - **Chinese**: Simplified + Traditional Chinese
 *
 * ## How It Works
 * ML Kit automatically detects the script in the image and routes to the
 * correct recognizer. No manual script selection needed.
 *
 * ## Latency
 * ~80-150ms on a mid-range phone — 10-30x faster than full VLM inference.
 *
 * ## Usage
 * Call [extractText] from a coroutine. Returns extracted text or null
 * if nothing was recognized.
 */
class OcrHelper {

    /**
     * Latin script recognizer — covers English, Malay, Indonesian,
     * Vietnamese, Turkish, Spanish, French, German, and most European languages.
     */
    private val latinRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.Builder().build())

    /**
     * Chinese script recognizer — covers Simplified and Traditional Chinese.
     */
    private val chineseRecognizer: TextRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    /**
     * Extract text from a camera bitmap using ML Kit on-device OCR.
     *
     * Runs BOTH the Latin (English/Malay/…) and Chinese recognizers and merges
     * their lines in reading order — required for packaging that mixes scripts
     * in one sentence. See [extractMerged] for the merge rules.
     *
     * @param bitmap Camera frame — will NOT be recycled (caller manages lifecycle)
     * @return Extracted text string, or null if nothing was recognized
     */
    suspend fun extractText(bitmap: Bitmap): String? = extractMerged(bitmap)?.first

    /**
     * Extract text with confidence metadata.
     * Returns a pair of (text, confidence) where confidence is 0.0-1.0.
     * Useful for deciding whether to fall back to Gemma for interpretation.
     */
    suspend fun extractTextWithConfidence(bitmap: Bitmap): Pair<String?, Float> = extractMerged(bitmap)

    /**
     * OCR with the LATIN (English/Malay/…) AND CHINESE recognizers BOTH always
     * running, then merged line-by-line in reading order.
     *
     * Product packaging frequently mixes scripts — Malay/English and Chinese in
     * the SAME sentence ("Sila ambil 1 bungkus 请服用一包"). The old Latin-first
     * logic stopped at the first Latin result and silently dropped the Chinese
     * half of such labels. Both recognizers now contribute, and a line is kept
     * only if it actually belongs to that recognizer's script (Chinese lines
     * must contain a CJK ideograph; Latin lines a Latin letter or digit), so an
     * English-only page is not polluted by Chinese-recognizer noise.
     *
     * @return (merged text in reading order, average element confidence)
     */
    private suspend fun extractMerged(bitmap: Bitmap): Pair<String?, Float> =
        withContext(Dispatchers.Default) {
            try {
                val inputImage = InputImage.fromBitmap(bitmap, 0)

                val latinResult = try {
                    latinRecognizer.process(inputImage).await()
                } catch (e: Exception) { null }

                val chineseResult = try {
                    chineseRecognizer.process(inputImage).await()
                } catch (e: Exception) { null }

                val lines = mutableListOf<OcrLine>()
                collectUsableLines(latinResult, isChinese = false, lines)
                collectUsableLines(chineseResult, isChinese = true, lines)

                if (lines.isEmpty()) {
                    Log.d(TAG, "OCR: no text found (latin + chinese)")
                    return@withContext Pair(null, 0f)
                }

                // Group into visual rows — code-switched lines from the two
                // recognizers share a vertical band, so they join one row and
                // are ordered left→right; the rows themselves run top→bottom.
                val rows = groupIntoRows(lines)
                val ordered = rows.sortedBy { row -> row.minOf { it.top } }
                val sb = StringBuilder()
                var totalConf = 0f
                var totalChars = 0
                for (row in ordered) {
                    val rowLines = row.sortedBy { it.left }
                    val rowText = rowLines.joinToString(" ") { it.text }.trim()
                    if (rowText.isEmpty()) continue
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(rowText)
                    val chars = rowLines.sumOf { it.text.length }
                    totalConf += rowLines.fold(0f) { acc, l -> acc + l.confidence * l.text.length }
                    totalChars += chars
                }

                val merged = sb.toString()
                if (merged.length < MIN_TEXT_LENGTH) {
                    Log.d(TAG, "OCR merged result too short")
                    return@withContext Pair(null, 0f)
                }
                Log.d(TAG, "OCR merged: ${merged.length} chars across ${ordered.size} rows")
                Pair(merged, if (totalChars > 0) totalConf / totalChars else 0f)

            } catch (e: Exception) {
                Log.e(TAG, "extractTextWithConfidence failed: ${e.message}")
                Pair(null, 0f)
            }
        }

    /** One recognized text line with its visual band + average confidence. */
    private class OcrLine(
        val top: Int,
        val left: Int,
        val bottom: Int,
        val text: String,
        val confidence: Float
    )

    /** Collect the lines of one recognizer that genuinely belong to its script. */
    private fun collectUsableLines(
        result: com.google.mlkit.vision.text.Text?,
        isChinese: Boolean,
        out: MutableList<OcrLine>
    ) {
        if (result == null) return
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val text = line.text?.trim() ?: continue
                if (text.isEmpty()) continue
                val usable = if (isChinese) {
                    containsCjkIdeograph(text)
                } else {
                    text.length >= 2 && text.any { it.isLetter() || it.isDigit() }
                }
                if (!usable) continue
                val box = line.boundingBox ?: continue
                out.add(OcrLine(box.top, box.left, box.bottom, text, lineConfidence(line)))
            }
        }
    }

    /** True if the text contains at least one CJK ideograph (any real Chinese). */
    private fun containsCjkIdeograph(text: String): Boolean =
        text.any { ch ->
            val c = ch.code
            (c in 0x4E00..0x9FFF) ||  // CJK Unified Ideographs
                (c in 0x3400..0x4DBF) ||  // Extension A
                (c in 0xF900..0xFAFF)     // Compatibility Ideographs
        }

    /** Average confidence of a line's elements (neutral default when absent). */
    private fun lineConfidence(line: com.google.mlkit.vision.text.Text.Line): Float {
        val elements = line.elements
        if (elements.isEmpty()) return 0.85f
        var sum = 0f
        for (e in elements) sum += e.confidence
        return sum / elements.size
    }

    /**
     * Group lines into visual rows. Lines whose vertical bands overlap by at
     * least half the shorter line's height belong to the same text row — this
     * is what re-joins the Latin and Chinese halves of one printed line.
     */
    private fun groupIntoRows(lines: List<OcrLine>): List<List<OcrLine>> {
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in lines.sortedBy { it.top }) {
            val existing = rows.firstOrNull { row -> row.any { sameRow(it, line) } }
            if (existing != null) existing.add(line) else rows.add(mutableListOf(line))
        }
        return rows
    }

    private fun sameRow(a: OcrLine, b: OcrLine): Boolean {
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (overlap <= 0) return false
        val shorterHeight = minOf(a.bottom - a.top, b.bottom - b.top)
        return overlap >= shorterHeight * 0.5f
    }

    /**
     * Release recognizer resources. Call when OCR is no longer needed.
     */
    fun close() {
        try {
            latinRecognizer.close()
            chineseRecognizer.close()
        } catch (e: Exception) {
            Log.w(TAG, "close error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "OcrHelper"

        /** Minimum characters for OCR result to be considered useful. */
        private const val MIN_TEXT_LENGTH = 3
    }
}
