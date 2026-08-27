package com.vyze.app

import androidx.lifecycle.ViewModel

/**
 * Minimal ViewModel that bridges the ML pipeline initialization state
 * between [MainActivity] (splash screen holder) and [CameraFragment]
 * (pipeline initializer).
 *
 * [isMlReady] starts as `false`.  Once the background thread in
 * CameraFragment finishes binding all ML models, it sets this flag to
 * `true` on the main thread.  The splash screen's
 * [setKeepOnScreenCondition] polls this flag every frame and dismisses
 * the splash as soon as it becomes `true`.
 *
 * The flag is intentionally a plain `@Volatile` Boolean rather than
 * `LiveData` because `setKeepOnScreenCondition` needs a synchronous,
 * non-observable check that runs on every choreographer frame.
 */
class SplashViewModel : ViewModel() {

    companion object {
        /**
         * Shared flag — `false` while ML models are still binding,
         * `true` once [CameraFragment] has finished [MlPipelineManager.initialize].
         *
         * Written from the main thread (CameraFragment's post-init callback),
         * read from the main thread (choreographer frame callback via
         * `setKeepOnScreenCondition`).  `@Volatile` ensures the write is
         * immediately visible across threads if the reads ever move off the
         * main thread in the future.
         */
        @Volatile
        @JvmStatic
        var isMlReady: Boolean = false
            private set

        /**
         * Reset to `false` — call when the activity is recreated so the
         * splash holds again for the new ML initialization cycle.
         */
        @JvmStatic
        fun reset() {
            isMlReady = false
        }
    }

    /**
     * Called by [CameraFragment] on the main thread after
     * [MlPipelineManager.initialize] completes and the camera is set up.
     */
    fun markMlReady() {
        isMlReady = true
    }
}
