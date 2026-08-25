package com.vyze.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Repository for local error logging.
 *
 * All write operations are non-blocking and best-effort. Errors during logging
 * are silently swallowed to avoid cascading failures.
 */
class ErrorLogRepository(context: Context) {

    private val errorLogDao: ErrorLogDao = VyzeDatabase.getInstance(context).errorLogDao()

    /** Log a message at the specified level. Best-effort, never throws. */
    suspend fun logError(
        level: String = "ERROR",
        tag: String,
        message: String,
        throwable: Throwable? = null
    ): Long {
        return try {
            withContext(Dispatchers.IO) {
                errorLogDao.insert(
                    ErrorLogEntity(
                        level = level,
                        tag = tag,
                        message = message,
                        stackTrace = throwable?.stackTraceToString()?.take(MAX_STACK_TRACE_LENGTH) ?: ""
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("ErrorLogRepo", "Failed to log error", e)
            -1L
        }
    }

    /** Log a warning. */
    suspend fun logWarning(tag: String, message: String): Long {
        return logError(level = "WARN", tag = tag, message = message)
    }

    /** Log an info message. */
    suspend fun logInfo(tag: String, message: String): Long {
        return logError(level = "INFO", tag = tag, message = message)
    }

    /** Get recent errors. */
    suspend fun getRecentErrors(limit: Int = 50): List<ErrorLogEntity> {
        return try {
            withContext(Dispatchers.IO) {
                errorLogDao.getRecentErrors(limit)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Observe recent errors. */
    fun observeRecentErrors(limit: Int = 50): Flow<List<ErrorLogEntity>> {
        return errorLogDao.observeRecentErrors(limit)
    }

    /** Get unreviewed error count. */
    suspend fun getUnreviewedCount(): Int {
        return try {
            withContext(Dispatchers.IO) {
                errorLogDao.getUnreviewedErrors().size
            }
        } catch (e: Exception) { 0 }
    }

    /** Mark an error as reviewed. */
    suspend fun markReviewed(errorId: Long) {
        try {
            withContext(Dispatchers.IO) {
                errorLogDao.markReviewed(errorId)
            }
        } catch (e: Exception) { /* best-effort */ }
    }

    /** Clear all logs older than the specified number of days. */
    suspend fun pruneOldLogs(daysOld: Int = 14) {
        try {
            withContext(Dispatchers.IO) {
                val cutoff = System.currentTimeMillis() - (daysOld * 24L * 60 * 60 * 1000)
                errorLogDao.deleteOlderThan(cutoff)
            }
        } catch (e: Exception) { /* best-effort */ }
    }

    /** Clear all error logs. */
    suspend fun clearAll() {
        try {
            withContext(Dispatchers.IO) {
                errorLogDao.deleteAll()
            }
        } catch (e: Exception) { /* best-effort */ }
    }

    companion object {
        private const val MAX_STACK_TRACE_LENGTH = 4000
    }
}
