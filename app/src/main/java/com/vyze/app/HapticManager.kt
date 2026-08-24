package com.vyze.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Provides distinct haptic feedback patterns for accessibility gesture confirmation.
 *
 * - [vibrateTap]: Quick 30ms light pulse for single-tap confirmation.
 * - [vibrateDoubleTap]: Two quick 40ms pulses separated by 60ms for double-tap / OCR.
 * - [vibrateLongPress]: Sustained 150ms firm pulse for long-press light level updates.
 * - [vibrateWarning]: Sharp triple-burst pulse for low-light or system errors.
 */
class HapticManager(context: Context) {

    private val vibrator: Vibrator? = createVibrator(context)

    /**
     * A quick 30 ms light pulse for single-tap confirmation.
     */
    fun vibrateTap() {
        vibrate(30, VibrationEffect.DEFAULT_AMPLITUDE)
    }

    /**
     * Two quick 40 ms pulses separated by 60 ms for double-tap / OCR confirmation.
     * Pattern: [delay 0ms, vibrate 40ms, delay 60ms, vibrate 40ms]
     */
    fun vibrateDoubleTap() {
        vibratePattern(longArrayOf(0, 40, 60, 40), 0)
    }

    /**
     * A sustained 150 ms firm pulse for long-press light level updates.
     */
    fun vibrateLongPress() {
        vibrate(150, VibrationEffect.DEFAULT_AMPLITUDE)
    }

    /**
     * A sharp triple-burst pulse for low-light warnings or system errors.
     * Pattern: [delay 0ms, vibrate 50ms, delay 40ms, vibrate 50ms, delay 40ms, vibrate 50ms]
     */
    fun vibrateWarning() {
        vibratePattern(longArrayOf(0, 50, 40, 50, 40, 50), 0)
    }

    /**
     * Perform a simple vibration with the given duration and amplitude.
     */
    private fun vibrate(durationMs: Long, amplitude: Int) {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(
                    VibrationEffect.createOneShot(
                        durationMs,
                        amplitude.coerceIn(1, 255)
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(durationMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed", e)
        }
    }

    /**
     * Perform a vibration from a timing pattern.
     *
     * @param timings  Pattern array: alternating off/on durations starting with off.
     *                 e.g. [0, 50, 40, 50, 40, 50] → immediate triple-burst.
     * @param repeat   Index into timings at which to repeat (-1 = no repeat).
     */
    private fun vibratePattern(timings: LongArray, repeat: Int) {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(
                    VibrationEffect.createWaveform(timings, repeat)
                )
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(timings, repeat)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration pattern failed", e)
        }
    }

    /**
     * Safely obtain the system Vibrator service.
     */
    private fun createVibrator(context: Context): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager =
                    context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to obtain Vibrator service", e)
            null
        }
    }

    /**
     * Release resources. Called during lifecycle teardown.
     */
    fun cancel() {
        vibrator?.cancel()
    }

    companion object {
        private const val TAG = "HapticManager"
    }
}
