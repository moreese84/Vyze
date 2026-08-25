package com.vyze.app

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Specialized OCR mode for parsing prescription labels and medicine packaging.
 *
 * Extracts:
 * - Drug name and dosage (e.g., "500mg", "10ml")
 * - Frequency instructions (e.g., "twice daily", "every 8 hours")
 * - Warnings and contraindications
 * - Expiry dates
 * - Active ingredients
 *
 * Safety: Always speaks dosage information clearly and with emphasis on warnings.
 */
class MedicineReaderHelper {

    private val TAG = "MedicineReaderHelper"

    private var textRecognizer: TextRecognizer? = null
    private var isActive = false

    // ── Lifecycle ─────────────────────────────────────────────────────

    fun enterMedicineMode() {
        if (isActive) return
        isActive = true
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        Log.d(TAG, "Medicine reader mode activated")
    }

    fun exitMedicineMode() {
        isActive = false
        textRecognizer?.close()
        textRecognizer = null
        Log.d(TAG, "Medicine reader mode deactivated")
    }

    fun isMedicineModeActive(): Boolean = isActive

    // ── Frame Processing ──────────────────────────────────────────────

    /**
     * Processes a camera frame for medicine label OCR.
     *
     * @param bitmap          Camera frame bitmap.
     * @param rotationDegrees Rotation applied by CameraX.
     * @param onSuccess       Callback with structured medicine information.
     * @param onError         Callback if OCR fails.
     */
    fun processFrame(
        bitmap: Bitmap,
        rotationDegrees: Int,
        onSuccess: (medicineInfo: MedicineInfo) -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (!isActive || textRecognizer == null) {
            onError(IllegalStateException("Medicine mode not active"))
            return
        }

        try {
            val image = InputImage.fromBitmap(bitmap, rotationDegrees)
            textRecognizer!!.process(image)
                .addOnSuccessListener { result ->
                    val info = parseMedicineLabel(result.text)
                    onSuccess(info)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Medicine OCR failed", e)
                    onError(e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create InputImage for medicine mode", e)
            onError(e)
        }
    }

    /**
     * Synchronous processing for pipeline integration.
     */
    fun processFrameSync(bitmap: Bitmap, rotationDegrees: Int): MedicineInfo? {
        if (!isActive || textRecognizer == null) return null

        val latch = CountDownLatch(1)
        var result: MedicineInfo? = null

        try {
            val image = InputImage.fromBitmap(bitmap, rotationDegrees)
            textRecognizer!!.process(image)
                .addOnSuccessListener { ocrResult ->
                    result = parseMedicineLabel(ocrResult.text)
                    latch.countDown()
                }
                .addOnFailureListener { latch.countDown() }

            latch.await(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "Sync medicine OCR failed", e)
        }

        return result
    }

    // ── Label Parsing ─────────────────────────────────────────────────

    /**
     * Parses raw OCR text into structured [MedicineInfo].
     */
    fun parseMedicineLabel(rawText: String): MedicineInfo {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val fullText = lines.joinToString(" ")

        return MedicineInfo(
            rawText = rawText,
            drugName = extractDrugName(lines),
            dosages = extractDosages(fullText),
            frequencies = extractFrequencies(fullText),
            warnings = extractWarnings(fullText),
            expiryDate = extractExpiryDate(fullText),
            activeIngredients = extractActiveIngredients(fullText),
            instructions = extractInstructions(fullText)
        )
    }

    // ── Extraction Helpers ────────────────────────────────────────────

    /**
     * Extracts drug name — typically the first prominent line.
     * Heuristic: the first line that isn't a dosage, warning, or date.
     */
    private fun extractDrugName(lines: List<String>): String {
        for (line in lines) {
            // Skip lines that look like dosages, warnings, or dates
            if (line.matches(Regex(".*\\d+\\s*(mg|ml|mcg|g|tablet|capsule).*", RegexOption.IGNORE_CASE))) continue
            if (line.contains(Regex("(warning|caution|danger|expire|exp|mfg|batch)", RegexOption.IGNORE_CASE))) continue
            if (line.length in 2..60) return line
        }
        return "Unknown medication"
    }

    /**
     * Extracts dosage amounts: "500mg", "10ml", "250mg/5ml", etc.
     */
    private fun extractDosages(text: String): List<String> {
        val dosagePattern = Regex(
            """(\d+(?:\.\d+)?)\s*(mg|mcg|g|ml|units?|iu|ml/hr)(?:\s*/\s*(\d+(?:\.\d+)?)\s*(mg|mcg|g|ml|units?|iu))?""",
            RegexOption.IGNORE_CASE
        )
        return dosagePattern.findAll(text).map { it.value }.distinct().toList()
    }

    /**
     * Extracts frequency instructions: "twice daily", "every 8 hours", etc.
     */
    private fun extractFrequencies(text: String): List<String> {
        val frequencyPatterns = listOf(
            Regex("""(once\s+(?:a\s+)?day|daily|every\s+\d+\s+hours?|every\s+\d+\s+hrs?)""", RegexOption.IGNORE_CASE),
            Regex("""(twice\s+(?:a\s+)?day|bid|two\s+times?\s+(?:a\s+)?day)""", RegexOption.IGNORE_CASE),
            Regex("""(three\s+times?\s+(?:a\s+)?day|tid|three\s+daily)""", RegexOption.IGNORE_CASE),
            Regex("""(four\s+times?\s+(?:a\s+)?day|qid|four\s+daily)""", RegexOption.IGNORE_CASE),
            Regex("""(every\s+\d+\s+(?:to|–|-)\s*\d+\s+hours?)""", RegexOption.IGNORE_CASE),
            Regex("""((?:at|before|after)\s+(?:breakfast|lunch|dinner|meals?|bedtime|sleep))""", RegexOption.IGNORE_CASE),
            Regex("""(as\s+needed|prn|when\s+needed)""", RegexOption.IGNORE_CASE),
            Regex("""(once\s+weekly|weekly|every\s+\d+\s+days?)""", RegexOption.IGNORE_CASE)
        )

        return frequencyPatterns.flatMap { pattern ->
            pattern.findAll(text).map { it.value }
        }.distinct().toList()
    }

    /**
     * Extracts warnings and contraindications.
     */
    private fun extractWarnings(text: String): List<String> {
        val warnings = mutableListOf<String>()
        val warningKeywords = listOf(
            "warning", "caution", "danger", "do not", "don't",
            "avoid", "allergy", "allergic", "contraindication",
            "side effect", "adverse", "overdose", "poison",
            "keep out of reach", "children", "pregnant", "nursing",
            "kidney", "liver", "heart", "blood pressure",
            "diabetes", "interaction", "alcohol", "drive",
            "drowsy", "dizzy", "nausea"
        )

        val lines = text.lines()
        for (line in lines) {
            val lowerLine = line.lowercase()
            if (warningKeywords.any { lowerLine.contains(it) }) {
                warnings.add(line.trim())
            }
        }

        // Also check for "⚠" or "!" symbols
        for (line in lines) {
            if (line.contains("⚠") || line.contains("❗") || line.contains("WARNING", ignoreCase = true)) {
                if (line.trim() !in warnings) {
                    warnings.add(line.trim())
                }
            }
        }

        return warnings
    }

    /**
     * Extracts expiry date: "EXP: 12/2025", "Expiry: 2025-12", etc.
     */
    private fun extractExpiryDate(text: String): String? {
        val expiryPattern = Regex(
            """(?:exp(?:iry|ires?)?|valid(?:ity)?|use\s+by)[:\s]*(\d{1,2}[/\-\.]\d{2,4})""",
            RegexOption.IGNORE_CASE
        )
        return expiryPattern.find(text)?.groupValues?.get(1)
    }

    /**
     * Extracts active ingredients.
     */
    private fun extractActiveIngredients(text: String): List<String> {
        val ingredientPattern = Regex(
            """(?:active\s+ingredients?|contains?)[:\s]*(.+?)(?:\.|$)""",
            RegexOption.IGNORE_CASE
        )
        return ingredientPattern.findAll(text).map { it.groupValues[1].trim() }.toList()
    }

    /**
     * Extracts usage instructions.
     */
    private fun extractInstructions(text: String): List<String> {
        val instructionPattern = Regex(
            """(?:directions?|instructions?|dosage|how\s+to\s+(?:take|use|apply))[:\s]*(.+?)(?:\.|$)""",
            RegexOption.IGNORE_CASE
        )
        return instructionPattern.findAll(text).map { it.groupValues[1].trim() }.toList()
    }

    // ── TTS-Friendly Output ───────────────────────────────────────────

    /**
     * Formats [MedicineInfo] into a clear, spoken summary for TTS.
     * Emphasizes warnings and dosage information.
     */
    fun formatForTts(info: MedicineInfo): String {
        return buildString {
            // Drug name
            append("Medicine: ${info.drugName}. ")

            // Dosages
            if (info.dosages.isNotEmpty()) {
                append("Dosage: ${info.dosages.joinToString(", ")}. ")
            }

            // Frequency
            if (info.frequencies.isNotEmpty()) {
                append("How to take: ${info.frequencies.joinToString(", ")}. ")
            }

            // Instructions
            if (info.instructions.isNotEmpty()) {
                append("Directions: ${info.instructions.joinToString(". ")}. ")
            }

            // Warnings (emphasized)
            if (info.warnings.isNotEmpty()) {
                append("Warning! ")
                append(info.warnings.joinToString(". Warning! ") + ". ")
            }

            // Expiry
            if (info.expiryDate != null) {
                append("Expires: ${info.expiryDate}. ")
            }

            // Active ingredients
            if (info.activeIngredients.isNotEmpty()) {
                append("Active ingredients: ${info.activeIngredients.joinToString(", ")}. ")
            }
        }.trim()
    }

    // ── Data Class ────────────────────────────────────────────────────

    data class MedicineInfo(
        val rawText: String,
        val drugName: String,
        val dosages: List<String>,
        val frequencies: List<String>,
        val warnings: List<String>,
        val expiryDate: String?,
        val activeIngredients: List<String>,
        val instructions: List<String>
    ) {
        /** Whether this label contains any warnings. */
        fun hasWarnings(): Boolean = warnings.isNotEmpty()

        /** Whether the medicine appears to be expired. */
        fun isExpired(): Boolean {
            if (expiryDate == null) return false
            // Simple check: if expiry date contains a past year
            val yearPattern = Regex("""\d{4}""")
            val year = yearPattern.find(expiryDate)?.value?.toIntOrNull() ?: return false
            return year < java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        }
    }

    companion object {
        private const val TAG = "MedicineReaderHelper"
    }
}
