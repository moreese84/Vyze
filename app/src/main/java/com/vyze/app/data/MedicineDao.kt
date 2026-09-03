package com.vyze.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object for [MedicineEntity].
 *
 * Supports fuzzy lookup by name substring and exact search key matching.
 * All queries run on Dispatchers.IO (Room enforces this for suspend functions).
 */
@Dao
interface MedicineDao {

    /** Insert or replace a medicine entry (used during database pre-population). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(medicines: List<MedicineEntity>)

    /** Find a medicine by exact normalized search key (e.g., "diclac retard"). */
    @Query("SELECT * FROM medicines WHERE searchName = :searchKey LIMIT 1")
    suspend fun findBySearchKey(searchKey: String): MedicineEntity?

    /**
     * Fuzzy search — find medicines whose name contains the query substring.
     * Returns up to 3 matches ranked by relevance.
     *
     * Used when OCR text doesn't exactly match a medicine name but is close
     * (e.g., OCR reads "DICLAC" and we match "Diclac Retard").
     */
    @Query("""
        SELECT * FROM medicines 
        WHERE searchName LIKE '%' || :query || '%' 
           OR name LIKE '%' || :query || '%'
        LIMIT 3
    """)
    suspend fun searchByName(query: String): List<MedicineEntity>

    /** Get all medicines in a category (e.g., "Pain relief"). */
    @Query("SELECT * FROM medicines WHERE category = :category")
    suspend fun getByCategory(category: String): List<MedicineEntity>

    /** Count total entries (used to verify pre-population). */
    @Query("SELECT COUNT(*) FROM medicines")
    suspend fun count(): Int
}
