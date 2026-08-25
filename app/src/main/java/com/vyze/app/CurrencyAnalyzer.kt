package com.vyze.app

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Analyzes OCR text output for currency denominations and produces
 * human-readable announcements for TTS readout.
 *
 * Supports:
 *  - **Malaysian Ringgit banknotes:** RM 1, RM 5, RM 10, RM 20, RM 50, RM 100
 *  - **US Dollar banknotes:** $1, $5, $10, $20, $50, $100
 *  - **Ringgit coins:** 5 sen, 10 sen, 20 sen, 50 sen
 *  - **Sen shorthand coins:** 5c, 10c, 20c, 50c
 *
 * ## Speech Debouncing
 * A [ConcurrentHashMap] tracks the last-spoken timestamp per denomination.
 * A 3,000 ms cooldown prevents the app from spamming the same denomination
 * when the camera holds steady on a banknote or coin.
 *
 * ## Thread Safety
 * The cooldown map uses [ConcurrentHashMap] and [SystemClock.uptimeMillis]
 * which is monotonic and safe across threads. The analyzer is designed to
 * be called from the composite analyzer's background executor.
 */
class CurrencyAnalyzer {

    /**
     * Timestamp (via [SystemClock.uptimeMillis]) of when each denomination
     * was last announced. Keyed by the denomination string (e.g. "RM 50",
     * "50 sen", "$10").
     */
    private val lastSpokenTimestamp = ConcurrentHashMap<String, Long>()

    /**
     * Analyzes OCR text for currency denominations and returns announcement
     * strings that are eligible to be spoken (not within the cooldown window).
     *
     * @param ocrText The raw text output from [TextRecognitionHelper].
     * @return A list of announcement strings, e.g. ["10 Ringgit note detected"].
     *         Empty if no new denominations are eligible.
     */
    fun analyzeForCurrency(ocrText: String): List<String> {
        if (ocrText.isBlank()) return emptyList()

        val results = mutableListOf<String>()

        // Match all currency patterns in the text
        for (pattern in CURRENCY_PATTERNS) {
            val matcher = pattern.regex.find(ocrText) ?: continue
            val denomination = pattern.denominationKey
            val announcement = pattern.announcement

            // Check cooldown
            if (isWithinCooldown(denomination)) continue

            // Record this denomination as spoken
            recordSpoken(denomination)

            results.add(announcement)
        }

        return results
    }

    /**
     * Convenience method that analyzes OCR text and returns the first
     * eligible denomination (highest priority). Returns `null` if
     * nothing is eligible or no currency is detected.
     *
     * @param ocrText The raw text output from OCR.
     * @return A single announcement string, or null.
     */
    fun analyzeSingle(ocrText: String): String? {
        return analyzeForCurrency(ocrText).firstOrNull()
    }

    /**
     * Checks whether a denomination was spoken within the cooldown window.
     */
    private fun isWithinCooldown(denomination: String): Boolean {
        val lastTime = lastSpokenTimestamp[denomination] ?: return false
        return (SystemClock.uptimeMillis() - lastTime) < COOLDOWN_MS
    }

    /**
     * Records that a denomination was just spoken.
     */
    private fun recordSpoken(denomination: String) {
        lastSpokenTimestamp[denomination] = SystemClock.uptimeMillis()
    }

    /**
     * Returns `true` if the given text contains any recognizable currency pattern.
     * Useful for deciding whether to trigger currency-specific TTS mode.
     */
    fun containsCurrency(ocrText: String): Boolean {
        if (ocrText.isBlank()) return false
        return CURRENCY_PATTERNS.any { it.regex.containsMatchIn(ocrText) }
    }

    /**
     * Clears all cooldown timestamps. Call when the detector is reset or
     * the user changes modes.
     */
    fun clearCooldowns() {
        lastSpokenTimestamp.clear()
    }

    // ── Currency Pattern Definitions ────────────────────────────────────────

    /**
     * A single currency pattern with its regex, unique key for debouncing,
     * and the human-readable TTS announcement.
     */
    private data class CurrencyPattern(
        val regex: Regex,
        val denominationKey: String,
        val announcement: String
    )

    companion object {
        private const val TAG = "CurrencyAnalyzer"

        /** Cooldown per denomination in milliseconds. */
        const val COOLDOWN_MS = 3000L

        // ── Ringgit Banknotes ───────────────────────────────────────────────
        // Patterns match variations like "RM100", "RM 100", "RM100.00", "rm100"

        private val RM_100 = CurrencyPattern(
            regex = Regex("""(?i)\brm\s?100\b"""),
            denominationKey = "RM 100",
            announcement = "100 Ringgit note detected"
        )
        private val RM_50 = CurrencyPattern(
            regex = Regex("""(?i)\brm\s?50\b"""),
            denominationKey = "RM 50",
            announcement = "50 Ringgit note detected"
        )
        private val RM_20 = CurrencyPattern(
            regex = Regex("""(?i)\brm\s?20\b"""),
            denominationKey = "RM 20",
            announcement = "20 Ringgit note detected"
        )
        private val RM_10 = CurrencyPattern(
            regex = Regex("""(?i)\brm\s?10\b"""),
            denominationKey = "RM 10",
            announcement = "10 Ringgit note detected"
        )
        private val RM_5 = CurrencyPattern(
            regex = Regex("""(?i)\brm\s?5\b"""),
            denominationKey = "RM 5",
            announcement = "5 Ringgit note detected"
        )
        private val RM_1 = CurrencyPattern(
            regex = Regex("""(?i)\brm\s?1\b"""),
            denominationKey = "RM 1",
            announcement = "1 Ringgit note detected"
        )

        // ── US Dollar Banknotes ─────────────────────────────────────────────
        // Match "$100", "$100.00", "$ 100", but NOT "$1000" (negative lookahead)

        private val USD_100 = CurrencyPattern(
            regex = Regex("""\$\s?100\b(?!\d)"""),
            denominationKey = "$100",
            announcement = "100 Dollar note detected"
        )
        private val USD_50 = CurrencyPattern(
            regex = Regex("""\$\s?50\b(?!\d)"""),
            denominationKey = "$50",
            announcement = "50 Dollar note detected"
        )
        private val USD_20 = CurrencyPattern(
            regex = Regex("""\$\s?20\b(?!\d)"""),
            denominationKey = "$20",
            announcement = "20 Dollar note detected"
        )
        private val USD_10 = CurrencyPattern(
            regex = Regex("""\$\s?10\b(?!\d)"""),
            denominationKey = "$10",
            announcement = "10 Dollar note detected"
        )
        private val USD_5 = CurrencyPattern(
            regex = Regex("""\$\s?5\b(?!\d)"""),
            denominationKey = "$5",
            announcement = "5 Dollar note detected"
        )
        private val USD_1 = CurrencyPattern(
            regex = Regex("""\$\s?1\b(?!\d)"""),
            denominationKey = "$1",
            announcement = "1 Dollar note detected"
        )

        // ── Ringgit Coins (sen) ─────────────────────────────────────────────
        // Match "50 sen", "50sen", "50 Sen", "50SEN"

        private val SEN_50 = CurrencyPattern(
            regex = Regex("""(?i)\b50\s?sen\b"""),
            denominationKey = "50 sen",
            announcement = "50 sen coin detected"
        )
        private val SEN_20 = CurrencyPattern(
            regex = Regex("""(?i)\b20\s?sen\b"""),
            denominationKey = "20 sen",
            announcement = "20 sen coin detected"
        )
        private val SEN_10 = CurrencyPattern(
            regex = Regex("""(?i)\b10\s?sen\b"""),
            denominationKey = "10 sen",
            announcement = "10 sen coin detected"
        )
        private val SEN_5 = CurrencyPattern(
            regex = Regex("""(?i)\b5\s?sen\b"""),
            denominationKey = "5 sen",
            announcement = "5 sen coin detected"
        )

        // ── Shorthand Coins (c suffix) ──────────────────────────────────────
        // Match "50c", "50 c", "50C" — but NOT "50cm" or "50cc"
        // Use negative lookahead for word chars after 'c'

        private val C_50 = CurrencyPattern(
            regex = Regex("""\b50\s?c\b(?![a-zA-Z])"""),
            denominationKey = "50c",
            announcement = "50 sen coin detected"
        )
        private val C_20 = CurrencyPattern(
            regex = Regex("""\b20\s?c\b(?![a-zA-Z])"""),
            denominationKey = "20c",
            announcement = "20 sen coin detected"
        )
        private val C_10 = CurrencyPattern(
            regex = Regex("""\b10\s?c\b(?![a-zA-Z])"""),
            denominationKey = "10c",
            announcement = "10 sen coin detected"
        )
        private val C_5 = CurrencyPattern(
            regex = Regex("""\b5\s?c\b(?![a-zA-Z])"""),
            denominationKey = "5c",
            announcement = "5 sen coin detected"
        )

        // ── Master Pattern List ─────────────────────────────────────────────
        // Ordered from highest to lowest value so the most valuable
        // denomination is always matched first when multiple overlap
        // (e.g. "RM 100" before "RM 100 sen" or "$100" before "$10").
        // Note: RM 100 must come before RM 10, $100 before $10, etc.

        private val CURRENCY_PATTERNS = listOf(
            // Ringgit banknotes (highest first)
            RM_100, RM_50, RM_20, RM_10, RM_5, RM_1,
            // US Dollar banknotes (highest first)
            USD_100, USD_50, USD_20, USD_10, USD_5, USD_1,
            // Ringgit coins (highest first)
            SEN_50, SEN_20, SEN_10, SEN_5,
            // Shorthand coins (highest first)
            C_50, C_20, C_10, C_5
        )
    }
}
