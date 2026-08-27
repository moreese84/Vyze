package com.vyze.app

import android.widget.TextView
import com.vyze.app.delegates.MlPipelineManager

/**
 * Manages the diagnostic debug overlay that shows pipeline state.
 *
 * Extracted from [CameraFragment] to separate debug UI rendering from
 * lifecycle and business logic. This class has NO dependency on:
 * - Fragment lifecycle
 * - TTS, Haptics, or Voice commands
 * - Camera or ML pipeline initialization
 *
 * ## Responsibilities
 * 1. Read pipeline state (initialized, analyzing, detector open, bundle present)
 * 2. Render a multi-line status string to a TextView
 *
 * ## Usage
 * ```kotlin
 * val manager = DiagnosticOverlayManager(
 *     diagnosticView = fragmentCameraBinding.diagnosticOverlay,
 *     mlPipeline = mlPipeline
 * )
 *
 * // From ML pipeline callbacks:
 * mlPipeline.onDiagnosticUpdate = { msg -> manager.update(msg) }
 * ```
 */
class DiagnosticOverlayManager(
    private val diagnosticView: TextView,
    private val mlPipeline: MlPipelineManager
) {

    /**
     * Update the diagnostic overlay with a status message and
     * current pipeline state flags.
     *
     * The overlay shows two lines:
     * - Line 1: The provided [msg] (e.g. "OD: 3 det, 45ms")
     * - Line 2: State flags (init, det, bundle, analyzing)
     */
    fun update(msg: String) {
        try {
            val init = mlPipeline.isOdInitialized()
            val analyzing = if (init) mlPipeline.isCurrentlyAnalyzing() else false
            val bundle = if (init) mlPipeline.objectDetectorHelper.lastResultBundle != null else false
            val detectorOpen = if (init) !mlPipeline.objectDetectorHelper.isClosed() else false
            diagnosticView.text = "$msg\ninit=$init det=$detectorOpen bundle=$bundle analyzing=$analyzing"
        } catch (_: Exception) {
            // Swallow — diagnostic overlay is non-critical
        }
    }
}
