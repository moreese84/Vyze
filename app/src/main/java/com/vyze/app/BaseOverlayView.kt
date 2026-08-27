package com.vyze.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlin.math.max
import kotlin.math.min

/**
 * Base class for bounding box overlay views.
 *
 * Extracts the shared coordinate pipeline, state management, and lifecycle
 * from [OverlayView] and [HighContrastOverlayView] into a single base.
 *
 * ## Coordinate Pipeline (shared)
 * 1. Detection boxes arrive in **letterboxed bitmap** coordinate space.
 * 2. Subtract `letterboxPadX` / `letterboxPadY` → **original frame** space.
 * 3. Scale by `scaleFactor` → **view pixels**.
 *
 * ## Subclass Contract
 * Subclasses must:
 * - Call [initPaints] in their `init` block
 * - Override [drawDetections] to render boxes + labels with their own paints
 * - Optionally call [computeBoxRect] to get the transformed RectF for each detection
 */
abstract class BaseOverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {

    // ── Shared State ──────────────────────────────────────────────

    protected var detections: List<ObjectDetectorHelper.VyzeDetection> = emptyList()
    protected var scaleFactor: Float = 1f
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
     */
    protected fun computeBoxRect(rawBox: RectF): RectF {
        val frameLeft   = rawBox.left   - letterboxPadX
        val frameTop    = rawBox.top    - letterboxPadY
        val frameRight  = rawBox.right  - letterboxPadX
        val frameBottom = rawBox.bottom - letterboxPadY

        return RectF(
            frameLeft * scaleFactor,
            frameTop * scaleFactor,
            frameRight * scaleFactor,
            frameBottom * scaleFactor
        )
    }

    // ── Internal ──────────────────────────────────────────────────

    private fun recomputeScaleFactor() {
        if (frameWidth <= 0 || frameHeight <= 0 || width <= 0 || height <= 0) {
            scaleFactor = 1f
            return
        }
        scaleFactor = when (runningMode) {
            RunningMode.IMAGE,
            RunningMode.VIDEO -> {
                min(width * 1f / frameWidth, height * 1f / frameHeight)
            }
            RunningMode.LIVE_STREAM -> {
                max(width * 1f / frameWidth, height * 1f / frameHeight)
            }
        }
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
