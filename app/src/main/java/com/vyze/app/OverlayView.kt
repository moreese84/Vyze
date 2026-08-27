package com.vyze.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import androidx.core.content.ContextCompat

/**
 * Standard overlay that draws bounding boxes and labels for detected objects.
 *
 * Extends [BaseOverlayView] for shared coordinate pipeline and state.
 * This view uses standard colors and thin borders.
 */
class OverlayView(context: Context?, attrs: AttributeSet?) :
    BaseOverlayView(context, attrs) {

    private var boxPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()

    init {
        initPaints()
    }

    override fun clear() {
        super.clear()
        textPaint.reset()
        textBackgroundPaint.reset()
        boxPaint.reset()
        initPaints()
    }

    override fun initPaints() {
        textBackgroundPaint.color = Color.BLACK
        textBackgroundPaint.style = Paint.Style.FILL
        textBackgroundPaint.textSize = 50f

        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 50f

        boxPaint.color = ContextCompat.getColor(context!!, R.color.mp_primary)
        boxPaint.strokeWidth = 8F
        boxPaint.style = Paint.Style.STROKE
    }

    override fun drawDetections(
        canvas: Canvas,
        detections: List<ObjectDetectorHelper.VyzeDetection>
    ) {
        for (detection in detections) {
            val boxRect = computeBoxRect(detection.boundingBox)

            // Draw bounding box
            canvas.drawRect(boxRect, boxPaint)

            // Strict pairing: label from THIS detection
            val category = detection.categories.firstOrNull() ?: continue
            val drawableText = category.label + " " +
                String.format("%.2f", category.score)

            // Draw label background anchored to THIS box
            textBackgroundPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)
            canvas.drawRect(
                boxRect.left,
                boxRect.top,
                boxRect.left + bounds.width() + BOUNDING_RECT_TEXT_PADDING,
                boxRect.top + bounds.height() + BOUNDING_RECT_TEXT_PADDING,
                textBackgroundPaint
            )

            // Draw label text
            canvas.drawText(
                drawableText,
                boxRect.left,
                boxRect.top + bounds.height(),
                textPaint
            )
        }
    }
}
