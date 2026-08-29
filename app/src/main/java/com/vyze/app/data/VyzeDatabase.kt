package com.vyze.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for Vyze.
 *
 * Version 4 — Added indexes on `vyze_memory` for efficient preference
 * lookups and environment memory pruning. Removed destructive migration
 * to preserve user memory and environment context across version updates.
 *
 * ## Migration Strategy
 * Each version upgrade uses explicit SQL migrations to add columns, tables,
 * or indexes without losing existing data. The `vyze_memory` table is
 * particularly important — it stores user preferences and environment
 * history that the VLM needs for personalized responses.
 */
@Database(
    entities = [ScanEntity::class, ErrorLogEntity::class, VyzeMemoryEntity::class],
    version = 4,
    exportSchema = false
)
abstract class VyzeDatabase : RoomDatabase() {

    abstract fun scanDao(): ScanDao
    abstract fun errorLogDao(): ErrorLogDao
    abstract fun memoryDao(): MemoryDao

    companion object {
        private const val DATABASE_NAME = "vyze_scans.db"

        @Volatile
        private var INSTANCE: VyzeDatabase? = null

        // ── Migrations ─────────────────────────────────────────────

        /**
         * Migration 2 → 3: Added `vyze_memory` table for adaptive personal intelligence.
         * This migration is safe — it only creates a new table, no existing data is touched.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `vyze_memory` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `category` TEXT NOT NULL,
                        `key` TEXT NOT NULL,
                        `value` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `metadata` TEXT NOT NULL DEFAULT ''
                    )"""
                )
            }
        }

        /**
         * Migration 3 → 4: Added performance indexes on `vyze_memory`.
         *
         * Indexes improve query speed for the most common operations:
         * - `idx_memory_category_key` — fast preference lookup by category+key
         * - `idx_memory_category_timestamp` — fast environment memory retrieval (recent first)
         * - `idx_memory_timestamp` — fast pruning of old memories
         *
         * This migration is additive only — no data is modified or deleted.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Index for fast preference lookups: memoryDao.get(category, key)
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_memory_category_key` " +
                    "ON `vyze_memory` (`category`, `key`)"
                )

                // Index for fast environment memory retrieval: memoryDao.getRecentEnvironment()
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_memory_category_timestamp` " +
                    "ON `vyze_memory` (`category`, `timestamp`)"
                )

                // Index for fast pruning: memoryDao.pruneOlderThan()
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_memory_timestamp` " +
                    "ON `vyze_memory` (`timestamp`)"
                )
            }
        }

        /** All registered migrations, in order. */
        private val ALL_MIGRATIONS = arrayOf(
            MIGRATION_2_3,
            MIGRATION_3_4
        )

        // ── Instance Creation ──────────────────────────────────────

        fun getInstance(context: Context): VyzeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    VyzeDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
