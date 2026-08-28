package com.vyze.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet

/**
 * High-contrast overlay view designed for low-vision users.
 *
 * Extends [BaseOverlayView] for shared coordinate pipeline and state.
 * Renders one clean uppercase label per detection: "COMPUTER KEYBOARD 27%".
 *
 * Label backgrounds are dynamically sized and clamped to view boundaries.
 */
class HighContrastOverlayView(context: Context?, attrs: AttributeSet?) :
    BaseOverlayView(context, attrs) {

    private var ocrText: String = ""

    private var boxOuterPaint = Paint()
    private var boxInnerPaint = Paint()
    private var labelBgPaint = Paint()
    private var labelPaint = Paint()
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

        labelBgPaint.color = Color.BLACK
        labelBgPaint.style = Paint.Style.FILL
        labelBgPaint.textSize = LABEL_TEXT_SIZE
        labelBgPaint.isFakeBoldText = true
        labelBgPaint.isAntiAlias = true

        labelPaint.color = COLOR_YELLOW
        labelPaint.style = Paint.Style.FILL
        labelPaint.textSize = LABEL_TEXT_SIZE
        labelPaint.isFakeBoldText = true
        labelPaint.isAntiAlias = true
        labelPaint.typeface = Typeface.DEFAULT_BOLD

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
        if (ocrText.isNotEmpty()) {
            drawOcrText(canvas)
        }
        super.draw(canvas)
    }

    override fun drawDetections(
        canvas: Canvas,
        detections: List<ObjectDetectorHelper.VyzeDetection>
    ) {
        val viewW = width.toFloat()
        val viewH = height.toFloat()

        for (detection in detections) {
            val boxRect = computeBoxRect(detection.boundingBox)

            // Draw outer yellow border
            canvas.drawRect(boxRect, boxOuterPaint)

            // Draw inner black border for double-border high-contrast effect
            val inset = BORDER_WIDTH_OUTER / 2
            val innerRect = RectF(
                boxRect.left + inset,
                boxRect.top + inset,
                boxRect.right - inset,
                boxRect.bottom - inset
            )
            canvas.drawRect(innerRect, boxInnerPaint)

            // Build label: "COMPUTER KEYBOARD 27%"
            val category = detection.categories.firstOrNull() ?: continue
            val drawableText = "${category.label.uppercase()} ${(category.score * 100).toInt()}%"

            // Dynamic text measurement
            val textWidth = labelPaint.measureText(drawableText)
            labelPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)
            val textHeight = bounds.height().toFloat()
            val pad = LABEL_PADDING

            // Background rect dimensions
            val bgW = textWidth + pad
            val bgH = textHeight + pad * 2

            // Position: prefer above box, clamp to view edges
            var bgLeft = boxRect.left
            var bgTop = boxRect.top - bgH - BORDER_WIDTH_OUTER

            // If above the box would go off-screen top, place below
            if (bgTop < 0f) {
                bgTop = boxRect.bottom + BORDER_WIDTH_OUTER
            }

            // Clamp horizontal so right edge doesn't exceed view width
            if (bgLeft + bgW > viewW) {
                bgLeft = viewW - bgW
            }

            // Clamp so left edge doesn't go off-screen left
            if (bgLeft < 0f) {
                bgLeft = 0f
            }

            // Clamp vertical so bottom doesn't exceed view height
            if (bgTop + bgH > viewH) {
                bgTop = viewH - bgH
            }

            val bgBottom = bgTop + bgH
            val bgRight = bgLeft + bgW

            // Draw background rect
            canvas.drawRect(bgLeft, bgTop, bgRight, bgBottom, labelBgPaint)

            // Draw label text baseline inside the background rect
            canvas.drawText(
                drawableText,
                bgLeft + pad / 2,
                bgBottom - pad / 2 - bounds.bottom, // center vertically
                labelPaint
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
