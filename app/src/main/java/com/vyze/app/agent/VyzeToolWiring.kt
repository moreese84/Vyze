package com.vyze.app.agent

import android.content.Context
import com.vyze.app.device.HapticManager
import com.vyze.app.speech.TTSManager
import com.vyze.app.vision.OcrHelper

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

    /**
     * VLM Reasoning delegate wiring. The engine binding itself is Phase 2
     * (approved hybrid pathing): [runQuery] will bind to
     * VlmEngineManager.analyzeText/analyzeImage with the generationMutex
     * staying internal. Kept as a parameter so Phase 1 ships no engine call
     * sites at all.
     */
    fun vlmContracts(runQuery: suspend (prompt: String) -> String): VlmReasoningToolContracts =
        VlmReasoningToolContracts(runQuery)
}
