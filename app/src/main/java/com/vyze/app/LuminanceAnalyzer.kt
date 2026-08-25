package com.vyze.app

import android.media.Image
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.ArrayDeque

/**
 * Analyzes camera frames for ambient luminance with hysteresis-based torch
 * triggering and rolling frame averaging.
 *
 * ## Hysteresis State Buffer
 * A dual-threshold system prevents torch flicker in transitional lighting:
 *  - Torch **ON**  only when averaged luminance falls **below 35 lux**.
 *  - Torch **OFF** only when averaged luminance rises **above 65 lux**.
 *  - Between 36–64 lux (the deadband) the current torch state is preserved.
 *
 * ## Frame Averaging
 * A rolling buffer of the last [FRAME_BUFFER_SIZE] (5) frame readings smooths
 * out brief shadows, camera movement spikes, and auto-exposure oscillation.
 * The average of this buffer is used for the hysteresis decision.
 *
 * ## Memory Safety
 * The YUV Y-plane luminance calculation performs zero object allocations per
 * frame — it reads raw bytes into a reused [ByteArray] buffer and accumulates
 * the sum in a primitive `Long`.
 */
class LuminanceAnalyzer(
    private val onLuminanceResult: (isDark: Boolean, meanLuminance: Double) -> Unit
) : ImageAnalysis.Analyzer {

    // ── Hysteresis Thresholds ───────────────────────────────────────────────

    companion object {
        /**
         * Legacy single-threshold kept for backward compatibility with code
         * that references [DARK_THRESHOLD] directly.
         */
        const val DARK_THRESHOLD = 50.0

        /** Torch turns ON when averaged luminance drops below this value. */
        const val TORCH_ON_THRESHOLD = 35.0

        /** Torch turns OFF when averaged luminance rises above this value. */
        const val TORCH_OFF_THRESHOLD = 65.0

        /** Number of frames in the rolling average buffer. */
        const val FRAME_BUFFER_SIZE = 5
    }

    // ── Frame Averaging Buffer ──────────────────────────────────────────────

    /**
     * Circular buffer holding the most recent [FRAME_BUFFER_SIZE] mean
     * luminance readings. Operates as a FIFO queue — oldest reading is
     * evicted when the buffer is full.
     *
     * Using [ArrayDeque] avoids boxing overhead vs. a `MutableList<Double>`
     * and provides O(1) addLast / removeFirst.
     */
    private val frameBuffer = ArrayDeque<Double>(FRAME_BUFFER_SIZE)

    /**
     * Running sum of values currently in [frameBuffer].
     * Maintained incrementally to avoid re-summing the buffer each frame.
     */
    private var bufferSum: Double = 0.0

    // ── Hysteresis State ────────────────────────────────────────────────────

    /**
     * The current torch recommendation based on the hysteresis dual-threshold.
     * `true` = torch should be ON, `false` = torch should be OFF.
     *
     * Updated only when the averaged luminance crosses the ON (< 35) or
     * OFF (> 65) thresholds. The 36–64 lux deadband preserves this state.
     */
    @Volatile
    private var hysteresisTorchState: Boolean = false

    // ── Pre-allocated Buffers (memory safety) ───────────────────────────────

    /**
     * Reused byte array for Y-plane luminance extraction.
     * Sized to the maximum expected Y-plane size; trimmed to actual
     * [java.nio.ByteBuffer.remaining] on each frame via [ByteArray] offset.
     *
     * Allocated once, reused across all frames — zero per-frame GC pressure.
     */
    private var yBuffer: ByteArray = ByteArray(0)

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Standalone analyzer that closes the imageProxy when done.
     * Used when running luminance analysis independently.
     */
    override fun analyze(imageProxy: ImageProxy) {
        try {
            analyzeLuminance(imageProxy)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Extracts luminance data from the Y plane WITHOUT closing the imageProxy.
     * This allows the composite analyzer to share the frame with other analyzers
     * (e.g., ObjectDetectorHelper) that handle their own imageProxy lifecycle.
     *
     * Performs:
     *  1. Fast YUV Y-plane byte extraction with zero per-frame allocations.
     *  2. Mean luminance calculation.
     *  3. Rolling frame average update.
     *  4. Hysteresis dual-threshold torch state update.
     *  5. Callback invocation with averaged results.
     */
    fun analyzeLuminance(imageProxy: ImageProxy) {
        val image: Image = imageProxy.image ?: return

        // ── Step 1: Y-plane luminance extraction (zero-allocation path) ────
        val yPlane = image.planes[0]
        val yBufferNio = yPlane.buffer
        val ySize = yBufferNio.remaining()

        // Grow the reused buffer only when the frame size increases
        if (ySize > yBuffer.size) {
            yBuffer = ByteArray(ySize)
        }

        // Read directly into the reused buffer — no new allocation
        yBufferNio.get(yBuffer, 0, ySize)

        // ── Step 2: Mean luminance calculation ─────────────────────────────
        var sum = 0L
        // Manual loop is faster than kotlin.sumOf and avoids Int boxing
        for (i in 0 until ySize) {
            sum += (yBuffer[i].toInt() and 0xFF)
        }
        val frameMean = sum.toDouble() / ySize

        // ── Step 3: Rolling frame average ──────────────────────────────────
        updateFrameBuffer(frameMean)
        val averagedLuminance = if (frameBuffer.size > 0) {
            bufferSum / frameBuffer.size
        } else {
            frameMean
        }

        // ── Step 4: Hysteresis torch state ─────────────────────────────────
        updateHysteresis(averagedLuminance)

        // ── Step 5: Callback ───────────────────────────────────────────────
        onLuminanceResult(hysteresisTorchState, averagedLuminance)
    }

    /**
     * Returns the current hysteresis-based torch state without triggering
     * a new analysis. Useful for querying the last known state.
     */
    fun isTorchRecommended(): Boolean = hysteresisTorchState

    /**
     * Returns the current averaged luminance from the rolling buffer.
     * Returns 0.0 if no frames have been analyzed yet.
     */
    fun currentAveragedLuminance(): Double =
        if (frameBuffer.size > 0) bufferSum / frameBuffer.size else 0.0

    /**
     * Resets all internal state. Call when the camera is restarted or
     * the fragment is destroyed to prevent stale readings.
     */
    fun reset() {
        frameBuffer.clear()
        bufferSum = 0.0
        hysteresisTorchState = false
    }

    // ── Internal Helpers ────────────────────────────────────────────────────

    /**
     * Updates the rolling frame buffer with a new luminance reading.
     *
     * If the buffer is full, the oldest reading is removed and its value
     * subtracted from [bufferSum] before the new value is added. This keeps
     * the running sum in O(1) without re-iterating the buffer.
     */
    private fun updateFrameBuffer(newReading: Double) {
        if (frameBuffer.size >= FRAME_BUFFER_SIZE) {
            // Evict oldest reading and subtract from running sum
            val evicted = frameBuffer.removeFirst()
            bufferSum -= evicted
        }
        frameBuffer.addLast(newReading)
        bufferSum += newReading
    }

    /**
     * Updates the hysteresis torch state based on the averaged luminance.
     *
     * Dual-threshold logic:
     *  - averagedLuminance < 35 → torch ON  (dark environment)
     *  - averagedLuminance > 65 → torch OFF (bright environment)
     *  - 36–64 lux deadband   → no change   (prevents flicker)
     */
    private fun updateHysteresis(averagedLuminance: Double) {
        hysteresisTorchState = when {
            averagedLuminance < TORCH_ON_THRESHOLD  -> true   // Dark → torch ON
            averagedLuminance > TORCH_OFF_THRESHOLD -> false  // Bright → torch OFF
            else -> hysteresisTorchState                     // Deadband → no change
        }
    }
}
