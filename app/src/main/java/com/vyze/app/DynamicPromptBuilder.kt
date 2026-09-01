package com.vyze.app

import android.util.Log
import com.vyze.app.data.MemoryDao
import com.vyze.app.memory.SimilarInteraction
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
        similarInteractions: List<SimilarInteraction> = emptyList(),
        continuousMode: Boolean = false,
        userLocale: Locale = Locale.US,
        ocrText: String? = null
    ): String {
        return try {
            val sb = StringBuilder()
            val isDirectQuery = !queryOverride.isNullOrBlank()

            // 0. LANGUAGE MIRROR — TOP OF PROMPT (before any English rules)
            //    Placing this FIRST ensures the model's primary language context
            //    is set to the user's language before reading any instructions.
            val langName = languageNameForLocale(userLocale)
            if (userLocale != Locale.US && userLocale.language != "en") {
                sb.appendLine("[CRITICAL: You MUST respond entirely in $langName. All output text, descriptions, and answers must be written in $langName. Do NOT use English in your response.]")
                sb.appendLine()
            }

            // 1. Inject appropriate rules based on query intent
            when {
                continuousMode -> sb.appendLine(CONTINUOUS_MODE_RULES)
                isDirectQuery -> sb.appendLine(BASE_RULES_DIRECT_QUERY)
                else -> sb.appendLine(BASE_RULES_NAVIGATION)
            }
            sb.appendLine()

            // 2. User preferences from Room DB
            val preferences = memoryDao.getAllPreferences()
            if (preferences.isNotEmpty()) {
                sb.appendLine(formatPreferencesSection(preferences))
            }

            // 3. Similar past interactions — adaptive intelligence context
            if (similarInteractions.isNotEmpty()) {
                sb.appendLine(formatSimilarInteractionsSection(similarInteractions))
            }

            // 4. Snapshot / touch context (navigation mode only)
            if (snapshotDescription.isNotBlank() && !isDirectQuery) {
                sb.appendLine("--- SNAPSHOT ---")
                sb.appendLine(snapshotDescription)
                sb.appendLine()
            }

            // 5. OCR pre-extracted text (if available — feeds clean text to model)
            if (!ocrText.isNullOrBlank()) {
                sb.appendLine("OCR: $ocrText")
            }

            // 6. Task specification
            sb.appendLine("--- TASK ---")
            if (isDirectQuery) {
                sb.appendLine("Answer this user question directly based on the image: \"$queryOverride\"")
            } else {
                sb.appendLine(DEFAULT_NAVIGATION_QUERY)
            }
            sb.appendLine()

            // 7. Language mirror directive — reinforce at bottom too
            if (userLocale != Locale.US && userLocale.language != "en") {
                sb.appendLine(LANGUAGE_MIRROR_DIRECTIVE.replace("{lang}", langName))
            }

            // 8. Output constraints
            sb.appendLine(OUTPUT_CONSTRAINTS)

            val prompt = sb.toString()
            Log.d(TAG, "Built prompt: ${prompt.length} chars, " +
                "mode=${if (isDirectQuery) "DIRECT_QUERY" else "NAVIGATION"}, " +
                "${preferences.size} prefs, ${similarInteractions.size} similar")
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

    private fun formatSimilarInteractionsSection(
        similarInteractions: List<SimilarInteraction>
    ): String {
        // Cap to top 2 most relevant interactions, 80 chars each
        val capped = similarInteractions.take(2)
        if (capped.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("Similar: ${capped.joinToString("; ") { s ->
            "${s.record.output.take(80)} (${formatTimestamp(s.record.timestamp)})"
        }}")
        return sb.toString()
    }

    private fun formatPreferencesSection(
        preferences: List<com.vyze.app.data.VyzeMemoryEntity>
    ): String {
        if (preferences.isEmpty()) return ""
        val sb = StringBuilder()
        val brevity = preferences.find { it.key == "brevity" }?.value
        val textPriority = preferences.find { it.key == "text_priority" }?.value
        val knownItems = preferences.find { it.key == "known_items" }?.value

        if (brevity == "short") sb.append("Brevity: 1 sentence. ")
        else if (brevity == "detailed") sb.append("Brevity: 2-3 sentences. ")
        if (textPriority == "high") sb.append("Read all text aloud. ")
        else if (textPriority == "low") sb.append("Skip minor text. ")
        if (!knownItems.isNullOrBlank()) sb.append("Known: $knownItems. ")
        if (sb.isNotEmpty()) sb.appendLine()
        return sb.toString()
    }

    private fun formatRoomMemorySection(
        envMemories: List<com.vyze.app.data.VyzeMemoryEntity>
    ): String {
        val capped = envMemories.take(2)
        if (capped.isEmpty()) return ""
        return "Recent: ${capped.joinToString("; ") { env ->
            env.value.take(60)
        }}"
    }

    // ── Helpers ────────────────────────────────────────────────────

    /**
     * Map a Locale to a human-readable language name for the prompt directive.
     * Uses Locale.getDisplayLanguage() for dynamic resolution — no hardcoded list.
     */
    private fun languageNameForLocale(locale: Locale): String {
        return locale.getDisplayLanguage(Locale.US).ifBlank { locale.language }
    }

    private fun formatTimestamp(timestampMs: Long): String {
        val diff = System.currentTimeMillis() - timestampMs
        return when {
            diff < 60_000 -> "${diff / 1000}s ago"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            else -> "${diff / 86_400_000}d ago"
        }
    }

    // ── Constants ──────────────────────────────────────────────────

    companion object {
        private const val TAG = "DynamicPromptBuilder"

        /**
         * NAVIGATION MODE — used for generic taps and automatic spatial descriptions.
         */
        private const val BASE_RULES_NAVIGATION =
            "Describe ONLY what you see in THIS image. No filler (I see, this shows). " +
            "Use left/center/right + distance. " +
            "Read visible text in its ORIGINAL language — do NOT translate. " +
            "Do NOT guess, infer, or hallucinate objects not clearly visible. " +
            "Do NOT reference prior scenes or memory. Objects outside frame excluded. " +
            "Mirror/glass described as such."

        private const val BASE_RULES_DIRECT_QUERY =
            "Answer the question directly in first sentence. " +
            "Read printed + handwritten text verbatim in its ORIGINAL language — do NOT translate. " +
            "If text is blurry, low-res, or unreadable, say 'Text is unreadable' — NEVER guess or invent details. " +
            "If no text is visible, say 'No text visible'. " +
            "Only describe what you actually see in THIS image. Do not reference prior scenes. " +
            "Mirror/screen/device only. Factual. No hallucination."

        private const val DEFAULT_NAVIGATION_QUERY =
            "Describe environment: obstacles, doors, people, text."

        private const val OUTPUT_CONSTRAINTS =
            "1-2 sentences. Spatial. No bullet/markdown. Start with data."

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
            "[OUTPUT LANGUAGE: {lang}] All your output must be written in {lang}. " +
            "Do NOT translate to English. Do NOT mix languages. " +
            "Write every word of your response in {lang}."
    }
}
