package com.vyze.app

import android.app.Application
import android.util.Log
import com.vyze.app.data.ErrorLogRepository
import com.vyze.app.data.InteractionDao
import com.vyze.app.data.MemoryDao
import com.vyze.app.data.ScanRepository
import com.vyze.app.data.VyzeDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application class for Vyze.
 *
 * Acts as a lightweight service locator for dependency injection — provides
 * singleton instances of shared managers and repositories. This avoids the
 * overhead and complexity of Hilt/Koin while keeping dependencies testable.
 *
 * Also installs a global [Thread.UncaughtExceptionHandler] that logs fatal
 * errors to the local error log database before crashing.
 */
class VyzeApplication : Application() {

    /** Application-scoped coroutine scope for background work. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Lazy Singletons ──────────────────────────────────────────────

    /** Singleton database instance shared across the app. */
    val database: VyzeDatabase by lazy {
        VyzeDatabase.getInstance(applicationContext)
    }

    /** Repository for scan history. */
    val scanRepository: ScanRepository by lazy {
        ScanRepository(applicationContext)
    }

    /** Repository for error logging. */
    val errorLogRepository: ErrorLogRepository by lazy {
        ErrorLogRepository(applicationContext)
    }

    /** Memory DAO for adaptive personal intelligence. */
    val memoryDao: MemoryDao by lazy {
        database.memoryDao()
    }

    /** DAO for interaction records (image embeddings + past conversations). */
    val interactionDao: InteractionDao by lazy {
        database.interactionDao()
    }

    /**
     * Singleton VyzeCoreController, created by LoadingFragment after VLM init
     * and shared with CameraFragment so the initialized engine is reused.
     */
    @Volatile
    var coreController: VyzeCoreController? = null

    /** Singleton TTSManager. Managed externally via TtsViewModel. */
    val ttsManager: TTSManager by lazy {
        TTSManager(applicationContext).apply {
            applySettings(applicationContext)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        CrashLogFile.init(this)
        installGlobalErrorHandler()
        pruneOldErrorLogs()
        Log.d(TAG, "VyzeApplication created")
    }

    /**
     * Prune diagnostic error logs older than 7 days on every cold start.
     * Prevents unbounded growth of the error_log table and limits the
     * retention window for potentially sensitive diagnostic data.
     */
    private fun pruneOldErrorLogs() {
        applicationScope.launch {
            try {
                errorLogRepository.pruneOldLogs(daysOld = 7)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to prune old error logs", e)
            }
        }
    }

    // ── Global Error Handler ──────────────────────────────────────────

    /**
     * Installs a global [Thread.UncaughtExceptionHandler] that logs fatal
     * errors to the Room error log before the app crashes.
     */
    private fun installGlobalErrorHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val errorMessage = buildString {
                append("FATAL: ${throwable.javaClass.simpleName}: ${throwable.message}")
                append("\nThread: ${thread.name}")
                throwable.stackTrace.take(5).forEach { element ->
                    append("\n  at $element")
                }
            }

            Log.e(TAG, errorMessage, throwable)

            // Write to crash log file (synchronous — survives native crashes)
            CrashLogFile.logError("UncaughtHandler", errorMessage, throwable)
            CrashLogFile.flush()

            // Log to Room database (best-effort, non-blocking)
            applicationScope.launch {
                try {
                    errorLogRepository.logError(
                        level = "FATAL",
                        tag = thread.name,
                        message = errorMessage,
                        throwable = throwable
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to log fatal error to database", e)
                }
            }

            // Call original handler (will crash the app)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "VyzeApplication"

        /** Convenience accessor for the Application instance. */
        fun from(app: android.content.Context): VyzeApplication {
            return app.applicationContext as VyzeApplication
        }
    }
}
