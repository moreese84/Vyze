package com.vyze.app

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * On-device agentic AI engine powered by Gemini Nano via ML Kit GenAI Prompt API.
 *
 * ## Architecture
 * 1. Checks AICore / Gemini Nano availability at startup via [Generation.getClient()].
 * 2. Routes user queries through 4 specialized system-prompt modes.
 * 3. Falls back to a deterministic rule-based spatial summarizer when the
 *    device does not support AICore (older hardware, missing system model).
 *
 * ## Usage
 * ```kotlin
 * val agent = AgentEngine(context)
 * agent.initialize() // checks availability
 *
 * val response = agent.processQuery(
 *     userQuery = "What's around me?",
 *     mode = AgentEngine.Mode.SCENE_NAVIGATION,
 *     sceneContext = contextBuilder.buildContextFromResultBundle(bundle)
 * )
 * ttsManager.speakImmediate(response)
 * ```
 *
 * ## Event-Driven Execution
 * The agent MUST only run when triggered by a voice command or gesture.
 * YOLO/OCR run continuously on camera frames — the agent reads their
 * latest output snapshot, never intercepting the frame pipeline.
 */
class AgentEngine(private val context: Context) {

    private val TAG = "AgentEngine"

    // ── ML Kit GenAI Client ───────────────────────────────────────────

    private var generativeModel: GenerativeModel? = null
    private var isAvailable = false
    private var availabilityChecked = false

    // ── Helpers ───────────────────────────────────────────────────────

    private val contextBuilder = AgentContextBuilder()

    // ── Initialization ────────────────────────────────────────────────

    /**
     * Check if Gemini Nano is available on this device.
     * Must be called on a background thread.
     *
     * @return true if the agent can use on-device inference.
     */
    suspend fun initialize(): Boolean {
        if (availabilityChecked) return isAvailable

        return withContext(Dispatchers.IO) {
            try {
                generativeModel = Generation.getClient()

                // checkStatus() is a suspend function returning Int (FeatureStatus constants)
                val status = generativeModel!!.checkStatus()

                isAvailable = when (status) {
                    FeatureStatus.AVAILABLE -> {
                        Log.i(TAG, "Gemini Nano: AVAILABLE (status=$status)")
                        true
                    }
                    FeatureStatus.DOWNLOADABLE -> {
                        Log.i(TAG, "Gemini Nano: DOWNLOADABLE — triggering download")
                        try {
                            // download() returns Flow<DownloadStatus>
                            generativeModel!!.download().firstOrNull()
                            Log.i(TAG, "Gemini Nano download completed")
                            true
                        } catch (e: Exception) {
                            Log.w(TAG, "Gemini Nano download failed: ${e.message}")
                            false
                        }
                    }
                    else -> {
                        Log.w(TAG, "Gemini Nano: UNAVAILABLE (status=$status) — using rule-based fallback")
                        false
                    }
                }
            } catch (e: GenAiException) {
                Log.e(TAG, "AICore GenAiException: ${e.message}", e)
                isAvailable = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check AICore availability: ${e.message}", e)
                isAvailable = false
            }

            availabilityChecked = true
            isAvailable
        }
    }

    /**
     * Returns true if Gemini Nano is available for inference.
     */
    fun isAgentAvailable(): Boolean = isAvailable

    // ── Core Processing ───────────────────────────────────────────────

    /**
     * Process a user query with the on-device agent.
     *
     * @param userQuery      Freeform text from voice recognition or gesture intent.
     * @param mode           The system prompt mode to use.
     * @param sceneContext   JSON string from [AgentContextBuilder] describing current scene.
     * @param ocrText        Latest OCR text (used for DOCUMENT / MEDICINE modes).
     * @return The agent's natural-language response, ready for TTS.
     */
    suspend fun processQuery(
        userQuery: String,
        mode: Mode,
        sceneContext: String = "{}",
        ocrText: String = ""
    ): String {
        if (!isAvailable) {
            Log.d(TAG, "Agent unavailable — using rule-based fallback for mode=$mode")
            return ruleBasedFallback(userQuery, mode, sceneContext, ocrText)
        }

        return try {
            val fullPrompt = buildFullPrompt(mode, userQuery, sceneContext, ocrText)
            val response = callGeminiNano(fullPrompt)
            response.ifBlank {
                Log.w(TAG, "Empty response from Gemini Nano, falling back")
                ruleBasedFallback(userQuery, mode, sceneContext, ocrText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini Nano inference failed: ${e.message}", e)
            ruleBasedFallback(userQuery, mode, sceneContext, ocrText)
        }
    }

    // ── Gemini Nano Inference ─────────────────────────────────────────

    /**
     * Call Gemini Nano with a text prompt.
     *
     * The beta1 API exposes:
     * - `GenerativeModel.generateContent(String, Continuation)` — suspend function
     * - Returns `GenerateContentResponse` with `candidates: List<Candidate>`
     * - Each `Candidate` has `getText(): String`
     */
    private suspend fun callGeminiNano(prompt: String): String {
        val model = generativeModel ?: throw IllegalStateException("GenerativeModel not initialized")

        return withContext(Dispatchers.IO) {
            // Build the request using the Kotlin DSL
            val request = generateContentRequest(TextPart(prompt)) {
                temperature = TEMPERATURE
                maxOutputTokens = MAX_OUTPUT_TOKENS
            }

            // generateContent is a suspend function in beta1
            val response = model.generateContent(request)

            // Extract text from the first candidate
            val candidates = response.candidates
            if (candidates.isNullOrEmpty()) {
                Log.w(TAG, "No candidates in response")
                return@withContext ""
            }

            val text = candidates.first().text ?: ""
            Log.d(TAG, "Gemini response: ${text.take(200)}...")
            text
        }
    }

    // ── System Prompts ────────────────────────────────────────────────

    private fun buildFullPrompt(
        mode: Mode,
        userQuery: String,
        sceneContext: String,
        ocrText: String
    ): String {
        val systemPrompt = when (mode) {
            Mode.SCENE_NAVIGATION -> SCENE_NAVIGATION_PROMPT
            Mode.TARGET_SEARCH -> TARGET_SEARCH_PROMPT
            Mode.DOCUMENT_READER -> DOCUMENT_READER_PROMPT
            Mode.MEDICINE_READER -> MEDICINE_READER_PROMPT
        }

        return buildString {
            appendLine(systemPrompt)
            appendLine()
            appendLine("--- CURRENT SCENE DATA ---")
            appendLine(sceneContext)

            if (ocrText.isNotBlank()) {
                appendLine()
                appendLine("--- VISIBLE TEXT (OCR) ---")
                appendLine(ocrText)
            }

            appendLine()
            appendLine("--- USER QUERY ---")
            appendLine(userQuery)
            appendLine()
            appendLine("Respond with a single concise spoken sentence. Do not use bullet points, markdown, or formatting. Speak naturally as if guiding a blind person.")
        }
    }

    // ── Rule-Based Fallback ───────────────────────────────────────────

    /**
     * Deterministic fallback when Gemini Nano is unavailable.
     * Produces a useful spoken response from structured context without any ML inference.
     */
    private fun ruleBasedFallback(
        userQuery: String,
        mode: Mode,
        sceneContext: String,
        ocrText: String
    ): String {
        return when (mode) {
            Mode.SCENE_NAVIGATION -> fallbackSceneNavigation(sceneContext)
            Mode.TARGET_SEARCH -> fallbackTargetSearch(userQuery, sceneContext)
            Mode.DOCUMENT_READER -> fallbackDocumentReader(ocrText)
            Mode.MEDICINE_READER -> fallbackMedicineReader(ocrText)
        }
    }

    private fun fallbackSceneNavigation(sceneContext: String): String {
        return try {
            val json = org.json.JSONObject(sceneContext)
            val scene = json.optJSONObject("scene") ?: return "No scene data available."
            val objects = scene.optJSONArray("objects") ?: return "No objects detected."
            val ocrText = scene.optString("ocr_text", "")

            if (objects.length() == 0 && ocrText.isBlank()) {
                return "No objects or text currently detected in view."
            }

            val parts = mutableListOf<String>()

            // Group by zone
            val left = mutableListOf<String>()
            val center = mutableListOf<String>()
            val right = mutableListOf<String>()

            for (i in 0 until objects.length()) {
                val obj = objects.getJSONObject(i)
                val label = obj.getString("label")
                val zone = obj.getString("zone")
                val proximity = obj.optString("proximity", "medium")

                val desc = if (proximity != "medium") "$label, $proximity" else label
                when (zone) {
                    "left" -> left.add(desc)
                    "center" -> center.add(desc)
                    "right" -> right.add(desc)
                }
            }

            if (center.isNotEmpty()) parts.add("Directly ahead: ${formatList(center)}")
            if (left.isNotEmpty()) parts.add("On your left: ${formatList(left)}")
            if (right.isNotEmpty()) parts.add("On your right: ${formatList(right)}")

            if (ocrText.isNotBlank()) {
                parts.add("Text visible: $ocrText")
            }

            parts.joinToString(". ") + "."
        } catch (e: Exception) {
            Log.e(TAG, "Rule-based scene fallback failed", e)
            "Unable to parse scene data."
        }
    }

    private fun fallbackTargetSearch(userQuery: String, sceneContext: String): String {
        val target = userQuery
            .lowercase()
            .replace(Regex("(where|find|show me|look for|is there|search for)"), "")
            .trim()

        return try {
            val json = org.json.JSONObject(sceneContext)
            val scene = json.optJSONObject("scene") ?: return "No scene data."
            val objects = scene.optJSONArray("objects") ?: return "No objects detected."

            val matches = mutableListOf<String>()
            for (i in 0 until objects.length()) {
                val obj = objects.getJSONObject(i)
                val label = obj.getString("label").lowercase()
                if (label.contains(target) || target.contains(label)) {
                    val zone = obj.getString("zone")
                    val proximity = obj.optString("proximity", "medium")
                    matches.add("$label, $zone, $proximity")
                }
            }

            if (matches.isNotEmpty()) {
                "Found ${formatList(matches)}."
            } else {
                "No $target detected in current view. Try moving the camera."
            }
        } catch (e: Exception) {
            "Unable to search for $target."
        }
    }

    private fun fallbackDocumentReader(ocrText: String): String {
        if (ocrText.isBlank()) return "No text detected. Point the camera at a sign or document."
        val truncated = if (ocrText.length > 500) {
            ocrText.take(500) + "... text continues."
        } else {
            ocrText
        }
        return "Reading: $truncated"
    }

    private fun fallbackMedicineReader(ocrText: String): String {
        if (ocrText.isBlank()) return "No medicine label detected. Point the camera at the label."
        return "Medicine label detected. Reading: ${ocrText.take(400)}. For detailed dosage analysis, Gemini Nano is required on this device."
    }

    // ── Cleanup ───────────────────────────────────────────────────────

    fun close() {
        generativeModel?.close()
        generativeModel = null
        isAvailable = false
        availabilityChecked = false
        Log.d(TAG, "AgentEngine closed")
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun formatList(items: List<String>): String {
        return when (items.size) {
            0 -> ""
            1 -> items[0]
            2 -> "${items[0]} and ${items[1]}"
            else -> items.dropLast(1).joinToString(", ") + ", and ${items.last()}"
        }
    }

    // ── Agent Modes ───────────────────────────────────────────────────

    enum class Mode {
        /** General spatial navigation — "What's around me?" */
        SCENE_NAVIGATION,

        /** Targeted search — "Where is the chair?" */
        TARGET_SEARCH,

        /** Document/OCR reading — "Read this sign." */
        DOCUMENT_READER,

        /** Medicine label parsing — "What's this medication?" */
        MEDICINE_READER
    }

    // ── Companion ─────────────────────────────────────────────────────

    companion object {
        private const val TEMPERATURE = 0.3f
        private const val MAX_OUTPUT_TOKENS = 150

        // ── System Prompts ────────────────────────────────────────────

        private const val SCENE_NAVIGATION_PROMPT = """You are Vyze, an offline audio assistant for a visually impaired user.
You will receive JSON data containing object labels, spatial positions (left, center, right), and estimated distances.

Your Task:
1. Synthesize a natural, spoken summary of the environment.
2. Focus ONLY on actionable information: clear pathways, immediate obstacles, and available seating.
3. Keep response under 2 sentences.
4. Speak in the second person ("on your left", "directly ahead of you").
5. Do NOT mention raw confidence scores, JSON structures, bounding boxes, or technical terms."""

        private const val TARGET_SEARCH_PROMPT = """You are Vyze, an offline object locator.
The user is searching for a specific target item. You will receive a target query and current spatial detections.

Your Task:
1. Search the JSON detections for the requested item.
2. If found, state its exact clock-face direction or relative position and distance in 1 concise sentence (e.g., "The coffee mug is about 2 feet away on your right").
3. If NOT found, simply state: "I don't see that in the current view."
4. Never list irrelevant objects. Keep responses under 12 words."""

        private const val DOCUMENT_READER_PROMPT = """You are Vyze, an accessible reading assistant.
You will receive raw, messy text captured from camera OCR.

Your Task:
1. Fix minor OCR typos and spelling glitches.
2. Summarize the core message or headline clearly for Text-to-Speech output.
3. If reading a sign, speak the main message immediately.
4. If reading a long document, summarize the primary purpose in 2 clear sentences."""

        private const val MEDICINE_READER_PROMPT = """You are Vyze, a safety-focused medical label reader.
You will receive OCR text extracted from a medication container.

Your Task:
1. Extract exactly 3 fields: Medication Name, Dosage/Directions, and Critical Warnings.
2. Format as a direct spoken response: "[Name]. [Dosage]. [Warnings]."
3. Critical Rule: Never invent, guess, or hallucinate medical dosages. If the text is incomplete or blurry, say: "Text is incomplete. Please re-align the medicine bottle.""""
    }
}
