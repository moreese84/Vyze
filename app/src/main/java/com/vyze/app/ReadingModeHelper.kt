package com.vyze.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Specialized OCR mode for reading documents, books, and pages.
 *
 * Features:
 * - Continuous line tracking with position awareness
 * - Page detection heuristics (page breaks, margins)
 * - Position bookmarking for resuming where the user left off
 * - Structured text output that reads naturally (top-to-bottom, left-to-right)
 *
 * Usage:
 * 1. Enter reading mode → `enterReadingMode()`
 * 2. Feed frames → `processFrame(bitmap, rotationDegrees)` returns page text
 * 3. Read current position → `getCurrentPageSummary()`
 * 4. Bookmark → `bookmarkPosition()` / `resumeFromBookmark()`
 * 5. Exit → `exitReadingMode()`
 */
class ReadingModeHelper {

    private val TAG = "ReadingModeHelper"

    private var textRecognizer: TextRecognizer? = null
    private var isActive = false

    // ── Page State ────────────────────────────────────────────────────

    /** Accumulated text lines for the current page. */
    private val currentPageLines = mutableListOf<String>()

    /** Previous page texts for back-tracking. */
    private val pageHistory = mutableListOf<String>()

    /** Current position (line index) within the page. */
    private var currentLineIndex = 0

    /** Bookmark: saved line index for resuming. */
    private var bookmarkedLineIndex = 0

    /** Previous frame's text for change detection. */
    private var previousFrameText = ""

    /** Number of consecutive unchanged frames (for page break detection). */
    private var unchangedFrameCount = 0

    /** Threshold for detecting a "page hold" (user is reading steadily). */
    private val PAGE_STABLE_THRESHOLD = 8

    // ── Configuration ─────────────────────────────────────────────────

    /** Maximum lines to accumulate per page before archiving. */
    private val MAX_PAGE_LINES = 200

    /** Minimum text blocks to consider a frame as having content. */
    private val MIN_TEXT_BLOCKS = 1

    // ── Lifecycle ─────────────────────────────────────────────────────

    /** Enters reading mode and initializes the text recognizer. */
    fun enterReadingMode() {
        if (isActive) return
        isActive = true
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        currentPageLines.clear()
        currentLineIndex = 0
        previousFrameText = ""
        unchangedFrameCount = 0
        Log.d(TAG, "Reading mode activated")
    }

    /** Exits reading mode and releases resources. */
    fun exitReadingMode() {
        isActive = false
        textRecognizer?.close()
        textRecognizer = null
        Log.d(TAG, "Reading mode deactivated. Pages read: ${pageHistory.size}")
    }

    fun isReadingModeActive(): Boolean = isActive

    // ── Frame Processing ──────────────────────────────────────────────

    /**
     * Processes a camera frame for reading mode OCR.
     *
     * @param bitmap          The camera frame as a Bitmap.
     * @param rotationDegrees Rotation applied by CameraX.
     * @param onSuccess       Callback with the extracted page text.
     * @param onError         Callback if OCR fails.
     */
    fun processFrame(
        bitmap: Bitmap,
        rotationDegrees: Int,
        onSuccess: (pageText: String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (!isActive || textRecognizer == null) {
            onError(IllegalStateException("Reading mode not active"))
            return
        }

        try {
            val image = InputImage.fromBitmap(bitmap, rotationDegrees)
            textRecognizer!!.process(image)
                .addOnSuccessListener { result -> handleOcrResult(result, onSuccess) }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Reading mode OCR failed", e)
                    onError(e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create InputImage for reading mode", e)
            onError(e)
        }
    }

    /**
     * Processes a frame synchronously using CountDownLatch (for pipeline integration).
     */
    fun processFrameSync(
        bitmap: Bitmap,
        rotationDegrees: Int
    ): String? {
        if (!isActive || textRecognizer == null) return null

        val latch = CountDownLatch(1)
        var resultText: String? = null

        try {
            val image = InputImage.fromBitmap(bitmap, rotationDegrees)
            textRecognizer!!.process(image)
                .addOnSuccessListener { result ->
                    resultText = extractStructuredText(result)
                    latch.countDown()
                }
                .addOnFailureListener {
                    latch.countDown()
                }

            latch.await(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Sync reading mode OCR failed", e)
        }

        return resultText
    }

    // ── Result Processing ─────────────────────────────────────────────

    private fun handleOcrResult(result: Text, onSuccess: (String) -> Unit) {
        val text = extractStructuredText(result)

        if (text.isBlank()) {
            // No new text — increment unchanged counter
            unchangedFrameCount++
            if (unchangedFrameCount >= PAGE_STABLE_THRESHOLD && currentPageLines.isNotEmpty()) {
                // Page appears stable — user is likely reading
                onSuccess(getCurrentPageSummary())
            }
            return
        }

        unchangedFrameCount = 0

        if (text == previousFrameText) {
            // Same text as last frame — user is holding steady
            unchangedFrameCount++
            return
        }

        previousFrameText = text

        // Detect if this is a new page (significant text change)
        val isNewPage = currentPageLines.isNotEmpty() && isPageBreakDetected(text)

        if (isNewPage) {
            archiveCurrentPage()
        }

        // Add new lines to current page
        val newLines = text.split("\n").filter { it.isNotBlank() }
        for (line in newLines) {
            if (line !in currentPageLines) {
                currentPageLines.add(line)
            }
        }

        // Enforce page line limit
        if (currentPageLines.size > MAX_PAGE_LINES) {
            archiveCurrentPage()
        }

        onSuccess(getCurrentPageSummary())
    }

    /**
     * Extracts structured text from ML Kit result.
     * Sorts text blocks top-to-bottom for natural reading order.
     */
    private fun extractStructuredText(result: Text): String {
        if (result.textBlocks.isEmpty()) return ""

        // Sort blocks by vertical position (top to bottom)
        val sortedBlocks = result.textBlocks.sortedBy { block ->
            block.boundingBox?.top ?: 0
        }

        return sortedBlocks.joinToString("\n") { block ->
            // Within each block, sort lines by vertical position
            block.lines.sortedBy { line ->
                line.boundingBox?.top ?: 0
            }.joinToString(" ") { line ->
                line.text.trim()
            }
        }
    }

    // ── Page Detection ────────────────────────────────────────────────

    /**
     * Detects if the new text represents a new page.
     * Heuristic: if >60% of the new text is different from the current page,
     * it's likely a new page.
     */
    private fun isPageBreakDetected(newText: String): Boolean {
        if (currentPageLines.isEmpty()) return false

        val currentPageText = currentPageLines.joinToString(" ").lowercase()
        val newWords = newText.lowercase().split("\\s+".toRegex()).toSet()
        val currentWords = currentPageText.split("\\s+".toRegex()).toSet()

        if (currentWords.isEmpty()) return false

        val overlap = newWords.intersect(currentWords).size.toFloat() / currentWords.size
        return overlap < 0.4f // Less than 40% overlap = new page
    }

    private fun archiveCurrentPage() {
        if (currentPageLines.isNotEmpty()) {
            pageHistory.add(currentPageLines.joinToString("\n"))
            currentPageLines.clear()
            currentLineIndex = 0
            Log.d(TAG, "Page archived. Total pages: ${pageHistory.size}")
        }
    }

    // ── Reading Position ──────────────────────────────────────────────

    /**
     * Returns a summary of the current reading position.
     */
    fun getCurrentPageSummary(): String {
        if (currentPageLines.isEmpty()) return "No text on current page."

        return buildString {
            append("Page ${pageHistory.size + 1}. ")
            append("${currentPageLines.size} lines. ")
            append("Currently at line ${currentLineIndex + 1} of ${currentPageLines.size}. ")
            // Show current and next 2 lines for context
            val start = currentLineIndex
            val end = minOf(currentLineIndex + 3, currentPageLines.size)
            for (i in start until end) {
                append("Line ${i + 1}: ${currentPageLines[i]}. ")
            }
        }
    }

    /**
     * Returns the next batch of lines from current position.
     * Advances the position counter for continuous reading.
     */
    fun readNextLines(count: Int = 3): String {
        if (currentPageLines.isEmpty()) return "No text available."

        val start = currentLineIndex
        val end = minOf(currentLineIndex + count, currentPageLines.size)

        if (start >= currentPageLines.size) {
            return "Reached end of page. Swipe to go to next page."
        }

        val lines = currentPageLines.subList(start, end).joinToString(". ") { it.trim() }
        currentLineIndex = end

        return lines
    }

    /**
     * Returns the previous batch of lines.
     */
    fun readPreviousLines(count: Int = 3): String {
        if (currentPageLines.isEmpty()) return "No text available."

        val end = currentLineIndex
        val start = maxOf(end - count, 0)

        if (start <= 0 && end <= 0) return "At the beginning of the page."

        currentLineIndex = start
        val lines = currentPageLines.subList(start, end).joinToString(". ") { it.trim() }

        return lines
    }

    // ── Bookmarking ───────────────────────────────────────────────────

    /** Saves the current reading position as a bookmark. */
    fun bookmarkPosition() {
        bookmarkedLineIndex = currentLineIndex
        Log.d(TAG, "Bookmark saved at line $currentLineIndex")
    }

    /** Resumes reading from the last bookmarked position. */
    fun resumeFromBookmark(): String {
        currentLineIndex = bookmarkedLineIndex
        return "Resumed from bookmark at line ${currentLineIndex + 1}. ${getCurrentPageSummary()}"
    }

    // ── Page Navigation ───────────────────────────────────────────────

    /** Get total pages read. */
    fun getPageCount(): Int = pageHistory.size + if (currentPageLines.isNotEmpty()) 1 else 0

    /** Get total lines on current page. */
    fun getCurrentLineCount(): Int = currentPageLines.size

    /** Reset all state (new document). */
    fun reset() {
        currentPageLines.clear()
        pageHistory.clear()
        currentLineIndex = 0
        bookmarkedLineIndex = 0
        previousFrameText = ""
        unchangedFrameCount = 0
    }

    companion object {
        private const val TAG = "ReadingModeHelper"
    }
}
