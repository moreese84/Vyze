package com.vyze.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for scan history.
 */
@Dao
interface ScanDao {

    /** Insert a scan entry. Returns the generated row ID. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScan(scan: ScanEntity): Long

    /** Get the most recent N scans, ordered by timestamp descending. */
    @Query("SELECT * FROM scan_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentScans(limit: Int = 3): List<ScanEntity>

    /** Observe the most recent N scans as a reactive Flow. */
    @Query("SELECT * FROM scan_history ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentScans(limit: Int = 3): Flow<List<ScanEntity>>

    /** Get all scans of a specific type. */
    @Query("SELECT * FROM scan_history WHERE type = :type ORDER BY timestamp DESC")
    suspend fun getScansByType(type: String): List<ScanEntity>

    /** Get all scans. */
    @Query("SELECT * FROM scan_history ORDER BY timestamp DESC")
    suspend fun getAllScans(): List<ScanEntity>

    /** Delete all scan history. */
    @Query("DELETE FROM scan_history")
    suspend fun deleteAllScans()

    /** Delete scans older than the given timestamp. */
    @Query("DELETE FROM scan_history WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteScansOlderThan(cutoffTimestamp: Long)

    /** Get total scan count. */
    @Query("SELECT COUNT(*) FROM scan_history")
    suspend fun getScanCount(): Int
}
