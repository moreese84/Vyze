package com.vyze.app

import android.util.Log
import com.vyze.app.data.MemoryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Constructs dynamic system prompts for the on-device VLM (Gemma 3n E2B).
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
        ocrText: String? = null
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
                continuousMode -> sb.appendLine(CONTINUOUS_MODE_RULES)
                isDirectQuery -> sb.appendLine(BASE_RULES_DIRECT_QUERY)
                else -> sb.appendLine(BASE_RULES_NAVIGATION)
            }

            // 2. OCR pre-extracted text (if available — feeds clean text to model)
            if (!ocrText.isNullOrBlank()) {
                sb.appendLine("OCR: $ocrText")
            }

            // 3. Task specification
            if (isDirectQuery) {
                sb.appendLine("Answer: \"$queryOverride\"")
            } else {
                sb.appendLine(DEFAULT_NAVIGATION_QUERY)
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
         * NAVIGATION MODE — used for generic taps and automatic spatial descriptions.
         */
        private const val BASE_RULES_NAVIGATION =
            "Describe what you see. No filler phrases. " +
            "Use left/center/right + distance. " +
            "Read text in ORIGINAL language. " +
            "If unsure about an object, say 'not clearly visible'. Do NOT guess or hallucinate. " +
            "Mirrors/glass: describe the surface itself."

        private const val BASE_RULES_DIRECT_QUERY =
            "Answer directly in first sentence. " +
            "Read text verbatim in ORIGINAL language. " +
            "If text is blurry or unreadable, say 'Text is unclear' — NEVER guess. " +
            "If no text visible, say 'No text visible'. " +
            "Only what you see in THIS image."

        private const val DEFAULT_NAVIGATION_QUERY =
            "Describe environment: obstacles, doors, people, text."

        private const val OUTPUT_CONSTRAINTS =
            "1-2 sentences. No bullet/markdown."

        /** Fallback prompt — used if dynamic prompt construction fails. */
        private const val FALLBACK_PROMPT = """You are Vyze, an accessible vision engine. Describe spatial layouts and obstacles directly. Do NOT use filler words like 'I see' or 'This photo shows'. Keep answers under 2 sentences.

Describe the immediate environment for navigation. Focus on obstacles, doors, people, and visible text.

Output 1-2 spoken sentences with spatial positioning. No filler, no formatting."""

        private const val CONTINUOUS_MODE_RULES =
            "Instant assistant. Key objects + position. 15 words max."

        /**
         * Language mirror directive — forces Gemma to respond in the same
         * language as the user's spoken query. {lang} is replaced dynamically
         * with the detected language name (e.g., "Malay", "Chinese").
         */
        private const val LANGUAGE_MIRROR_DIRECTIVE =
            "[OUTPUT LANGUAGE: {lang}] Write every word in {lang}. No English."
    }
}
