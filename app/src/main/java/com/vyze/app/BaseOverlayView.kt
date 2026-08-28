package com.vyze.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlin.math.min

/**
 * Base class for bounding box overlay views.
 *
 * ## Coordinate Pipeline (shared)
 * 1. Detection boxes arrive in **letterboxed bitmap** coordinate space.
 * 2. Subtract `letterboxPadX` / `letterboxPadY` → **original frame** space.
 * 3. Scale by `scaleFactor` and translate by `offsetX/offsetY` → **view pixels**.
 *
 * ## PreviewView Alignment
 * The camera preview uses `fillStart` (aspect-fit), so the overlay must
 * replicate the same transform: scale = min(viewW / frameW, viewH / frameH),
 * then offset to the start (top-left for portrait).
 */
abstract class BaseOverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {

    // ── Shared State ──────────────────────────────────────────────

    protected var detections: List<ObjectDetectorHelper.VyzeDetection> = emptyList()
    protected var scaleFactor: Float = 1f
    protected var offsetX: Float = 0f
    protected var offsetY: Float = 0f
    protected var letterboxPadX = 0
    protected var letterboxPadY = 0
    protected var frameWidth = 0
    protected var frameHeight = 0
    var runningMode: RunningMode = RunningMode.IMAGE
    protected var hasPendingResults = false
    protected val bounds = Rect()

    // ── Lifecycle ─────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (hasPendingResults && frameWidth > 0 && frameHeight > 0) {
            recomputeScaleFactor()
            invalidate()
        }
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (detections.isEmpty()) return
        if (scaleFactor <= 0f || width <= 0 || height <= 0) return
        drawDetections(canvas, detections)
    }

    // ── Public API ────────────────────────────────────────────────

    open fun clear() {
        detections = emptyList()
        invalidate()
    }

    fun setResults(
        newDetections: List<ObjectDetectorHelper.VyzeDetection>,
        outputHeight: Int,
        outputWidth: Int,
        letterboxPadX: Int = 0,
        letterboxPadY: Int = 0
    ) {
        this.detections = newDetections
        this.frameWidth = outputWidth
        this.frameHeight = outputHeight
        this.letterboxPadX = letterboxPadX
        this.letterboxPadY = letterboxPadY
        this.hasPendingResults = true

        recomputeScaleFactor()
        invalidate()
    }

    // ── Coordinate Transform ──────────────────────────────────────

    /**
     * Transform a detection bounding box from letterboxed coordinates
     * to view pixel coordinates.
     *
     * Pipeline:
     *   1. rawBox is in letterboxed bitmap space (e.g. 1280×1280)
     *   2. Subtract letterbox padding → original frame space
     *   3. Scale by scaleFactor + translate by offset → view pixels
     */
    protected fun computeBoxRect(rawBox: RectF): RectF {
        val frameLeft   = (rawBox.left   - letterboxPadX) * scaleFactor + offsetX
        val frameTop    = (rawBox.top    - letterboxPadY) * scaleFactor + offsetY
        val frameRight  = (rawBox.right  - letterboxPadX) * scaleFactor + offsetX
        val frameBottom = (rawBox.bottom - letterboxPadY) * scaleFactor + offsetY

        return RectF(frameLeft, frameTop, frameRight, frameBottom)
    }

    /**
     * Map a screen touch coordinate back to original frame coordinates.
     * Returns a [Pair] of (frameX, frameY) in the original (pre-letterbox,
     * pre-rotation) camera frame space, or null if the touch is outside
     * the scaled image area.
     */
    fun screenToFrameCoords(screenX: Float, screenY: Float): Pair<Float, Float>? {
        if (scaleFactor <= 0f || frameWidth <= 0 || frameHeight <= 0) return null

        val frameX = (screenX - offsetX) / scaleFactor
        val frameY = (screenY - offsetY) / scaleFactor

        // Bounds check: must be within the original frame
        if (frameX < 0f || frameX > frameWidth.toFloat() ||
            frameY < 0f || frameY > frameHeight.toFloat()) {
            return null
        }

        return Pair(frameX, frameY)
    }

    /**
     * Hit-test a screen touch against all current detections.
     * Returns the highest-confidence detection whose bounding box
     * (in frame coordinates) contains the touch point, or null.
     */
    fun hitTestDetection(screenX: Float, screenY: Float): ObjectDetectorHelper.VyzeDetection? {
        val (frameX, frameY) = screenToFrameCoords(screenX, screenY) ?: return null

        // Find all detections whose bounding box (in letterboxed coords)
        // contains the touch point after unpadding
        val hitDetections = detections.mapNotNull { detection ->
            val box = detection.boundingBox
            val unPaddedLeft   = box.left   - letterboxPadX
            val unPaddedTop    = box.top    - letterboxPadY
            val unPaddedRight  = box.right  - letterboxPadX
            val unPaddedBottom = box.bottom - letterboxPadY

            if (frameX >= unPaddedLeft && frameX <= unPaddedRight &&
                frameY >= unPaddedTop  && frameY <= unPaddedBottom) {
                detection
            } else {
                null
            }
        }

        // Return highest-confidence hit
        return hitDetections.maxByOrNull {
            it.categories.firstOrNull()?.score ?: 0f
        }
    }

    // ── Internal ──────────────────────────────────────────────────

    /**
     * Compute the scale factor and offset to map from original frame
     * coordinates to view pixel coordinates.
     *
     * Uses **aspect-fit** (min) for all modes to match PreviewView's
     * `fillStart` scaling. The offset positions the scaled image at
     * the start (top for portrait).
     */
    private fun recomputeScaleFactor() {
        if (frameWidth <= 0 || frameHeight <= 0 || width <= 0 || height <= 0) {
            scaleFactor = 1f
            offsetX = 0f
            offsetY = 0f
            return
        }

        // Aspect-fit: use min() so the entire frame fits within the view
        scaleFactor = min(width.toFloat() / frameWidth, height.toFloat() / frameHeight)

        // Compute offset to position image at top-left (matching fillStart)
        val scaledFrameW = frameWidth * scaleFactor
        val scaledFrameH = frameHeight * scaleFactor
        offsetX = (width - scaledFrameW) / 2f
        offsetY = (height - scaledFrameH) / 2f
    }

    // ── Subclass Contract ─────────────────────────────────────────

    /** Called once during init. Subclass must initialize its paints here. */
    protected abstract fun initPaints()

    /** Draw all detections with the subclass's custom rendering. */
    protected abstract fun drawDetections(
        canvas: Canvas,
        detections: List<ObjectDetectorHelper.VyzeDetection>
    )

    companion object {
        const val BOUNDING_RECT_TEXT_PADDING = 8
    }
}
