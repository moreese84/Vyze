package com.vyze.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Manages the first-run onboarding experience for Vyze.
 *
 * On first launch, delivers a sequential audio tutorial via [TTSManager]
 * with synchronized haptic pulses via [HapticManager] so visually impaired
 * users learn the gesture vocabulary through both sound and touch.
 *
 * The first-run flag is persisted in [SharedPreferences] under [PREF_FIRST_RUN]
 * so the tutorial plays only once across app restarts.
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
     */
    fun isFirstRun(): Boolean {
        return prefs.getBoolean(PREF_FIRST_RUN, true)
    }

    /**
     * Marks the first-run flag as completed so the tutorial will not
     * play again on subsequent launches.
     */
    fun markOnboardingComplete() {
        prefs.edit().putBoolean(PREF_FIRST_RUN, false).apply()
    }

    /**
     * Plays the onboarding tutorial if this is the first run.
     *
     * The tutorial speaks each gesture description sequentially, with a
     * haptic pulse synchronized to each segment so the user receives
     * tactile confirmation of each instruction.
     *
     * Call this from [CameraFragment.onViewCreated] or equivalent.
     * The tutorial is non-blocking — it uses delayed callbacks so it
     * does not hold the main thread.
     *
     * @param onComplete Optional callback invoked when the full tutorial finishes.
     */
    fun playTutorialIfFirstRun(onComplete: (() -> Unit)? = null) {
        if (!isFirstRun()) {
            onComplete?.invoke()
            return
        }

        Log.d(TAG, "First run detected — playing onboarding tutorial")

        // Step 0: Welcome message + greeting haptic
        handler.postDelayed({
            hapticManager.vibrateLongPress()
            ttsManager.speakImmediate(WELCOME_MESSAGE)
        }, DELAY_WELCOME_MS)

        // Step 1: Single-tap instruction
        handler.postDelayed({
            hapticManager.vibrateTap()
            ttsManager.speak(SINGLE_TAP_INSTRUCTION)
        }, DELAY_SINGLE_TAP_MS)

        // Step 2: Double-tap instruction
        handler.postDelayed({
            hapticManager.vibrateDoubleTap()
            ttsManager.speak(DOUBLE_TAP_INSTRUCTION)
        }, DELAY_DOUBLE_TAP_MS)

        // Step 3: Long-press instruction
        handler.postDelayed({
            hapticManager.vibrateLongPress()
            ttsManager.speak(LONG_PRESS_INSTRUCTION)
        }, DELAY_LONG_PRESS_MS)

        // Step 4: Swipe instruction
        handler.postDelayed({
            hapticManager.vibrateWarning()
            ttsManager.speak(SWIPE_INSTRUCTION)
        }, DELAY_SWIPE_MS)

        // Step 5: Completion
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
        const val PREF_FIRST_RUN = "first_run"

        // ── Tutorial Messages ───────────────────────────────────────────────

        const val WELCOME_MESSAGE =
            "Welcome to Vyze. I am your eyes. " +
            "Let me teach you how to use me."

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
        // Delays are chosen to allow TTS to finish each segment before
        // the next one starts. Adjust if TTS speed settings change.

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
