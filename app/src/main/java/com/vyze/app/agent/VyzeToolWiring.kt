package com.vyze.app.agent

import android.content.Context
import android.graphics.Bitmap
import com.vyze.app.device.HapticManager
import com.vyze.app.speech.TTSManager
import com.vyze.app.vision.OcrHelper

/**
 * VYZE MIGRATION PLAN v3 — UNIFIED REGISTRY WIRING (single master).
 *
 * Binds the master agent's native tool hooks to the REAL native
 * singletons. With the 3-agent hierarchy gone there are no per-sub-agent
 * tool-contract classes to wire — [VyzeMasterAgent.create] takes the
 * native lambdas directly, and this object is the single place where
 * those lambdas are produced from production services.
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
 *    point that the camera layer fills in (read-only access to an
 *    already-delivered frame). The `isCapturing` CAS gate inside
 *    CameraFragment is NOT touched by this wiring and never will be.
 *  - The engine ops in [MasterModelBridge] are injected by the caller —
 *    [com.vyze.app.core.VlmEngineManager]'s generationMutex, session
 *    discipline, and LiteRT-LM runtime remain 100% internal.
 */
object VyzeToolWiring {

    /**
     * Default 3x3 sector mapper — byte-identical semantics to VyzeCoreController's
     * private gridSectorFor (SpatialSectoringEngine native reference).
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
     * Build the [MasterModelBridge] over the local LiteRT-LM engine ops.
     *
     * @param analyzeText text-only engine op (prompt, sessionId) -> answer?
     * @param analyzeImage frame engine op (bitmap, prompt, sessionId) -> answer?
     */
    fun engineBridge(
        analyzeText: suspend (prompt: String, sessionId: String) -> String?,
        analyzeImage: suspend (frame: Bitmap, prompt: String, sessionId: String) -> String?,
    ): MasterModelBridge = MasterModelBridge(
        generateText = analyzeText,
        generateImage = analyzeImage,
    )

    /**
     * Bind the master agent's native tool hooks to the real services.
     *
     * @param context any context; only the application context is used.
     * @param latestFrameProvider returns the most recent camera frame already
     *   delivered by the existing analyzer, or null when none is available.
     *   The tool degrades gracefully (structured "error" result) instead of
     *   capturing on its own — frame acquisition stays exclusively inside
     *   the camera layer's existing isCapturing-gated paths.
     * @param gridMapper sector mapper; defaults to [defaultGridSectorMapper].
     */
    fun masterToolHooks(
        context: Context,
        latestFrameProvider: (() -> Bitmap?)? = null,
        gridMapper: (x: Int, y: Int, frameW: Int, frameH: Int) -> String = ::defaultGridSectorMapper,
    ): MasterToolHooks {
        val appContext = context.applicationContext
        val ocr = OcrHelper()
        val tts = TTSManager.getInstance(appContext)
        val haptics = HapticManager(appContext)

        return MasterToolHooks(
            frameProvider = {
                // Null frame -> null so tools degrade gracefully; OcrHelper
                // merges the latin + Chinese recognizers internally.
                latestFrameProvider?.invoke()
            },
            ocrFrame = { frame -> ocr.extractText(frame) },
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
            gridMapper = gridMapper,
        )
    }
}

/**
 * The native tool hooks for [VyzeMasterAgent.create] — the exact lambda
 * shape the unified registry binds, produced from real singletons by
 * [VyzeToolWiring.masterToolHooks].
 */
class MasterToolHooks(
    val frameProvider: () -> Bitmap?,
    val ocrFrame: suspend (Bitmap) -> String?,
    val speakImmediate: (String) -> Boolean,
    val hapticPattern: (String) -> Unit,
    val gridMapper: (x: Int, y: Int, frameW: Int, frameH: Int) -> String,
)
