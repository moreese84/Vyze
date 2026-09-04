package com.vyze.app

import android.util.Log
import com.vyze.app.data.MemoryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Constructs dynamic system prompts for the on-device VLM (Gemma 4 E2B).
 *
 * ## Intent-Based Prompt Branching
 * Automatically selects the appropriate system rules based on whether the user
 * asked a targeted question (e.g., "What medicine is this?") or triggered a
 * generic tap/snapshot (e.g., automatic spatial description).
 *
 * - **Navigation mode** (`BASE_RULES_NAVIGATION`): spatial layouts, obstacles, distances
 * - **Direct query mode** (`BASE_RULES_DIRECT_QUERY`): text extraction, OCR, direct answers
 */
class DynamicPromptBuilder(private val memoryDao: MemoryDao) {

    // ── Public API ─────────────────────────────────────────────────

    suspend fun buildPrompt(
        snapshotDescription: String = "",
        queryOverride: String? = null,
        continuousMode: Boolean = false,
        userLocale: Locale = Locale.US,
        ocrText: String? = null,
        currencyMode: Boolean = false,
        memoryContext: String? = null,
        textOnlyMode: Boolean = false
    ): String {
        return try {
            val sb = StringBuilder()
            val isDirectQuery = !queryOverride.isNullOrBlank()

            // 0. LANGUAGE MIRROR — TOP OF PROMPT (before any English rules)
            //    This is the strongest lever for non-English output.
            val langName = languageNameForLocale(userLocale)
            if (userLocale != Locale.US && userLocale.language != "en") {
                sb.appendLine("[OUTPUT LANGUAGE: $langName] Write EVERY word of your response in $langName. Do NOT use English. Do NOT translate. This is mandatory.")
                sb.appendLine()
            }

            // 1. Inject appropriate rules based on query intent
            when {
                // Text-only Q&A — general knowledge, NO camera frame exists.
                // The model must answer from its own knowledge, never invent
                // a scene, and stay concise for spoken delivery.
                textOnlyMode -> sb.appendLine(TEXT_ONLY_RULES)
                continuousMode -> sb.appendLine(CONTINUOUS_MODE_RULES)
                isDirectQuery -> sb.appendLine(BASE_RULES_DIRECT_QUERY)
                else -> sb.appendLine(BASE_RULES_NAVIGATION)
            }

            // 2. OCR pre-extracted text (if available — feeds clean text to model)
            if (!ocrText.isNullOrBlank()) {
                sb.appendLine("OCR: $ocrText")
                // 2b. Reading guidance — the model sometimes echoes OCR text
                //     letter-by-letter ("H-U-R-I-X") or stops early on long
                //     passages (box back panels). Anchor the desired behavior.
                sb.appendLine("The OCR text above is the ground truth. Read it as whole words and " +
                    "continuous sentences — never spell it letter by letter. When asked to read " +
                    "text, read ALL of it in reading order; do not summarize, skip, or stop early.")
            }

            // 2c. Prior scene memory (context injection — never a substitute)
            //     Only present when the current frame strongly matches a recent
            //     past scan. The model still analyzes the FRESH frame; the memory
            //     just lets it confirm continuity instead of describing from zero.
            if (!memoryContext.isNullOrBlank()) {
                sb.appendLine("Prior scene memory: $memoryContext")
                sb.appendLine("If this is the same scene you described before, confirm it briefly in " +
                    "your answer. If it is a different scene, or something has changed, describe " +
                    "ONLY what you now see — never repeat the prior description if it does not " +
                    "match the current image.")
                sb.appendLine()
            }

            // 3. Task specification
            if (isDirectQuery) {
                sb.appendLine("Answer: \"$queryOverride\"")
            } else {
                sb.appendLine(DEFAULT_NAVIGATION_QUERY)
            }

            // 3b. Currency reading rules (banknotes + coins)
            if (currencyMode) {
                sb.appendLine(CURRENCY_RULES)
            }

            // 4. Language mirror — reinforce at bottom
            if (userLocale != Locale.US && userLocale.language != "en") {
                sb.appendLine("REMEMBER: Respond only in $langName.")
            }

            val prompt = sb.toString()
            Log.d(TAG, "Built prompt: ${prompt.length} chars, " +
                "mode=${if (isDirectQuery) "DIRECT_QUERY" else "NAVIGATION"}")
            prompt

        } catch (e: Exception) {
            Log.e(TAG, "Failed to build dynamic prompt, using fallback", e)
            FALLBACK_PROMPT
        }
    }

    // ── Memory Write Operations ────────────────────────────────────

    suspend fun setPreference(key: String, value: String) {
        withContext(Dispatchers.IO) {
            memoryDao.upsert(
                com.vyze.app.data.VyzeMemoryEntity(
                    category = "preference",
                    key = key,
                    value = value
                )
            )
        }
    }

    suspend fun storeEnvironmentObservation(description: String) {
        withContext(Dispatchers.IO) {
            memoryDao.upsert(
                com.vyze.app.data.VyzeMemoryEntity(
                    category = "environment",
                    key = "scene_${System.currentTimeMillis()}",
                    value = description
                )
            )
            val cutoff = System.currentTimeMillis() - (24L * 60 * 60 * 1000)
            memoryDao.pruneOlderThan(cutoff)
        }
    }

    suspend fun storeInteraction(query: String, response: String) {
        withContext(Dispatchers.IO) {
            memoryDao.upsert(
                com.vyze.app.data.VyzeMemoryEntity(
                    category = "interaction",
                    key = "query_${System.currentTimeMillis()}",
                    value = response,
                    metadata = query
                )
            )
        }
    }

    // ── Section Formatters ─────────────────────────────────────────

    // ── Helpers ────────────────────────────────────────────────────

    /**
     * Map a Locale to a human-readable language name for the prompt directive.
     * Uses Locale.getDisplayLanguage() for dynamic resolution — no hardcoded list.
     */
    private fun languageNameForLocale(locale: Locale): String {
        return locale.getDisplayLanguage(Locale.US).ifBlank { locale.language }
    }

    // ── Constants ──────────────────────────────────────────────────

    companion object {
        private const val TAG = "DynamicPromptBuilder"

        /**
         * System directive — injected as <|turn|>system block.
         * Prevents Gemma from wasting cycles on internal English reasoning chains.
         */
        private const val SYSTEM_DIRECTIVE =
            "You are a fast, concise visual assistant. Describe scene layouts and spatial objects " +
            "directly in the language requested by the user without cross-translating or outputting " +
            "internal reasoning chains. Use clear, natural punctuation (commas, periods, and short clauses) " +
            "to guide spoken delivery. Respond only in the requested language."

        /**
         * NAVIGATION MODE — used for generic taps and automatic spatial descriptions.
         */
        private const val BASE_RULES_NAVIGATION =
            "Describe the scene in 1-2 DENSE sentences — more useful detail per word, never wordy prose. " +
            "For each key object say what it is plus the details you can ACTUALLY SEE: " +
            "color, size, material, and state (open/closed, full/empty, lying/standing). " +
            "Add left/center/right + distance when it places the object for the user. " +
            "For a packaged product (packet, box, bottle, can), first say its BRAND name " +
            "and product type exactly as printed, then its details. " +
            "Read printed text verbatim in ORIGINAL language as whole words — never spell letter by letter. " +
            "No filler phrases ('there is', 'I see', 'in the image', 'it appears'). " +
            "Use clear punctuation: periods to end sentences, commas to separate details. " +
            "If unsure about an object, say 'not clearly visible'. Do NOT guess or hallucinate. " +
            "Mirrors/glass: describe the surface itself.\n" +
            "Examples:\n" +
            "Input: door in front. Output: Brown wooden door, closed, center, about 2 steps ahead.\n" +
            "Input: person nearby. Output: Person on your left, about 1 step away.\n" +
            "Input: sofa scene. Output: Grey fabric sofa, soft cushions, about 3 steps ahead. Low wooden table in front of it.\n" +
            "Input: bottle on table. Output: Clear glass water bottle, half full, on the table about 1 step ahead.\n" +
            "Input: dark room. Output: Dark room. No obstacles detected within 3 steps.\n" +
            "Input: red packet on table. Output: Small red Maggi instant noodle packet, closed, on the table about 1 step ahead — label reads Maggi Kari."

        private const val BASE_RULES_DIRECT_QUERY =
            "Answer directly in the first sentence. " +
            "Name the object and give compact, useful details — size, color, material, " +
            "state (open/closed, full/empty) — only what you can ACTUALLY see, never long prose. " +
            "If it is a packaged product (packet, box, bottle, can), first say its BRAND name and product type " +
            "exactly as printed on it, then its details. " +
            "Read text verbatim in ORIGINAL language as whole words and sentences — never spell letter by letter. " +
            "When asked to read text, read the ENTIRE text in reading order; never stop halfway or summarize. " +
            "Keep the whole answer to 1-3 short spoken sentences. " +
            "Use clear punctuation: periods to end sentences, commas for pauses between details. " +
            "If text is blurry or unreadable, say 'Text is unclear' — NEVER guess. " +
            "If no text visible, say 'No text visible'. " +
            "Only what you see in THIS image.\n" +
            "Examples:\n" +
            "Input: what is this? Image shows a red packet. Output: Small red Maggi instant noodle packet, closed. Label reads Maggi Kari — noodles and seasoning sachets inside.\n" +
            "Input: what medicine is this? Image shows Diclac Retard box. Output: Diclac Retard, diclofenac sodium 100 milligram. Take one tablet daily after meals.\n" +
            "Input: read this label. Image shows price tag RM12.90. Output: Price is 12 Ringgit and 90 sen.\n" +
            "Input: what does this sign say? Image blurry. Output: Text is unclear."

        private const val DEFAULT_NAVIGATION_QUERY =
            "Describe environment: obstacles, doors, people, text."

        private const val OUTPUT_CONSTRAINTS =
            "1-2 sentences. No bullet/markdown."

        /** Fallback prompt — used if dynamic prompt construction fails. */
        private const val FALLBACK_PROMPT = """You are Vyze, an accessible vision engine. Describe spatial layouts and obstacles directly. Do NOT use filler words like 'I see' or 'This photo shows'. Keep answers under 2 sentences.

Describe the immediate environment for navigation. Focus on obstacles, doors, people, and visible text.

Output 1-2 spoken sentences with spatial positioning. No filler, no formatting."""

        /**
         * Money-reading rules — banknotes and coins.
         * Priority is exact value + no guessing: a wrong denomination is far
         * worse than "I cannot read it clearly" for a blind user.
         */
        private const val CURRENCY_RULES =
            "This is money — a banknote or a coin. Identify its VALUE from the " +
            "large numerals and printed text. State the value and the currency " +
            "(for example: 50 Ringgit, or 10 cents) and the dominant color. " +
            "If the value cannot be read clearly, say exactly: I cannot read this " +
            "clearly. NEVER guess or invent a value. Do not mention serial numbers."

        private const val CONTINUOUS_MODE_RULES =
            "Instant assistant. Key objects + position. 15 words max. Use commas between items, period at end."

        /**
         * Text-only Q&A rules — used when NO camera frame is available
         * (general-knowledge voice questions). The model answers from its
         * own training, must not pretend to see anything, and stays
         * concise for spoken delivery.
         */
        private const val TEXT_ONLY_RULES =
            "Answer the question from your own knowledge. No camera image is " +
            "available, so do NOT describe any scene, object, or text — answer " +
            "the question directly. Use clear punctuation: periods to end " +
            "sentences, commas for pauses. Keep the answer concise: 1-3 sentences. " +
            "If you do not know the answer, say 'I do not know that' — never guess."

        /**
         * Language mirror directive — forces Gemma to respond in the same
         * language as the user's spoken query. {lang} is replaced dynamically
         * with the detected language name (e.g., "Malay", "Chinese").
         */
        private const val LANGUAGE_MIRROR_DIRECTIVE =
            "[OUTPUT LANGUAGE: {lang}] Write every word in {lang}. No English."
    }
}
