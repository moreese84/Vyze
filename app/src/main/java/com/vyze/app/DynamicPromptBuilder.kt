package com.vyze.app

import android.util.Log
import com.vyze.app.data.MemoryDao
import com.vyze.app.memory.SimilarInteraction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        continuousMode: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        try {
            val sb = StringBuilder()
            val isDirectQuery = !queryOverride.isNullOrBlank()

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

            // 4. Room memory — recent scenes + known local items
            val envMemories = memoryDao.getRecentEnvironment(limit = 5)
            if (envMemories.isNotEmpty()) {
                sb.appendLine(formatRoomMemorySection(envMemories))
            }

            // 5. Snapshot / touch context (navigation mode only)
            if (snapshotDescription.isNotBlank() && !isDirectQuery) {
                sb.appendLine("--- SNAPSHOT ---")
                sb.appendLine(snapshotDescription)
                sb.appendLine()
            }

            // 6. Task specification
            sb.appendLine("--- TASK ---")
            if (isDirectQuery) {
                sb.appendLine("Answer this user question directly based on the image: \"$queryOverride\"")
            } else {
                sb.appendLine(DEFAULT_NAVIGATION_QUERY)
            }
            sb.appendLine()

            // 7. Output constraints
            sb.appendLine(OUTPUT_CONSTRAINTS)

            val prompt = sb.toString()
            Log.d(TAG, "Built prompt: ${prompt.length} chars, " +
                "mode=${if (isDirectQuery) "DIRECT_QUERY" else "NAVIGATION"}, " +
                "${preferences.size} prefs, ${similarInteractions.size} similar, ${envMemories.size} env memories")
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
        val sb = StringBuilder()
        sb.appendLine("--- SIMILAR PAST INTERACTIONS ---")
        sb.appendLine("These are visually similar scenes from this user's history:")
        sb.appendLine()

        for ((index, similar) in similarInteractions.withIndex()) {
            val record = similar.record
            val score = String.format("%.2f", similar.similarityScore)
            val timeAgo = formatTimestamp(record.timestamp)

            sb.appendLine("[${index + 1}] (similarity: $score, $timeAgo)")
            sb.appendLine("  Previous query: ${record.prompt.take(100)}")
            sb.appendLine("  Previous output: ${record.output.take(200)}")
            if (record.feedback.isNotBlank()) {
                sb.appendLine("  User feedback: ${record.feedback.take(100)}")
            }
            sb.appendLine()
        }

        sb.appendLine("Use this history to:")
        sb.appendLine("- Provide spatial continuity (what changed or remained the same)")
        sb.appendLine("- Reference known objects from past observations")
        sb.appendLine("- Avoid repeating information already given")
        sb.appendLine("- Learn from any user feedback to improve this response")
        sb.appendLine()
        return sb.toString()
    }

    private fun formatPreferencesSection(
        preferences: List<com.vyze.app.data.VyzeMemoryEntity>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("--- USER PREFERENCES ---")

        val brevity = preferences.find { it.key == "brevity" }?.value
        val textPriority = preferences.find { it.key == "text_priority" }?.value
        val knownItems = preferences.find { it.key == "known_items" }?.value
        val preferredZones = preferences.find { it.key == "preferred_zones" }?.value

        when (brevity) {
            "short" -> sb.appendLine("Output length: 1 sentence max. Be extremely terse.")
            "detailed" -> sb.appendLine("Output length: up to 3 sentences. Include spatial detail, colors, and navigation hazards.")
            "normal" -> sb.appendLine("Output length: 1-2 sentences.")
            else -> { /* use default from base rules */ }
        }

        when (textPriority) {
            "high" -> sb.appendLine("Text priority: HIGH. Always read any visible text, signs, labels, or numbers aloud, even if brief.")
            "low" -> sb.appendLine("Text priority: LOW. Only mention text if it is the primary subject of the scene.")
            else -> { /* normal text priority */ }
        }

        if (!knownItems.isNullOrBlank()) {
            sb.appendLine("Known items in this user's environment: $knownItems")
            sb.appendLine("If any of these are visible, name them explicitly and give their position.")
        }

        if (!preferredZones.isNullOrBlank()) {
            sb.appendLine("Emphasize objects in these zones: $preferredZones")
        }

        val typedKeys = setOf("brevity", "text_priority", "known_items", "preferred_zones")
        for (pref in preferences) {
            if (pref.key !in typedKeys) {
                sb.appendLine("${pref.key}: ${pref.value}")
            }
        }

        sb.appendLine()
        return sb.toString()
    }

    private fun formatRoomMemorySection(
        envMemories: List<com.vyze.app.data.VyzeMemoryEntity>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("--- ROOM MEMORY ---")
        sb.appendLine("Recent observations from this environment:")

        for (env in envMemories) {
            sb.appendLine("[${formatTimestamp(env.timestamp)}] ${env.value}")
        }

        sb.appendLine("Use this history for spatial continuity. If the current view is similar to a recent observation, mention what changed or remained the same.")
        sb.appendLine()
        return sb.toString()
    }

    // ── Helpers ────────────────────────────────────────────────────

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
        private const val BASE_RULES_NAVIGATION = """You are Vyze, an accessible vision engine. Describe spatial layouts and obstacles directly. Do NOT use filler words like 'I see' or 'This photo shows'. Keep answers under 2 sentences.

Output rules:
- Start with the most important spatial information (obstacle, door, person).
- Use clock-face or left/center/right positioning.
- Estimate distance when possible ("about 2 steps ahead").
- Name visible text or signs directly without preamble.
- If no objects are detected, say "Clear path ahead" or "No obstacles detected."
- Never say "I notice", "It appears", "Looking at the image", or similar hedging.

Critical accuracy rules:
- ONLY describe objects directly in the physical space in front of the camera.
- If the image shows a MIRROR or reflective surface, describe the mirror/glass itself.
- Do NOT hallucinate or guess objects that are not clearly visible.
- Do not describe objects outside the camera frame."""

        /**
         * DIRECT QUERY MODE — used when the user asks a specific question.
         * Includes full anti-hallucination guardrails and low-confidence fallbacks.
         */
        private const val BASE_RULES_DIRECT_QUERY = """You are Vyze, a concise audio assistant for visually impaired users.

Critical direct-answer rules:
- Answer the user's specific question directly in the very first sentence.
- Prioritize reading and extracting both PRINTED and HANDWRITTEN text on labels, documents, packages, or medication bags.
- Read handwritten fields verbatim (e.g., drug names, dosage instructions, indications).
- Do NOT describe visual packaging, background colors, card textures, or layout unless explicitly asked.
- Do NOT use filler words like "I see", "The image shows", or "This package contains".
- Keep responses concise, factual, and optimized for immediate text-to-speech reading.

Anti-hallucination rules (MANDATORY):
- ONLY report text and objects you can clearly see in the image.
- Do NOT guess, fabricate, or invent any text, words, numbers, or details.
- If text is blurry, partially obscured, low-resolution, or unreadable, say exactly: "Text is unreadable due to image quality."
- If handwriting is ambiguous or illegible, say exactly: "Handwriting is unclear."
- If you are not confident about a word, do NOT include it — omit it rather than guess.
- If the image shows a MIRROR or reflective surface, describe the mirror itself — do NOT describe reflected text as if it is in the room.
- If the image shows a SCREEN or TV, describe the device — do NOT describe displayed content as physical text.
- Do NOT hallucinate objects, labels, or text that are not clearly visible in the frame."""

        /** Default navigation query — used when no user query is provided. */
        private const val DEFAULT_NAVIGATION_QUERY = """Describe the immediate environment for navigation. Focus on:
- Obstacles, furniture, or hazards in the path
- Doors, stairs, or transitions between rooms
- People and their approximate position
- Any visible text or signage"""

        /** Output constraints — appended after the task to reinforce formatting. */
        private const val OUTPUT_CONSTRAINTS = """Output format:
- 1-2 spoken sentences only.
- Spatial language: "on your left", "directly ahead", "about 3 feet away".
- No bullet points, no markdown, no lists, no formatting.
- No conversational filler. Start with the data, not a preamble."""

        /** Fallback prompt — used if dynamic prompt construction fails. */
        private const val FALLBACK_PROMPT = """You are Vyze, an accessible vision engine. Describe spatial layouts and obstacles directly. Do NOT use filler words like 'I see' or 'This photo shows'. Keep answers under 2 sentences.

Describe the immediate environment for navigation. Focus on obstacles, doors, people, and visible text.

Output 1-2 spoken sentences with spatial positioning. No filler, no formatting."""

        /**
         * CONTINUOUS MODE — ultra-concise for the auto-snapshot loop.
         * Optimized for streaming: 25 words max, no filler, instant data.
         */
        private const val CONTINUOUS_MODE_RULES =
            "Instant visual assistant. Describe key objects and spatial arrangement directly in 15 words or less. No filler."
    }
}
