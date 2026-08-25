package com.vyze.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for Vyze.
 *
 * Version 2 — [ScanEntity] + [ErrorLogEntity].
 */
@Database(
    entities = [ScanEntity::class, ErrorLogEntity::class],
    version = 2,
    exportSchema = false
)
abstract class VyzeDatabase : RoomDatabase() {

    abstract fun scanDao(): ScanDao
    abstract fun errorLogDao(): ErrorLogDao

    companion object {
        private const val DATABASE_NAME = "vyze_scans.db"

        @Volatile
        private var INSTANCE: VyzeDatabase? = null

        fun getInstance(context: Context): VyzeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    VyzeDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
