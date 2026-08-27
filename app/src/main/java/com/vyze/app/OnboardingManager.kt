package com.vyze.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Manages the first-run onboarding experience for Vyze.
 *
 * On first launch, delivers a spoken tutorial via [TTSManager]
 * with synchronized haptic pulses via [HapticManager] so visually impaired
 * users learn the gesture vocabulary through both sound and touch.
 *
 * The first-run flag is persisted in [SharedPreferences] under [PREF_IS_FIRST_LAUNCH]
 * so the tutorial plays only once across app restarts.
 *
 * ## Flow
 * 1. Splash screen holds while ML models initialize.
 * 2. Splash dismisses → [playWelcomeMessage] speaks the consolidated welcome.
 * 3. Camera feed is active behind the onboarding overlay.
 * 4. User taps screen → [dismissOnboarding] marks complete and hides overlay.
 */
class OnboardingManager(
    private val context: Context,
    private val ttsManager: TTSManager,
    private val hapticManager: HapticManager
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Returns `true` if this is the first time the app has been launched.
     * Checks the [PREF_IS_FIRST_LAUNCH] key (default `true`).
     */
    fun isFirstLaunch(): Boolean {
        return prefs.getBoolean(PREF_IS_FIRST_LAUNCH, true)
    }

    /**
     * Marks the first-launch flag as completed so the onboarding will not
     * play again on subsequent launches.
     */
    fun markOnboardingComplete() {
        prefs.edit().putBoolean(PREF_IS_FIRST_LAUNCH, false).apply()
    }

    /**
     * Speaks the consolidated welcome message on first launch.
     *
     * The message covers all gestures in a single utterance:
     * "Welcome to Vyze. Single tap anywhere to hear objects ahead."
     * "Double tap to read text. Long press for scene summaries."
     * "Tap screen to begin."
     *
     * @param onWelcomeFinished Optional callback invoked after the TTS
     *                          utterance is queued (not after it finishes
     *                          speaking — TTS is async).
     */
    fun playWelcomeMessage(onWelcomeFinished: (() -> Unit)? = null) {
        if (!isFirstLaunch()) {
            onWelcomeFinished?.invoke()
            return
        }

        Log.d(TAG, "First launch detected — playing welcome onboarding")

        handler.postDelayed({
            hapticManager.vibrateLongPress()
            ttsManager.speakImmediate(WELCOME_MESSAGE)
            onWelcomeFinished?.invoke()
        }, DELAY_WELCOME_MS)
    }

    /**
     * Plays the full sequential tutorial (welcome + individual instructions).
     * Kept for backward compatibility — new code should use [playWelcomeMessage].
     *
     * @param onComplete Optional callback invoked when the full tutorial finishes.
     */
    fun playTutorialIfFirstRun(onComplete: (() -> Unit)? = null) {
        if (!isFirstLaunch()) {
            onComplete?.invoke()
            return
        }

        Log.d(TAG, "First launch detected — playing onboarding tutorial")

        handler.postDelayed({
            hapticManager.vibrateLongPress()
            ttsManager.speakImmediate(WELCOME_MESSAGE)
        }, DELAY_WELCOME_MS)

        handler.postDelayed({
            hapticManager.vibrateTap()
            ttsManager.speak(SINGLE_TAP_INSTRUCTION)
        }, DELAY_SINGLE_TAP_MS)

        handler.postDelayed({
            hapticManager.vibrateDoubleTap()
            ttsManager.speak(DOUBLE_TAP_INSTRUCTION)
        }, DELAY_DOUBLE_TAP_MS)

        handler.postDelayed({
            hapticManager.vibrateLongPress()
            ttsManager.speak(LONG_PRESS_INSTRUCTION)
        }, DELAY_LONG_PRESS_MS)

        handler.postDelayed({
            hapticManager.vibrateWarning()
            ttsManager.speak(SWIPE_INSTRUCTION)
        }, DELAY_SWIPE_MS)

        handler.postDelayed({
            hapticManager.vibrateDoubleTap()
            ttsManager.speak(COMPLETION_MESSAGE)
            markOnboardingComplete()
            onComplete?.invoke()
        }, DELAY_COMPLETE_MS)
    }

    /**
     * Cancels any in-progress tutorial. Call this when the user manually
     * dismisses the tutorial or when the fragment is destroyed.
     */
    fun cancelTutorial() {
        handler.removeCallbacksAndMessages(null)
        ttsManager.stop()
    }

    companion object {
        private const val TAG = "OnboardingManager"
        private const val PREFS_NAME = "vyze_onboarding"

        /** SharedPreferences key for first-launch flag (default true). */
        const val PREF_IS_FIRST_LAUNCH = "is_first_launch"

        // Legacy key — kept for migration compatibility
        const val PREF_FIRST_RUN = "first_run"

        // ── Tutorial Messages ───────────────────────────────────────────────

        /**
         * Consolidated welcome message for first launch.
         * Covers all gestures in a single utterance so the user can
         * tap to begin immediately without waiting through a long sequence.
         */
        const val WELCOME_MESSAGE =
            "Welcome to Vyze. " +
            "Single tap anywhere to hear objects ahead. " +
            "Double tap to read text. " +
            "Long press for scene summaries. " +
            "Tap screen to begin."

        const val SINGLE_TAP_INSTRUCTION =
            "Tap once anywhere on the screen to detect objects around you. " +
            "I will tell you what I see and where it is."

        const val DOUBLE_TAP_INSTRUCTION =
            "Double tap anywhere to read text. " +
            "I will scan for signs, labels, and documents."

        const val LONG_PRESS_INSTRUCTION =
            "Press and hold to check the light level. " +
            "I will turn on the flashlight if it is dark."

        const val SWIPE_INSTRUCTION =
            "Swipe left or right to switch between camera and gallery modes."

        const val COMPLETION_MESSAGE =
            "That is everything. You are ready to go. " +
            "I am always here to help."

        // ── Timing (milliseconds) ───────────────────────────────────────────

        /** Delay before the welcome message plays. */
        const val DELAY_WELCOME_MS = 500L

        /** Delay before the single-tap instruction. */
        const val DELAY_SINGLE_TAP_MS = 4000L

        /** Delay before the double-tap instruction. */
        const val DELAY_DOUBLE_TAP_MS = 10_000L

        /** Delay before the long-press instruction. */
        const val DELAY_LONG_PRESS_MS = 17_000L

        /** Delay before the swipe instruction. */
        const val DELAY_SWIPE_MS = 23_000L

        /** Delay before the completion message. */
        const val DELAY_COMPLETE_MS = 28_000L
    }
}
