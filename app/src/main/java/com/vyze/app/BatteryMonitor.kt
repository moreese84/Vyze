package com.vyze.app

import android.content.Context
import android.os.BatteryManager
import android.util.Log

/**
 * Pure battery threshold evaluation for accessibility warnings.
 *
 * Extracted from [CameraFragment] to separate hardware monitoring from
 * UI lifecycle concerns. This class has NO dependency on:
 * - Fragment lifecycle
 * - TTS engine
 * - Haptic feedback
 * - ViewModel or UI state
 *
 * ## Responsibilities
 * 1. Read battery level from the system BatteryManager
 * 2. Compare against configurable low/critical thresholds
 * 3. Fire callbacks when thresholds are crossed (edge-triggered, not level-triggered)
 *
 * ## Usage
 * ```kotlin
 * val monitor = BatteryMonitor(
 *     context = applicationContext,
 *     onLowBattery = { level -> ttsManager.speak("Battery low: $level%") },
 *     onCriticalBattery = { level -> hapticManager.vibrateWarning() },
 *     onBatteryLevelChanged = { level -> viewModel.updateBatteryLevel(level) }
 * )
 *
 * // In a Runnable or coroutine:
 * monitor.checkBatteryLevel()
 * ```
 */
class BatteryMonitor(
    private val context: Context,
    private val onLowBattery: (level: Int) -> Unit,
    private val onCriticalBattery: (level: Int) -> Unit,
    private val onBatteryLevelChanged: (level: Int) -> Unit = {}
) {

    private val TAG = "BatteryMonitor"

    /**
     * Last warning level that was announced.
     * Prevents re-announcing the same threshold (e.g. 15% fires once,
     * not every 2 minutes while the battery stays at 15%).
     */
    private var lastWarningLevel: Int = -1

    // ── Core Logic ────────────────────────────────────────────────

    /**
     * Check the current battery level and fire callbacks if thresholds
     * are crossed.
     *
     * @return The current battery level (0–100), or null if unavailable.
     */
    fun checkBatteryLevel(): Int? {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level == null) {
            Log.w(TAG, "Unable to read battery level")
            return null
        }

        onBatteryLevelChanged(level)

        if (level <= CRITICAL_THRESHOLD && lastWarningLevel > CRITICAL_THRESHOLD) {
            lastWarningLevel = CRITICAL_THRESHOLD
            Log.w(TAG, "Battery CRITICAL: $level%")
            onCriticalBattery(level)
        } else if (level <= LOW_THRESHOLD && lastWarningLevel > LOW_THRESHOLD) {
            lastWarningLevel = LOW_THRESHOLD
            Log.w(TAG, "Battery LOW: $level%")
            onLowBattery(level)
        }

        return level
    }

    /**
     * Reset the warning state. Call after the user plugs in the charger
     * or dismisses a battery warning, so the next low battery event fires
     * a fresh announcement.
     */
    fun resetWarningState() {
        lastWarningLevel = -1
    }

    // ── Companion ─────────────────────────────────────────────────

    companion object {
        /** Battery level at which a "low" warning is triggered. */
        const val LOW_THRESHOLD = 15

        /** Battery level at which a "critical" warning is triggered. */
        const val CRITICAL_THRESHOLD = 5

        /** Interval in milliseconds between battery checks. */
        const val CHECK_INTERVAL_MS = 120_000L
    }
}
