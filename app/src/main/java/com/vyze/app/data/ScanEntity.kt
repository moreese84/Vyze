package com.vyze.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a single scan result.
 *
 * Types: OCR, BARCODE, CURRENCY, COLOR, FACE, SCENE
 */
@Entity(tableName = "scan_history")
data class ScanEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Timestamp when the scan was captured (System.currentTimeMillis). */
    val timestamp: Long = System.currentTimeMillis(),

    /** Scan type: OCR, BARCODE, CURRENCY, COLOR, FACE, SCENE. */
    val type: String,

    /** The recognized content (text, denomination, color name, etc.). */
    val content: String,

    /** Optional supplementary info (e.g., confidence, spatial position). */
    val metadata: String = ""
)
