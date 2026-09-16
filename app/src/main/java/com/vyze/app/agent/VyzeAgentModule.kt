package com.vyze.app.agent

import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool

/**
 * Vyze <-> Google ADK orchestration module - PHASE 0 (INERT DEAD CODE).
 *
 * This file is intentionally never referenced by production code. It exists
 * to (a) pin the 3-agent Router-Worker topology from
 * Vyze_ADK_Migration_Plan-v4.pdf in code, (b) validate the ADK KSP tool
 * processor end-to-end at build time, and (c) stage the Phase 1 tool
 * contracts so later phases only add WIRING, never new contracts.
 *
 * HARDWARE INVARIANTS (approved directive, non-negotiable):
 *  - [com.vyze.app.ui.fragments.CameraFragment] `isCapturing` AtomicBoolean
 *    CAS gate is NEVER removed, refactored, or bypassed. Any future tool
 *    needing a camera frame must acquire it through the existing capture
 *    helper that owns that gate.
 *  - [com.vyze.app.core.ThermalPowerController] is NEVER bypassed; ADK
 *    session lifecycle (Phase 5) must defer to it.
 *  - [com.vyze.app.core.VlmEngineManager] `generationMutex` stays INTERNAL;
 *    the VLM Reasoning Agent wraps analyzeText/analyzeImage as a tool
 *    delegate (approved hybrid pathing) and never re-platforms the engine.
 *
 * Nothing in production calls this module until the Phase 3 shadow router
 * lands behind a debug flag.
 */
object VyzeAgentTopology {
    // ── Agent identity (Router-Worker hierarchy, spec sections 1-3) ──

    /** Host Router Agent - state-machine dispatcher, latency budget < 5 ms. */
    const val ROUTER_AGENT_NAME = "vyze_host_router"

    /** Fast Perception Agent - deterministic, zero-LLM, budget < 50 ms. */
    const val FAST_PERCEPTION_AGENT_NAME = "vyze_fast_perception"

    /** VLM Reasoning Agent - Gemma 4 via LiteRT-LM, hybrid tool delegate. */
    const val VLM_REASONING_AGENT_NAME = "vyze_vlm_reasoning"

    /**
     * Router instruction. Deterministic intents (tap-to-read, flashlight,
     * haptic confirmations) dispatch to the Fast Perception Agent WITHOUT
     * invoking the LLM engine; only complex visual/spatial queries are
     * escalated to the VLM Reasoning Agent.
     */
    const val ROUTER_INSTRUCTION =
        "You are the host router of the Vyze accessibility assistant. " +
            "Classify each user action: deterministic device actions route to " +
            "the fast perception agent; complex visual questions route to the " +
            "VLM reasoning agent. Never answer visual questions yourself."

    /**
     * VLM agent instruction. Language mirroring: answer in the user's
     * detected locale (en/ms/zh), concise spoken-style sentences, honour the
     * existing hedging/fallback contracts of the native pipeline.
     */
    const val VLM_REASONING_INSTRUCTION =
        "You are Vyze's visual reasoning agent. Answer concisely in spoken " +
            "style, mirroring the user's language (English, Bahasa Melayu, " +
            "or Chinese). If the scene does not clearly show the answer, say " +
            "so plainly instead of guessing."
}

/**
 * Phase 1 tool contracts for the Fast Perception Agent.
 *
 * KSP generates tool descriptors from these @Tool functions at build time.
 * The class is NOT instantiated anywhere in Phase 0; when wiring lands
 * (Phase 3/4) the constructor lambdas are injected from the existing
 * singletons - [com.vyze.app.vision.OcrHelper], [com.vyze.app.speech.TTSManager],
 * [com.vyze.app.device.HapticManager] and the tap-grid mapper - none of which
 * are modified or re-created by this module.
 *
 * `speakImmediate` MUST stay bound to TTSManager.speakImmediate() (the
 * existing bypass lane) so agent-driven deterministic utterances can never
 * pollute the streaming sentence buffer state machine (firstChunkSent /
 * currentChunkStarted) or its single-funnel flush logic.
 */
class FastPerceptionToolContracts(
    /** Runs the existing ML Kit OCR pipeline on the current camera frame.
     *  Nullable result: the contract coalesces null (no text found) to "". */
    private val ocrCurrentFrame: suspend (includeChinese: Boolean) -> String?,
    /** Maps a tap to its 3x3 sector via the existing gridSectorFor logic.
     * Phase 1 refinement: frame dimensions pass through verbatim so this is
     * a zero-reimplementation delegate of the native mapper. */
    private val gridSectorFor: (x: Int, y: Int, frameW: Int, frameH: Int) -> String,
    /** Binds to TTSManager.speakImmediate() - NEVER to the streaming path. */
    private val speakImmediate: (text: String) -> Boolean,
    /** Binds to the existing HapticManager pattern library. */
    private val hapticPattern: (name: String) -> Unit,
) {
    @Tool(description = "Performs instant on-device text recognition on the current camera frame; returns in under 50ms")
    suspend fun runFastOcr(
        @Param("Use true to merge the Chinese-capable recognizer for CJK signage") includeChinese: Boolean,
    ): Map<String, String> = mapOf("text" to (ocrCurrentFrame(includeChinese) ?: ""))

    @Tool(description = "Maps raw tap pixel coordinates to one of nine grid sectors of the camera frame")
    fun calculateSpatialGrid(
        @Param("Tap x pixel") x: Int,
        @Param("Tap y pixel") y: Int,
        @Param("Camera frame width in pixels") frameW: Int,
        @Param("Camera frame height in pixels") frameH: Int,
    ): Map<String, String> = mapOf("sector" to gridSectorFor(x, y, frameW, frameH))

    @Tool(description = "Speaks short deterministic text immediately without any generative model delay")
    fun speakImmediateTts(
        @Param("Text to speak verbatim") text: String,
    ): Map<String, String> = mapOf("queued" to speakImmediate(text).toString())

    @Tool(description = "Triggers a haptic feedback pattern for gesture confirmation")
    fun triggerHaptics(
        @Param("One of: TAP, DOUBLE_TAP, LONG_PRESS, WARNING") pattern: String,
    ): Map<String, String> {
        val name = pattern.uppercase()
        hapticPattern(name)
        return mapOf("triggered" to name)
    }
}

/**
 * Phase 2 hybrid-pathing tool contract for the VLM Reasoning Agent.
 *
 * The agent reaches the on-device Gemma engine ONLY through this delegate,
 * which binds to [com.vyze.app.core.VlmEngineManager.analyzeText] /
 * `analyzeImage` (see VyzeToolWiring). The engine's `generationMutex`,
 * session discipline, and LiteRT-LM runtime (litertlm-android 0.16.1)
 * remain 100% internal and untouched — ADK orchestrates the call, never
 * the engine. Camera frames come exclusively from the injected frame
 * provider (the camera layer's isCapturing-gated paths do all capture).
 *
 * Delegate semantics: return the model answer, or NULL when the engine
 * cannot serve the query (not initialized, inference error, or a requested
 * camera frame is unavailable). Null is mapped to a structured error
 * result so the agent degrades gracefully instead of crashing.
 */
class VlmReasoningToolContracts(
    /** Early-exit readiness probe (engine availability, thermal state). */
    private val isReady: () -> Boolean = { true },
    /** Binds to VlmEngineManager.analyzeText / analyzeImage in Phase 2.
     *  Null result means "cannot answer right now" — never an exception. */
    private val runQuery: suspend (prompt: String, includeCameraFrame: Boolean) -> String?,
) {
    @Tool(description = "Runs a complex visual or language reasoning query through the on-device Gemma engine")
    suspend fun runVlmQuery(
        @Param("The user's visual or conversational question") prompt: String,
        @Param("Set true to include the current camera frame for visual questions") includeCameraFrame: Boolean,
    ): Map<String, String> {
        if (!isReady()) {
            return mapOf("error" to "Vision engine is not ready")
        }
        return when (val answer = runQuery(prompt, includeCameraFrame)) {
            null -> mapOf("error" to "Engine could not answer this query")
            else -> mapOf("answer" to answer)
        }
    }
}
