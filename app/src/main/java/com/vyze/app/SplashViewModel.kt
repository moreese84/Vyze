package com.vyze.app

import androidx.lifecycle.ViewModel

/**
 * Minimal ViewModel that controls the splash screen duration.
 *
 * Previously this held `isMlReady` to keep the splash visible while ML models
 * loaded. Now LoadingFragment handles the full init pipeline, so the splash
 * dismisses immediately — LoadingFragment is the real loading screen.
 *
 * The flag is kept as `true` so [MainActivity]'s
 * [setKeepOnScreenCondition] releases the splash instantly on launch.
 */
class SplashViewModel : ViewModel() {

    companion object {
        /**
         * Shared flag — always `true` so the splash screen dismisses immediately.
         * LoadingFragment is now responsible for showing the real loading state.
         */
        @Volatile
        @JvmStatic
        var isMlReady: Boolean = true
            private set

        /** Reset to false — kept for API compatibility but no longer used. */
        @JvmStatic
        fun reset() {
            isMlReady = true
        }
    }

    /**
     * Called by [CameraFragment] for API compatibility — now a no-op since
     * the splash is already dismissed by LoadingFragment.
     */
    fun markMlReady() {
        isMlReady = true
    }
}
