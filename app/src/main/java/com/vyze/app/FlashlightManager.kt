package com.vyze.app

import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.ExperimentalGetImage

/**
 * Manages the camera flashlight / torch via CameraX [Camera.cameraControl].
 *
 * Provides:
 * - [toggleTorch]: Turn the torch on or off.
 * - [isTorchOn]: Query the current torch state.
 * - [autoTorch]: Automatically enable the torch when the environment is dark,
 *   and disable it when lighting is sufficient.
 */
class FlashlightManager {

    /**
     * The CameraX [Camera] instance that owns the cameraControl used for torch toggling.
     * Must be set from [CameraFragment] after the camera is bound.
     */
    var camera: Camera? = null

    /**
     * Tracks the last-known torch state to avoid redundant toggle commands.
     */
    @Volatile
    private var torchEnabled: Boolean = false

    /**
     * Toggle the torch on or off.
     *
     * @param enable `true` to turn the torch on, `false` to turn it off.
     */
    fun toggleTorch(enable: Boolean) {
        val cam = camera
        if (cam == null) {
            Log.w(TAG, "Camera not available; cannot toggle torch")
            return
        }

        try {
            if (cam.cameraInfo.hasFlashUnit()) {
                cam.cameraControl.enableTorch(enable)
                torchEnabled = enable
                Log.d(TAG, "Torch ${if (enable) "ON" else "OFF"}")
            } else {
                Log.w(TAG, "This camera has no flash unit")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle torch", e)
        }
    }

    /**
     * Returns the current known torch state.
     */
    fun isTorchOn(): Boolean = torchEnabled

    /**
     * Sets the torch state based on the hysteresis decision from
     * [LuminanceAnalyzer].
     *
     * The dual-threshold hysteresis (ON < 35 lux, OFF > 65 lux) is already
     * computed by the analyzer, so this method simply applies the result
     * without its own comparison logic.
     *
     * @param shouldBeOn `true` if the hysteresis says torch should be on,
     *                    `false` if it should be off.
     */
    fun autoTorch(shouldBeOn: Boolean) {
        if (shouldBeOn != torchEnabled) {
            toggleTorch(shouldBeOn)
        }
    }

    /**
     * Toggle the torch if the user explicitly requests it (e.g., on long-press).
     * If the environment is dark and the torch is off, turn it on.
     * If the torch is already on, turn it off.
     */
    fun userToggle() {
        toggleTorch(!torchEnabled)
    }

    companion object {
        private const val TAG = "FlashlightManager"
    }
}
