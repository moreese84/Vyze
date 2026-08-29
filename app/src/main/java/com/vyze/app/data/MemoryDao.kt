package com.vyze.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for [VyzeMemoryEntity].
 *
 * Supports CRUD operations for user preferences and visual environment history.
 * All write operations run on Dispatchers.IO via suspend.
 */
@Dao
interface MemoryDao {

    /** Insert or update a memory entry (replaces on category+key conflict). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: VyzeMemoryEntity): Long

    /** Get a specific memory by category and key. */
    @Query("SELECT * FROM vyze_memory WHERE category = :category AND `key` = :key LIMIT 1")
    suspend fun get(category: String, key: String): VyzeMemoryEntity?

    /** Get all memories for a given category. */
    @Query("SELECT * FROM vyze_memory WHERE category = :category ORDER BY timestamp DESC")
    suspend fun getAllByCategory(category: String): List<VyzeMemoryEntity>

    /** Observe all memories for a given category as a reactive Flow. */
    @Query("SELECT * FROM vyze_memory WHERE category = :category ORDER BY timestamp DESC")
    fun observeByCategory(category: String): Flow<List<VyzeMemoryEntity>>

    /** Get the most recent N memories across all categories. */
    @Query("SELECT * FROM vyze_memory ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<VyzeMemoryEntity>

    /** Get the most recent N environment memories. */
    @Query("SELECT * FROM vyze_memory WHERE category = 'environment' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentEnvironment(limit: Int = 10): List<VyzeMemoryEntity>

    /** Get all preference memories. */
    @Query("SELECT * FROM vyze_memory WHERE category = 'preference' ORDER BY `key` ASC")
    suspend fun getAllPreferences(): List<VyzeMemoryEntity>

    /** Delete a specific memory by ID. */
    @Query("DELETE FROM vyze_memory WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Delete all memories for a given category. */
    @Query("DELETE FROM vyze_memory WHERE category = :category")
    suspend fun deleteByCategory(category: String)

    /** Delete memories older than the given timestamp. */
    @Query("DELETE FROM vyze_memory WHERE timestamp < :cutoffTimestamp")
    suspend fun pruneOlderThan(cutoffTimestamp: Long)

    /** Get total memory count. */
    @Query("SELECT COUNT(*) FROM vyze_memory")
    suspend fun getCount(): Int
}
