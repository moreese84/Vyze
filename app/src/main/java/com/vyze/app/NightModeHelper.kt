package com.vyze.app

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate

/**
 * Helper for toggling dark/light theme (night mode) across the app.
 *
 * Persists the user's choice to SharedPreferences and applies it
 * immediately via [AppCompatDelegate.setDefaultNightMode].
 *
 * ## Modes
 * - [AppCompatDelegate.MODE_NIGHT_NO] — Light theme always
 * - [AppCompatDelegate.MODE_NIGHT_YES] — Dark theme always
 * - [AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM] — Follow system setting
 * - [AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY] — Dark when battery saver is on
 */
class NightModeHelper(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Sets the night mode and persists the choice.
     *
     * @param mode One of [AppCompatDelegate.MODE_NIGHT_*] constants.
     */
    fun setNightMode(mode: Int) {
        prefs.edit().putInt(KEY_NIGHT_MODE, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /**
     * Returns the current night mode setting.
     */
    fun getNightMode(): Int {
        return prefs.getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    /**
     * Toggle between light and dark mode.
     * If currently light → switches to dark.
     * If currently dark → switches to light.
     * If following system → switches to dark.
     */
    fun toggleNightMode(): Int {
        val current = getNightMode()
        val newMode = when (current) {
            AppCompatDelegate.MODE_NIGHT_YES -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_YES
        }
        setNightMode(newMode)
        return newMode
    }

    /**
     * Apply the saved night mode to the given activity.
     * Call this in [Activity.onCreate] before [setContentView].
     */
    fun applyToActivity(activity: Activity) {
        AppCompatDelegate.setDefaultNightMode(getNightMode())
    }

    /**
     * Returns a human-readable name for the current mode.
     */
    fun getCurrentModeName(): String {
        return when (getNightMode()) {
            AppCompatDelegate.MODE_NIGHT_NO -> "Light"
            AppCompatDelegate.MODE_NIGHT_YES -> "Dark"
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> "System"
            AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY -> "Battery Saver"
            else -> "System"
        }
    }

    /**
     * Returns the string resource key for the current mode label.
     */
    fun getCurrentModeStringRes(): Int {
        return when (getNightMode()) {
            AppCompatDelegate.MODE_NIGHT_NO -> R.string.night_mode_light
            AppCompatDelegate.MODE_NIGHT_YES -> R.string.night_mode_dark
            else -> R.string.night_mode_system
        }
    }

    companion object {
        private const val PREFS_NAME = "vyze_theme"
        private const val KEY_NIGHT_MODE = "night_mode"
    }
}
