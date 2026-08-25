package com.vyze.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a logged error event.
 *
 * Used for local diagnostics without requiring crash reporting services.
 * Stores background failures, ML pipeline errors, and fatal crashes.
 */
@Entity(tableName = "error_log")
data class ErrorLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Timestamp when the error occurred (System.currentTimeMillis). */
    val timestamp: Long = System.currentTimeMillis(),

    /** Error level: DEBUG, INFO, WARN, ERROR, FATAL. */
    val level: String,

    /** Log tag (e.g., class name or component name). */
    val tag: String,

    /** Human-readable error message. */
    val message: String,

    /** Stack trace or supplementary detail (may be empty). */
    val stackTrace: String = "",

    /** Whether the error has been reviewed/dismissed by the user. */
    val reviewed: Boolean = false
)
