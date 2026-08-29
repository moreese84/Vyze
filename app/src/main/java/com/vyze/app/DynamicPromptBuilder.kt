package com.vyze.app

import android.util.Log
import com.vyze.app.data.MemoryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Constructs dynamic system prompts for the on-device VLM (Gemma 4 E2B).
 *
 * Uses a strict base-rules system prompt that forbids conversational fluff,
 * then dynamically injects:
 * 1. **User preferences** — brevity level, text priority, known local items
 * 2. **Room memory** — recent scene descriptions for spatial continuity
 * 3. **Snapshot context** — what triggered this inference
 *
 * ## Prompt Structure
 * ```
 * [BASE RULES — anti-fluff, accessibility-first]
 * [USER PREFERENCES — brevity, text priority, local items]
 * [ROOM MEMORY — recent scenes, known objects]
 * [SNAPSHOT CONTEXT — trigger context]
 * [USER QUERY — the actual task]
 * ```
 *
 * ## Anti-Fluff Enforcement
 * The base rules explicitly forbid filler phrases like "I see", "This photo shows",
 * "Let me describe", etc. The model is instructed to output spatial data directly.
 */
class DynamicPromptBuilder(private val memoryDao: MemoryDao) {

    // ── Public API ─────────────────────────────────────────────────

    /**
     * Build a complete system prompt for VLM inference.
     *
     * @param snapshotDescription  Optional context about what triggered this inference
     *                             (e.g., "single tap at position (500, 300)")
     * @param queryOverride        Optional user query override
     *                             (e.g., "Where is the nearest door?")
     * @return The complete prompt string ready for VLM inference.
     */
    suspend fun buildPrompt(
        snapshotDescription: String = "",
        queryOverride: String? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val sb = StringBuilder()

            // 1. Base rules — anti-fluff, accessibility-first
            sb.appendLine(BASE_RULES)
            sb.appendLine()

            // 2. User preferences from Room DB
            val preferences = memoryDao.getAllPreferences()
            if (preferences.isNotEmpty()) {
                sb.appendLine(formatPreferencesSection(preferences))
            }

            // 3. Room memory — recent scenes + known local items
            val envMemories = memoryDao.getRecentEnvironment(limit = 5)
            if (envMemories.isNotEmpty()) {
                sb.appendLine(formatRoomMemorySection(envMemories))
            }

            // 4. Snapshot trigger context
            if (snapshotDescription.isNotBlank()) {
                sb.appendLine("--- SNAPSHOT ---")
                sb.appendLine(snapshotDescription)
                sb.appendLine()
            }

            // 5. User query or default navigation task
            sb.appendLine("--- TASK ---")
            sb.appendLine(queryOverride ?: DEFAULT_NAVIGATION_QUERY)
            sb.appendLine()
            sb.appendLine(OUTPUT_CONSTRAINTS)

            val prompt = sb.toString()
            Log.d(TAG, "Built prompt: ${prompt.length} chars, " +
                "${preferences.size} prefs, ${envMemories.size} env memories")
            prompt

        } catch (e: Exception) {
            Log.e(TAG, "Failed to build dynamic prompt, using fallback", e)
            FALLBACK_PROMPT
        }
    }

    // ── Memory Write Operations ────────────────────────────────────

    /**
     * Store a user preference in Room DB.
     *
     * Common keys:
     * - "brevity" → "short" | "normal" | "detailed"
     * - "text_priority" → "high" | "normal" | "low"
     * - "known_items" → "chair,table,lamp" (comma-separated local items)
     * - "preferred_zones" → "center,left" (zones to prioritize)
     */
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

    /**
     * Store a visual environment observation in Room DB.
     * Auto-prunes entries older than 24 hours.
     */
    suspend fun storeEnvironmentObservation(description: String) {
        withContext(Dispatchers.IO) {
            memoryDao.upsert(
                com.vyze.app.data.VyzeMemoryEntity(
                    category = "environment",
                    key = "scene_${System.currentTimeMillis()}",
                    value = description
                )
            )

            // Prune environment memories older than 24 hours
            val cutoff = System.currentTimeMillis() - (24L * 60 * 60 * 1000)
            memoryDao.pruneOlderThan(cutoff)
        }
    }

    /**
     * Store an interaction record (past query + response) for context continuity.
     */
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

    /**
     * Format user preferences into a structured section.
     *
     * Dynamically adapts the system prompt based on:
     * - brevity: adjusts sentence count instruction
     * - text_priority: raises/lowers OCR priority
     * - known_items: tells model which objects the user frequently encounters
     * - preferred_zones: tells model which spatial zones to emphasize
     */
    private fun formatPreferencesSection(
        preferences: List<com.vyze.app.data.VyzeMemoryEntity>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("--- USER PREFERENCES ---")

        // Extract typed preferences for dynamic prompt shaping
        val brevity = preferences.find { it.key == "brevity" }?.value
        val textPriority = preferences.find { it.key == "text_priority" }?.value
        val knownItems = preferences.find { it.key == "known_items" }?.value
        val preferredZones = preferences.find { it.key == "preferred_zones" }?.value

        // Brevity instruction — overrides the default sentence count
        when (brevity) {
            "short" -> sb.appendLine("Output length: 1 sentence max. Be extremely terse.")
            "detailed" -> sb.appendLine("Output length: up to 3 sentences. Include spatial detail, colors, and navigation hazards.")
            "normal" -> sb.appendLine("Output length: 1-2 sentences.")
            else -> { /* use default from base rules */ }
        }

        // Text priority — raises/lowers OCR emphasis
        when (textPriority) {
            "high" -> sb.appendLine("Text priority: HIGH. Always read any visible text, signs, labels, or numbers aloud, even if brief.")
            "low" -> sb.appendLine("Text priority: LOW. Only mention text if it is the primary subject of the scene.")
            else -> { /* normal text priority */ }
        }

        // Known local items — model should recognize and name these
        if (!knownItems.isNullOrBlank()) {
            sb.appendLine("Known items in this user's environment: $knownItems")
            sb.appendLine("If any of these are visible, name them explicitly and give their position.")
        }

        // Preferred spatial zones — model should emphasize these
        if (!preferredZones.isNullOrBlank()) {
            sb.appendLine("Emphasize objects in these zones: $preferredZones")
        }

        // Dump all other preferences as raw key-value pairs
        val typedKeys = setOf("brevity", "text_priority", "known_items", "preferred_zones")
        for (pref in preferences) {
            if (pref.key !in typedKeys) {
                sb.appendLine("${pref.key}: ${pref.value}")
            }
        }

        sb.appendLine()
        return sb.toString()
    }

    /**
     * Format Room memory (recent environment observations) into a section.
     * Provides spatial continuity — the model knows what was seen recently.
     */
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
         * BASE RULES — the core system prompt that enforces accessibility-first,
         * anti-fluff output. This is injected at the top of every prompt.
         *
         * Key constraints:
         * - NO filler phrases ("I see", "This photo shows", "Let me describe")
         * - Spatial language ONLY (left/center/right, close/far)
         * - Direct obstacle/hazard identification
         * - Under 2 sentences by default
         * - Natural spoken tone — no markdown, no bullets, no technical terms
         */
        private const val BASE_RULES = """You are Vyze, an accessible vision engine. Describe spatial layouts and obstacles directly. Do NOT use filler words like 'I see' or 'This photo shows'. Keep answers under 2 sentences.

Output rules:
- Start with the most important spatial information (obstacle, door, person).
- Use clock-face or left/center/right positioning.
- Estimate distance when possible ("about 2 steps ahead").
- Name visible text or signs directly without preamble.
- If no objects are detected, say "Clear path ahead" or "No obstacles detected."
- Never say "I notice", "It appears", "Looking at the image", or similar hedging.

Critical accuracy rules:
- ONLY describe objects directly in the physical space in front of the camera.
- If the image shows a MIRROR or reflective surface, describe the mirror/glass itself and the room around it — do NOT describe the reflected scene as if it is real.
- If the image shows a SCREEN or TV displaying an image, describe the screen device — not what is on the screen.
- Do NOT hallucinate or guess objects that are not clearly visible. If unsure, say what you are confident about.
- Do not describe objects outside the camera frame."""

        /**
         * DEFAULT NAVIGATION QUERY — used when no user query is provided.
         * Frames the task as spatial navigation assistance.
         */
        private const val DEFAULT_NAVIGATION_QUERY = """Describe the immediate environment for navigation. Focus on:
- Obstacles, furniture, or hazards in the path
- Doors, stairs, or transitions between rooms
- People and their approximate position
- Any visible text or signage"""

        /**
         * OUTPUT CONSTRAINTS — appended after the task to reinforce formatting.
         */
        private const val OUTPUT_CONSTRAINTS = """Output format:
- 1-2 spoken sentences only.
- Spatial language: "on your left", "directly ahead", "about 3 feet away".
- No bullet points, no markdown, no lists, no formatting.
- No conversational filler. Start with the data, not a preamble."""

        /**
         * FALLBACK PROMPT — used if dynamic prompt construction fails.
         */
        private const val FALLBACK_PROMPT = """You are Vyze, an accessible vision engine. Describe spatial layouts and obstacles directly. Do NOT use filler words like 'I see' or 'This photo shows'. Keep answers under 2 sentences.

Describe the immediate environment for navigation. Focus on obstacles, doors, people, and visible text.

Output 1-2 spoken sentences with spatial positioning. No filler, no formatting."""
    }
}
