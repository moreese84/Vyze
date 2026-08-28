package com.vyze.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import androidx.core.content.ContextCompat

/**
 * Standard overlay that draws bounding boxes and labels for detected objects.
 *
 * Extends [BaseOverlayView] for shared coordinate pipeline and state.
 * Renders one clean label per detection: "Label 27%".
 *
 * Label backgrounds are dynamically sized using paint.measureText() + padding,
 * and clamped to view boundaries so text is never clipped at edges.
 */
class OverlayView(context: Context?, attrs: AttributeSet?) :
    BaseOverlayView(context, attrs) {

    private var boxPaint = Paint()
    private var labelBgPaint = Paint()
    private var labelPaint = Paint()

    init {
        initPaints()
    }

    override fun clear() {
        super.clear()
        labelPaint.reset()
        labelBgPaint.reset()
        boxPaint.reset()
        initPaints()
    }

    override fun initPaints() {
        labelBgPaint.color = Color.BLACK
        labelBgPaint.style = Paint.Style.FILL
        labelBgPaint.textSize = LABEL_TEXT_SIZE
        labelBgPaint.isAntiAlias = true

        labelPaint.color = Color.WHITE
        labelPaint.style = Paint.Style.FILL
        labelPaint.textSize = LABEL_TEXT_SIZE
        labelPaint.isAntiAlias = true

        boxPaint.color = ContextCompat.getColor(context!!, R.color.mp_primary)
        boxPaint.strokeWidth = BOX_STROKE_WIDTH
        boxPaint.style = Paint.Style.STROKE
        boxPaint.isAntiAlias = true
    }

    override fun drawDetections(
        canvas: Canvas,
        detections: List<ObjectDetectorHelper.VyzeDetection>
    ) {
        val viewW = width.toFloat()
        val viewH = height.toFloat()

        for (detection in detections) {
            val boxRect = computeBoxRect(detection.boundingBox)

            // Draw bounding box stroke
            canvas.drawRect(boxRect, boxPaint)

            // Build label: "Label 27%"
            val category = detection.categories.firstOrNull() ?: continue
            val drawableText = "${category.label} ${(category.score * 100).toInt()}%"

            // Dynamic text measurement
            val textWidth = labelPaint.measureText(drawableText)
            labelPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)
            val textHeight = bounds.height().toFloat()
            val pad = BOUNDING_RECT_TEXT_PADDING.toFloat()

            // Background rect dimensions
            val bgW = textWidth + pad
            val bgH = textHeight + pad * 2

            // Position: prefer above box, clamp to view edges
            var bgLeft = boxRect.left
            var bgTop = boxRect.top - bgH - BOX_STROKE_WIDTH

            // If above the box would go off-screen top, place below the box
            if (bgTop < 0f) {
                bgTop = boxRect.bottom + BOX_STROKE_WIDTH
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
                bgBottom - pad / 2 - bounds.bottom, // center vertically in bg
                labelPaint
            )
        }
    }

    companion object {
        private const val LABEL_TEXT_SIZE = 40f
        private const val BOX_STROKE_WIDTH = 6f
    }
}
