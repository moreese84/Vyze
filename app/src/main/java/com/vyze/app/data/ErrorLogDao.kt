package com.vyze.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for error log entries.
 */
@Dao
interface ErrorLogDao {

    @Insert
    suspend fun insert(log: ErrorLogEntity): Long

    /** Get the most recent N error entries. */
    @Query("SELECT * FROM error_log ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentErrors(limit: Int = 50): List<ErrorLogEntity>

    /** Observe recent errors as a reactive Flow. */
    @Query("SELECT * FROM error_log ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentErrors(limit: Int = 50): Flow<List<ErrorLogEntity>>

    /** Get errors filtered by level. */
    @Query("SELECT * FROM error_log WHERE level = :level ORDER BY timestamp DESC")
    suspend fun getErrorsByLevel(level: String): List<ErrorLogEntity>

    /** Get errors filtered by tag. */
    @Query("SELECT * FROM error_log WHERE tag = :tag ORDER BY timestamp DESC")
    suspend fun getErrorsByTag(tag: String): List<ErrorLogEntity>

    /** Get unreviewed errors. */
    @Query("SELECT * FROM error_log WHERE reviewed = 0 ORDER BY timestamp DESC")
    suspend fun getUnreviewedErrors(): List<ErrorLogEntity>

    /** Mark an error as reviewed. */
    @Query("UPDATE error_log SET reviewed = 1 WHERE id = :errorId")
    suspend fun markReviewed(errorId: Long)

    /** Get total error count. */
    @Query("SELECT COUNT(*) FROM error_log")
    suspend fun getErrorCount(): Int

    /** Delete all error logs. */
    @Query("DELETE FROM error_log")
    suspend fun deleteAll()

    /** Delete errors older than the specified number of days. */
    @Query("DELETE FROM error_log WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOlderThan(cutoffTimestamp: Long)
}
