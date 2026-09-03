package com.vyze.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local medicine knowledge base for offline drug information lookup.
 *
 * When OCR extracts text from a medicine label, the extracted text is
 * cross-referenced against this database to provide accurate drug
 * information (name, dosage, frequency, warnings) without relying
 * solely on the VLM model's visual interpretation.
 *
 * Pre-populated with common Malaysian medicines via [MedicineDatabaseCallback].
 */
@Entity(
    tableName = "medicines",
    indices = [
        Index(value = ["searchName"], unique = true),
        Index(value = ["category"])
    ]
)
data class MedicineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Display name (e.g., "Diclac Retard"). */
    val name: String,

    /** Generic/active ingredient name (e.g., "Diclofenac sodium"). */
    val genericName: String,

    /** Dosage form and strength (e.g., "100mg modified-release tablet"). */
    val dosage: String,

    /** How to take (e.g., "One tablet daily after meals"). */
    val frequency: String,

    /** Warnings and precautions (e.g., "Avoid if allergic to NSAIDs. Take after meals."). */
    val warnings: String,

    /** Drug category (e.g., "Pain relief", "Antibiotic", "Vitamin"). */
    val category: String,

    /** Normalized search key — lowercase, no special chars. Used for fuzzy matching. */
    val searchName: String
)
