package com.vyze.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet

/**
 * High-contrast overlay view designed for low-vision users.
 *
 * Extends [BaseOverlayView] for shared coordinate pipeline and state.
 * Adds:
 * - Thick yellow-and-black double-border bounding boxes
 * - Ultra-large, high-contrast text labels
 * - Large OCR result text rendered on screen
 */
class HighContrastOverlayView(context: Context?, attrs: AttributeSet?) :
    BaseOverlayView(context, attrs) {

    // OCR text to render on screen
    private var ocrText: String = ""

    // Paints for high-contrast rendering
    private var boxOuterPaint = Paint()
    private var boxInnerPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()
    private var ocrBackgroundPaint = Paint()
    private var ocrTextPaint = Paint()

    init {
        initPaints()
    }

    override fun clear() {
        super.clear()
        ocrText = ""
    }

    fun clearOcrText() {
        ocrText = ""
        invalidate()
    }

    fun setOcrText(text: String) {
        ocrText = text
        invalidate()
    }

    override fun initPaints() {
        boxOuterPaint.color = COLOR_YELLOW
        boxOuterPaint.style = Paint.Style.STROKE
        boxOuterPaint.strokeWidth = BORDER_WIDTH_OUTER
        boxOuterPaint.isAntiAlias = true

        boxInnerPaint.color = Color.BLACK
        boxInnerPaint.style = Paint.Style.STROKE
        boxInnerPaint.strokeWidth = BORDER_WIDTH_INNER
        boxInnerPaint.isAntiAlias = true

        textBackgroundPaint.color = Color.BLACK
        textBackgroundPaint.style = Paint.Style.FILL
        textBackgroundPaint.isAntiAlias = true

        textPaint.color = COLOR_YELLOW
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = LABEL_TEXT_SIZE
        textPaint.isFakeBoldText = true
        textPaint.isAntiAlias = true
        textPaint.typeface = Typeface.DEFAULT_BOLD

        ocrBackgroundPaint.color = COLOR_OCR_BACKGROUND
        ocrBackgroundPaint.style = Paint.Style.FILL
        ocrBackgroundPaint.isAntiAlias = true

        ocrTextPaint.color = Color.WHITE
        ocrTextPaint.style = Paint.Style.FILL
        ocrTextPaint.textSize = OCR_TEXT_SIZE
        ocrTextPaint.isFakeBoldText = true
        ocrTextPaint.isAntiAlias = true
        ocrTextPaint.typeface = Typeface.DEFAULT_BOLD
    }

    override fun draw(canvas: Canvas) {
        // Draw OCR text first (behind boxes)
        if (ocrText.isNotEmpty()) {
            drawOcrText(canvas)
        }
        super.draw(canvas)
    }

    override fun drawDetections(
        canvas: Canvas,
        detections: List<ObjectDetectorHelper.VyzeDetection>
    ) {
        for (detection in detections) {
            val boxRect = computeBoxRect(detection.boundingBox)

            // Draw outer yellow border
            canvas.drawRect(boxRect, boxOuterPaint)

            // Draw inner black border for double-border high-contrast effect
            val innerRect = RectF(
                boxRect.left + BORDER_WIDTH_OUTER / 2,
                boxRect.top + BORDER_WIDTH_OUTER / 2,
                boxRect.right - BORDER_WIDTH_OUTER / 2,
                boxRect.bottom - BORDER_WIDTH_OUTER / 2
            )
            canvas.drawRect(innerRect, boxInnerPaint)

            // Strict pairing: label from THIS detection
            val category = detection.categories.firstOrNull() ?: continue
            val drawableText = "${category.label.uppercase()} ${
                String.format("%.0f%%", category.score * 100)
            }"

            // Draw label background anchored to THIS box
            textBackgroundPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)
            val padding = LABEL_PADDING
            canvas.drawRect(
                boxRect.left,
                boxRect.top - bounds.height() - padding * 2,
                boxRect.left + bounds.width() + padding,
                boxRect.top,
                textBackgroundPaint
            )

            // Draw label text in high-contrast yellow
            canvas.drawText(
                drawableText,
                boxRect.left + padding / 2,
                boxRect.top - padding,
                textPaint
            )
        }
    }

    private fun drawOcrText(canvas: Canvas) {
        val maxLineWidth = width - OCR_MARGIN * 2
        val lines = wrapText(ocrText, ocrTextPaint, maxLineWidth)
        val lineHeight = OCR_TEXT_SIZE * 1.3f
        val totalHeight = lines.size * lineHeight + OCR_PADDING * 2
        val topStart = height - totalHeight - OCR_BOTTOM_MARGIN

        canvas.drawRect(
            0f, topStart, width.toFloat(), height.toFloat(), ocrBackgroundPaint
        )

        var y = topStart + OCR_PADDING + OCR_TEXT_SIZE
        for (line in lines) {
            canvas.drawText(line, OCR_MARGIN, y, ocrTextPaint)
            y += lineHeight
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val result = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(testLine) <= maxWidth) {
                currentLine.append(if (currentLine.isEmpty()) "" else " ").append(word)
            } else {
                if (currentLine.isNotEmpty()) {
                    result.add(currentLine.toString())
                    currentLine = StringBuilder(word)
                } else {
                    result.add(word)
                }
            }
        }
        if (currentLine.isNotEmpty()) result.add(currentLine.toString())
        return result.ifEmpty { listOf(text) }
    }

    companion object {
        private const val COLOR_YELLOW = 0xFFFFEB3B.toInt()
        private const val COLOR_OCR_BACKGROUND = 0xE6000000.toInt()
        private const val BORDER_WIDTH_OUTER = 16f
        private const val BORDER_WIDTH_INNER = 6f
        private const val LABEL_TEXT_SIZE = 42f
        private const val LABEL_PADDING = 12f
        private const val OCR_TEXT_SIZE = 56f
        private const val OCR_MARGIN = 32f
        private const val OCR_PADDING = 24f
        private const val OCR_BOTTOM_MARGIN = 16f
    }
}
