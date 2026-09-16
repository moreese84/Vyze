package com.vyze.app.agent

import android.content.Context
import android.graphics.Bitmap
import com.vyze.app.device.HapticManager
import com.vyze.app.speech.TTSManager
import com.vyze.app.vision.OcrHelper
import java.util.concurrent.atomic.AtomicLong

/**
 * PHASE 1 wiring: binds the [FastPerceptionToolContracts] and
 * [VlmReasoningToolContracts] to the REAL native singletons.
 *
 * Non-breaking by construction (approved migration constraints):
 *  - [OcrHelper] is instantiated here exactly like [com.vyze.app.core.VyzeCoreController]
 *    does (plain constructor); its ML Kit recognizers are untouched.
 *  - [TTSManager.getInstance] REUSES the existing process-wide singleton —
 *    it is never re-created or reconfigured.
 *  - [HapticManager] is constructed from the application context, following
 *    the exact pattern already used by MainActivity and CameraFragment.
 *  - `speakImmediate` binds to [TTSManager.speakImmediate] ONLY — the
 *    existing bypass lane. The streaming sentence buffer state machine
 *    (firstChunkSent / currentChunkStarted / single-funnel flush) is never
 *    reachable from agent-driven deterministic utterances.
 *  - No camera frame is acquired here: [latestFrameProvider] is an injection
 *    point that the camera layer fills in Phase 3 (read-only access to an
 *    already-delivered frame). The `isCapturing` CAS gate inside
 *    CameraFragment is NOT touched by this wiring and never will be.
 */
object VyzeToolWiring {

    /**
     * Default 3x3 sector mapper — byte-identical semantics to VyzeCoreController's
     * private gridSectorFor. Phase 3's shadow router replaces this with the native
     * reference and diffs the two, so any divergence becomes self-detecting.
     */
    fun defaultGridSectorMapper(x: Int, y: Int, frameW: Int, frameH: Int): String {
        val col = when {
            x < frameW / 3 -> "Left"
            x < frameW * 2 / 3 -> "Center"
            else -> "Right"
        }
        val row = when {
            y < frameH / 3 -> "Top"
            y < frameH * 2 / 3 -> "Middle"
            else -> "Bottom"
        }
        return "$row-$col"
    }

    /**
     * Build the Fast Perception tool set bound to the real native services.
     *
     * @param context any context; only the application context is used.
     * @param latestFrameProvider returns the most recent camera frame already
     *   delivered by the existing analyzer, or null when none is available.
     *   Phase 1 leaves this to the caller; the tool degrades gracefully
     *   (structured "error" result) instead of capturing on its own —
     *   frame acquisition stays exclusively inside the camera layer's
     *   existing isCapturing-gated paths.
     * @param gridMapper sector mapper; defaults to [defaultGridSectorMapper].
     */
    fun fromSingletons(
        context: Context,
        latestFrameProvider: (() -> android.graphics.Bitmap?)? = null,
        gridMapper: (x: Int, y: Int, frameW: Int, frameH: Int) -> String = ::defaultGridSectorMapper,
    ): FastPerceptionToolContracts {
        val appContext = context.applicationContext
        val ocr = OcrHelper()
        val tts = TTSManager.getInstance(appContext)
        val haptics = HapticManager(appContext)

        return FastPerceptionToolContracts(
            ocrCurrentFrame = { _ ->
                // OcrHelper.extractText already merges the latin + Chinese
                // recognizers internally, so includeChinese needs no distinct
                // branch today; the parameter stays for a future per-recognizer
                // fast path. Null frame -> null text (coalesced to "" by the
                // contract) so the tool degrades gracefully.
                val frame = latestFrameProvider?.invoke()
                frame?.let { ocr.extractText(it) }
            },
            gridSectorFor = gridMapper,
            speakImmediate = { text -> tts.speakImmediate(text) },
            hapticPattern = { name ->
                when (name.uppercase()) {
                    "TAP" -> haptics.vibrateTap()
                    "DOUBLE_TAP" -> haptics.vibrateDoubleTap()
                    "LONG_PRESS" -> haptics.vibrateLongPress()
                    "WARNING" -> haptics.vibrateWarning()
                    // Unknown names are ignored: haptics must never crash or
                    // misfire from a malformed tool argument.
                }
            },
        )
    }

    private val vlmSessionSeq = AtomicLong(0)

    /**
     * PHASE 2: VLM Reasoning delegate under the approved hybrid pathing.
     *
     * This adapter owns the AGENT-SIDE semantics only — readiness gating,
     * text vs. frame routing, fail-safe null handling, and the `adk_*`
     * session namespace — as pure logic over INJECTED engine hooks. It never
     * imports or constructs [com.vyze.app.core.VlmEngineManager]; Phase 3
     * binds the real engine as one-line hooks, e.g.:
     *
     * VyzeToolWiring.vlmDelegate(
     *     isEngineReady = coreController::isEngineReady,
     *     analyzeText = { prompt, sid -> vlm.analyzeText(prompt, sessionId = sid) },
     *     analyzeImage = { bmp, prompt, sid -> vlm.analyzeImage(bmp, prompt, sessionId = sid) },
     *     latestFrameProvider = frameSource,
     * )  // F inferred as android.graphics.Bitmap
     *
     * The engine's `generationMutex`, session discipline, and LiteRT-LM
     * runtime remain 100% internal — ADK orchestrates the call, never the
     * engine. The `adk_*` session id makes the native pipeline's
     * stale-session gate drop any engine callbacks from agent-path
     * inference, so agent traffic cannot cross-talk with native answers.
     *
     * Fail-safe defaults: unwired hooks return null, which the contract maps
     * to a structured error — a misconfigured agent degrades, never crashes.
     *
     * @param isEngineReady early-exit probe; Phase 3 binds
     *   VyzeCoreController::isEngineReady (the app's authoritative signal).
     * @param analyzeText text-only engine op (prompt, sessionId) -> answer?
     * @param analyzeImage frame engine op (bitmap, prompt, sessionId) -> answer?
     * @param latestFrameProvider read-only access to the most recent
     *   camera frame already delivered by the existing analyzer — capture
     *   itself stays exclusively inside the camera layer's isCapturing-gated
     *   paths, which this adapter never touches.
     */
    fun <F : Any> vlmDelegate(
        isEngineReady: () -> Boolean = { true },
        analyzeText: suspend (prompt: String, sessionId: String) -> String? = { _, _ -> null },
        analyzeImage: suspend (frame: F, prompt: String, sessionId: String) -> String? = { _, _, _ -> null },
        latestFrameProvider: (() -> F?)? = null,
    ): VlmReasoningToolContracts {
        val sessionId = "adk_vlm_${vlmSessionSeq.incrementAndGet()}"
        return VlmReasoningToolContracts(
            isReady = isEngineReady,
            runQuery = { prompt, includeCameraFrame ->
                if (includeCameraFrame) {
                    // A visual query without a frame must NOT be answered as
                    // if sighted — fail safe to the structured error instead
                    // of risking a hallucinated visual answer.
                    val frame = latestFrameProvider?.invoke()
                    if (frame != null) analyzeImage(frame, prompt, sessionId) else null
                } else {
                    analyzeText(prompt, sessionId)
                }
            },
        )
    }
}
