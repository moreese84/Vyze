package com.vyze.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import kotlin.math.max
import kotlin.math.min

/**
 * High-contrast overlay view designed for low-vision users.
 *
 * Draws:
 * - Thick yellow-and-black bordered bounding boxes around detected MediaPipe objects.
 * - Ultra-large, high-contrast text labels for recognized objects.
 * - Large OCR result text rendered on screen for maximum visual contrast.
 */
class HighContrastOverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {

    private var results: ObjectDetectorResult? = null
    private var scaleFactor: Float = 1f
    private var bounds = Rect()
    private var outputWidth = 0
    private var outputHeight = 0
    private var outputRotate = 0
    private var runningMode: RunningMode = RunningMode.IMAGE

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

    fun clear() {
        results = null
        ocrText = ""
        invalidate()
    }

    fun clearOcrText() {
        ocrText = ""
        invalidate()
    }

    fun setRunningMode(runningMode: RunningMode) {
        this.runningMode = runningMode
    }

    /**
     * Display recognized OCR text on screen in large, high-contrast letters.
     */
    fun setOcrText(text: String) {
        ocrText = text
        invalidate()
    }

    private fun initPaints() {
        // Outer border: bright yellow for maximum contrast
        boxOuterPaint.color = COLOR_YELLOW
        boxOuterPaint.style = Paint.Style.STROKE
        boxOuterPaint.strokeWidth = BORDER_WIDTH_OUTER
        boxOuterPaint.isAntiAlias = true

        // Inner border: solid black for high-contrast double-border effect
        boxInnerPaint.color = Color.BLACK
        boxInnerPaint.style = Paint.Style.STROKE
        boxInnerPaint.strokeWidth = BORDER_WIDTH_INNER
        boxInnerPaint.isAntiAlias = true

        // Label background: solid black for readability
        textBackgroundPaint.color = Color.BLACK
        textBackgroundPaint.style = Paint.Style.FILL
        textBackgroundPaint.isAntiAlias = true

        // Label text: bright yellow, large font
        textPaint.color = COLOR_YELLOW
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = LABEL_TEXT_SIZE
        textPaint.isFakeBoldText = true
        textPaint.isAntiAlias = true
        textPaint.typeface = Typeface.DEFAULT_BOLD

        // OCR background: semi-transparent black
        ocrBackgroundPaint.color = COLOR_OCR_BACKGROUND
        ocrBackgroundPaint.style = Paint.Style.FILL
        ocrBackgroundPaint.isAntiAlias = true

        // OCR text: bright white, very large font
        ocrTextPaint.color = Color.WHITE
        ocrTextPaint.style = Paint.Style.FILL
        ocrTextPaint.textSize = OCR_TEXT_SIZE
        ocrTextPaint.isFakeBoldText = true
        ocrTextPaint.isAntiAlias = true
        ocrTextPaint.typeface = Typeface.DEFAULT_BOLD
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        // Draw OCR text if available
        if (ocrText.isNotEmpty()) {
            drawOcrText(canvas)
        }

        // Draw bounding boxes around detected objects
        results?.detections()?.map { detection ->
            val boxRect = RectF(
                detection.boundingBox().left,
                detection.boundingBox().top,
                detection.boundingBox().right,
                detection.boundingBox().bottom
            )
            val matrix = Matrix()
            matrix.postTranslate(-outputWidth / 2f, -outputHeight / 2f)
            matrix.postRotate(outputRotate.toFloat())

            if (outputRotate == 90 || outputRotate == 270) {
                matrix.postTranslate(outputHeight / 2f, outputWidth / 2f)
            } else {
                matrix.postTranslate(outputWidth / 2f, outputHeight / 2f)
            }
            matrix.mapRect(boxRect)
            boxRect
        }?.forEachIndexed { index, floats ->

            val top = floats.top * scaleFactor
            val bottom = floats.bottom * scaleFactor
            val left = floats.left * scaleFactor
            val right = floats.right * scaleFactor

            // Draw outer yellow border
            val drawableRect = RectF(left, top, right, bottom)
            canvas.drawRect(drawableRect, boxOuterPaint)

            // Draw inner black border for double-border high-contrast effect
            val innerRect = RectF(
                left + BORDER_WIDTH_OUTER / 2,
                top + BORDER_WIDTH_OUTER / 2,
                right - BORDER_WIDTH_OUTER / 2,
                bottom - BORDER_WIDTH_OUTER / 2
            )
            canvas.drawRect(innerRect, boxInnerPaint)

            // Create text to display alongside detected objects
            val category = results?.detections()!![index].categories()[0]
            val drawableText =
                "${category.categoryName().uppercase()} ${
                    String.format("%.0f%%", category.score() * 100)
                }"

            // Draw label background
            textBackgroundPaint.getTextBounds(
                drawableText,
                0,
                drawableText.length,
                bounds
            )
            val textWidth = bounds.width()
            val textHeight = bounds.height()
            val padding = LABEL_PADDING
            canvas.drawRect(
                left,
                top - textHeight - padding * 2,
                left + textWidth + padding,
                top,
                textBackgroundPaint
            )

            // Draw label text in high-contrast yellow
            canvas.drawText(
                drawableText,
                left + padding / 2,
                top - padding,
                textPaint
            )
        }
    }

    /**
     * Draws OCR text in large, high-contrast white-on-black at the bottom of the screen.
     */
    private fun drawOcrText(canvas: Canvas) {
        val maxLineWidth = width - OCR_MARGIN * 2
        val lines = wrapText(ocrText, ocrTextPaint, maxLineWidth)
        val lineHeight = OCR_TEXT_SIZE * 1.3f
        val totalHeight = lines.size * lineHeight + OCR_PADDING * 2
        val topStart = height - totalHeight - OCR_BOTTOM_MARGIN

        // Draw background
        canvas.drawRect(
            0f,
            topStart,
            width.toFloat(),
            height.toFloat(),
            ocrBackgroundPaint
        )

        // Draw each line of OCR text
        var y = topStart + OCR_PADDING + OCR_TEXT_SIZE
        for (line in lines) {
            canvas.drawText(line, OCR_MARGIN, y, ocrTextPaint)
            y += lineHeight
        }
    }

    /**
     * Wraps text to fit within the given max width by splitting on spaces.
     */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val result = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word
            else "$currentLine $word"

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
        if (currentLine.isNotEmpty()) {
            result.add(currentLine.toString())
        }

        return result.ifEmpty { listOf(text) }
    }

    fun setResults(
        detectionResults: ObjectDetectorResult,
        outputHeight: Int,
        outputWidth: Int,
        imageRotation: Int
    ) {
        results = detectionResults
        this.outputWidth = outputWidth
        this.outputHeight = outputHeight
        this.outputRotate = imageRotation

        val rotatedWidthHeight = when (imageRotation) {
            0, 180 -> Pair(outputWidth, outputHeight)
            90, 270 -> Pair(outputHeight, outputWidth)
            else -> return
        }

        scaleFactor = when (runningMode) {
            RunningMode.IMAGE,
            RunningMode.VIDEO -> {
                min(
                    width * 1f / rotatedWidthHeight.first,
                    height * 1f / rotatedWidthHeight.second
                )
            }

            RunningMode.LIVE_STREAM -> {
                max(
                    width * 1f / rotatedWidthHeight.first,
                    height * 1f / rotatedWidthHeight.second
                )
            }
        }

        invalidate()
    }

    companion object {
        // High-contrast yellow for maximum visibility
        private const val COLOR_YELLOW = 0xFFFFEB3B.toInt()

        // Semi-transparent black for OCR background
        private const val COLOR_OCR_BACKGROUND = 0xE6000000.toInt()

        // Border dimensions for thick double-border effect
        private const val BORDER_WIDTH_OUTER = 16f
        private const val BORDER_WIDTH_INNER = 6f

        // Label dimensions
        private const val LABEL_TEXT_SIZE = 42f
        private const val LABEL_PADDING = 12f

        // OCR text dimensions
        private const val OCR_TEXT_SIZE = 56f
        private const val OCR_MARGIN = 32f
        private const val OCR_PADDING = 24f
        private const val OCR_BOTTOM_MARGIN = 16f
    }
}
