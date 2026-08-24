package com.vyze.app

import android.app.Application
import android.os.Build
import android.util.Log
import kotlin.system.exitProcess

/**
 * Global [Application] subclass for Vyze.
 *
 * - Installs a default [Thread.UncaughtExceptionHandler] that logs the error
 *   and gracefully finishes the process instead of showing a crash dialog.
 * - Provides a central place for future global initialisation (analytics, work manager, etc.).
 */
class VyzeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installGlobalExceptionHandler()
        Log.i(TAG, "VyzeApplication onCreate – API ${Build.VERSION.SDK_INT}")
    }

    /**
     * Catches uncaught exceptions on **any** thread so that camera / sensor edge-cases
     * (e.g. a RuntimeException from a CameraX callback or an ML Kit timeout on a
     * background thread) do not crash the app for the user.
     */
    private fun installGlobalExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread '${thread.name}'", throwable)

            // Attempt to notify the user via logcat, then exit cleanly.
            // We intentionally do *not* show an ANR / crash dialog.
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(2)
        }
    }

    companion object {
        private const val TAG = "VyzeApplication"
    }
}
