package com.vyze.app.agent

import android.util.Log
import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.events.Event
import com.google.adk.kt.types.Content
import java.util.concurrent.atomic.AtomicLong

/**
 * PHASE 3 — SHADOW ROUTER (approved migration plan, Phase 3).
 *
 * The [VyzeRouterAgent] runs the same routing decision as the legacy
 * gesture dispatch and logs `SHADOW-ROUTE:` lines — every decision is
 * observable in logcat, NONE is executed. Production behavior is
 * byte-identical to pre-Phase 3 unless the caller explicitly flips
 * [ShadowRouterConfig.enabled] (a Phase 4 decision; the flag ships false
 * and is not referenced by any production call path).
 *
 * Hardware invariants (binding constraints, Phase 0 directive #4):
 *  - `isCapturing` (CameraFragment AtomicBoolean CAS gate) is NEVER read,
 *    written, or bypassed here — frame acquisition stays exclusively in
 *    the camera layer.
 *  - [com.vyze.app.core.ThermalPowerController] remains the sole thermal
 *    authority: [SessionEpisodeManager] only *asks* it for the current
 *    thermal status and never overrides its decisions.
 */

// ── Pure decision taxonomy ───────────────────────────────────────────
// Mirrors the legacy gesture map (CameraFragment v2) + barge-in speech
// queries. Ordered: first match wins, so more specific intents must
// precede generic ones.

/** An incoming request signal (from a gesture or the ASR pipeline). */
data class RouterSignal(
    val kind: Kind,
    val spokenText: String = "",
    val tapX: Int = -1,
    val tapY: Int = -1,
) {
    enum class Kind { SINGLE_TAP, DOUBLE_TAP, LONG_PRESS, SPEECH }
}

/** The routing decision — what the legacy pipeline would do. */
data class RouterDecision(
    val action: Action,
    val reason: String,
    /** True when this must go to the heavyweight VLM engine path. */
    val requiresVlm: Boolean,
    /** True when the answer should include the current camera frame. */
    val includeCameraFrame: Boolean,
) {
    enum class Action {
        VLM_SCENE_DESCRIBE,   // single tap → "look"
        VLM_TEXT_READ,        // speech: read/OCR keywords
        VLM_VOICE_QUERY,      // speech: general question
        FAST_COLOR_ANALYSIS,  // triple tap (reserved; not a signal source yet)
        LIGHT_CHECK,          // long press
        SOS,                  // triple-tap-hold (reserved)
        IGNORE,               // empty/nonsensical input
    }
}

/** Pure decision input snapshot — everything needed, nothing more. */
data class RouterSnapshot(
    val engineReady: Boolean,
    val isInferring: Boolean,
    val captureAvailable: Boolean,
)

object VyzeShadowRouter {

    private const val TAG = "VyzeShadowRouter"

    // Reading-intent keywords, aligned with the legacy voice-query path
    // (OCR reading included automatically via reading keywords).
    private val READ_KEYWORDS =
        listOf("read", "text", "label", "sign", "baca", "teks", "字", "读", "念")

    /**
     * The routing decision for [signal] under [snapshot]. PURE — no I/O,
     * no engine calls, no state mutation; fully unit-testable.
     */
    fun decide(signal: RouterSignal, snapshot: RouterSnapshot): RouterDecision {
        // SOS is never shadow-routed or intercepted — legacy path is exact.
        // (SOS fires from the gesture layer before any router involvement.)

        return when (signal.kind) {
            RouterSignal.Kind.SINGLE_TAP ->
                RouterDecision(
                    action = RouterDecision.Action.VLM_SCENE_DESCRIBE,
                    reason = "single-tap look (legacy: bargeInAndCapture scene prompt)",
                    requiresVlm = true,
                    includeCameraFrame = true,
                )

            RouterSignal.Kind.LONG_PRESS ->
                RouterDecision(
                    action = RouterDecision.Action.LIGHT_CHECK,
                    reason = "long-press ambient light check (legacy: performLightCheck)",
                    requiresVlm = false,
                    includeCameraFrame = false,
                )

            RouterSignal.Kind.DOUBLE_TAP ->
                RouterDecision(
                    action = RouterDecision.Action.IGNORE,
                    reason = "double-tap opens the on-demand voice session in the UI layer; router defers",
                    requiresVlm = false,
                    includeCameraFrame = false,
                )

            RouterSignal.Kind.SPEECH -> decideSpeech(signal, snapshot)
        }
    }

    private fun decideSpeech(signal: RouterSignal, snapshot: RouterSnapshot): RouterDecision {
        val text = signal.spokenText.trim()
        if (text.isEmpty()) {
            return RouterDecision(
                action = RouterDecision.Action.IGNORE,
                reason = "empty transcript",
                requiresVlm = false,
                includeCameraFrame = false,
            )
        }
        val lower = text.lowercase()
        val isReadIntent = READ_KEYWORDS.any { lower.contains(it) }

        return if (isReadIntent) {
            RouterDecision(
                action = RouterDecision.Action.VLM_TEXT_READ,
                reason = "reading keyword (legacy: OCR pipeline still-capture path)",
                requiresVlm = true,
                includeCameraFrame = true,
            )
        } else {
            RouterDecision(
                action = RouterDecision.Action.VLM_VOICE_QUERY,
                reason = "general voice query (legacy: openVoiceQuery session)",
                requiresVlm = true,
                includeCameraFrame = true,
            )
        }.let { d ->
            if (!snapshot.engineReady) d.copy(
                reason = d.reason + " | engine NOT ready → legacy error-announce path",
            ) else d
        }
    }

    /**
     * Log one shadow decision. Kept as the single funnel so the Phase 4
     * flip only changes what happens AFTER this line, never the logging.
     */
    fun logDecision(signal: RouterSignal, decision: RouterDecision, snapshot: RouterSnapshot) {
        Log.i(
            TAG,
            "SHADOW-ROUTE: signal=${signal.kind} text=\"${signal.spokenText.take(40)}\" " +
                "→ ${decision.action} (vlm=${decision.requiresVlm}, " +
                "frame=${decision.includeCameraFrame}) | engineReady=${snapshot.engineReady} " +
                "inferring=${snapshot.isInferring} | ${decision.reason}"
        )
    }
}

// ── ADK-native agent wrapper ─────────────────────────────────────────

/**
 * Real ADK [BaseAgent]: the decision runs inside the ADK event pipeline
 * ([runAsyncImpl] emits ADK [Event]s), so shadow logs are produced by the
 * actual runner machinery that Phase 4/5 will use for live routing.
 *
 * Constructed lazily and only *run* when the debug flag is on; see
 * [VyzeAgentRuntime]. It performs no hardware access of any kind.
 */
class VyzeRouterAgent(
    private val snapshotProvider: () -> RouterSnapshot,
) : BaseAgent(
    name = "vyze_host_router",
    description = "Vyze host router (shadow mode): mirrors legacy gesture/speech dispatch",
) {
    val decisionCount = AtomicLong(0)

    override fun runAsyncImpl(ctx: InvocationContext): kotlinx.coroutines.flow.Flow<Event> =
        kotlinx.coroutines.flow.flow {
            val userText = ctx.userContent?.parts
                ?.mapNotNull { it.text }
                ?.joinToString(" ")
                .orEmpty()

            val signal = RouterSignal(
                kind = RouterSignal.Kind.SPEECH, // agent-path invocations are speech queries
                spokenText = userText,
            )
            val snapshot = snapshotProvider()
            val decision = VyzeShadowRouter.decide(signal, snapshot)
            VyzeShadowRouter.logDecision(signal, decision, snapshot)
            decisionCount.incrementAndGet()

            // Shadow mode: emit the decision as an event payload. Nothing is
            // executed — no VLM call, no TTS call, no capture.
            emit(
                Event(
                    invocationId = ctx.invocationId,
                    author = name,
                    content = Content.fromText(
                        "shadow:${decision.action} reason=${decision.reason}",
                        "model",
                    ),
                )
            )
            emitEndOfAgent(ctx) // member-extension on FlowCollector; completion event
        }
}

// ── Section 6: session episodes + idle eviction ──────────────────────

/**
 * One conversational episode = one ADK session, mirroring the native
 * pipeline's session-per-turn discipline. Episodes are reaped when idle,
 * and reaping is *accelerated* under thermal pressure by consulting the
 * existing [com.vyze.app.core.ThermalPowerController] status — the
 * controller remains the sole authority (its verdict is read, never
 * overridden).
 */
class SessionEpisodeManager(
    private val thermalStatusProvider: () -> String = { "unknown" },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val idleEvictMs: Long = 5 * 60_000L,
    private val thermalEvictMs: Long = 90_000L,
) {
    data class Episode(val sessionId: String, val createdAt: Long, var lastUsedAt: Long)

    private val episodes = LinkedHashMap<String, Episode>()

    @Synchronized
    fun openOrTouch(sessionId: String): Episode {
        val now = nowMs()
        return episodes.getOrPut(sessionId) { Episode(sessionId, now, now) }
            .also { it.lastUsedAt = now }
    }

    @Synchronized
    fun close(sessionId: String) {
        episodes.remove(sessionId)
    }

    /**
     * Evict idle episodes. Under elevated thermal status the idle window
     * shrinks from [idleEvictMs] to [thermalEvictMs] — fewer warm ADK
     * sessions on device under thermal pressure.
     *
     * @param isThermallyConstrained true when ThermalPowerController
     *   reports a throttled/limited state. Read-only consultation.
     */
    @Synchronized
    fun evictIdle(isThermallyConstrained: Boolean): List<String> {
        val now = nowMs()
        val window = if (isThermallyConstrained) thermalEvictMs else idleEvictMs
        val stale = episodes.values
            .filter { now - it.lastUsedAt > window }
            .map { it.sessionId }
        stale.forEach { episodes.remove(it) }
        return stale
    }

    @Synchronized
    fun size(): Int = episodes.size
}// ── Runtime holder (flag-gated; ships dark) ─────────────────────────

/**
 * Lazily constructs the ADK-native pieces and (Phase 4) executes the
 * LIVE route when the shadow flag is enabled.
 *
 * LIVE ROUTING SCOPE (approved constraint: never race native generation):
 * Phase 4 routes TEXT-ONLY queries end-to-end through the ADK agent path.
 * Frame-dependent decisions decline here and fall back to the legacy
 * dispatch — frame capture remains exclusively inside the camera layer's
 * isCapturing-gated paths.
 */
object VyzeAgentRuntime {

    private const val TAG = "VyzeAgentRuntime"
    private var runner: com.google.adk.kt.runners.InMemoryRunner? = null
    private var routerAgent: VyzeRouterAgent? = null
    private val episodeManager = SessionEpisodeManager()

    /** Flip point for Phase 4. Ships false; no production reader until the hook. */
    @Volatile
    var shadowEnabled: Boolean = false

    /** Monotonic counter for the `adk_text_<n>` session namespace. */
    private val textSessionSeq = AtomicLong(0)

    /** Tracks the one live agent-path generation for busy/decline gating. */
    private val liveGenerationActive = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True when an agent-path generation is in flight (decline gate). */
    val isLiveGenerationActive: Boolean get() = liveGenerationActive.get()

    /**
     * Attempt a LIVE agent-path route for a text-only query.
     *
     * All hardware/thermal/native-engine state arrives as INJECTED gates —
     * this runtime never touches isCapturing, never overrides
     * ThermalPowerController, and never imports VlmEngineManager.
     *
     * @param textOnlyQuery the user's text-only question.
     * @param snapshotProvider reads engine/inferring/capture state for the
     *   decision + logs (read-only).
     * @param vlmInferenceAllowed read-only consultation of the current
     *   ThermalPowerController policy.
     * @param analyzeText the engine op: (prompt, sessionId) -> answer? —
     *   bound to VlmEngineManager.analyzeText by the caller. The engine's
     *   generationMutex/session discipline stay 100% internal.
     * @return the structured answer string, or null when the route was
     *   declined (flag off, non-text-only, frame required, engine busy/
     *   not ready, thermal refusal, or runner unavailable). Declining is
     *   ALWAYS safe: the caller falls back to the legacy dispatch.
     */
    suspend fun tryLiveRouteTextOnly(
        textOnlyQuery: String,
        snapshotProvider: () -> RouterSnapshot,
        vlmInferenceAllowed: () -> Boolean,
        analyzeText: suspend (prompt: String, sessionId: String) -> String?,
    ): String? {
        val snapshot = snapshotProvider()
        val signal = RouterSignal(kind = RouterSignal.Kind.SPEECH, spokenText = textOnlyQuery)
        val decision = VyzeShadowRouter.decide(signal, snapshot)
        VyzeShadowRouter.logDecision(signal, decision, snapshot)

        // Live grant: general-knowledge voice queries ONLY. Reading intents
        // (VLM_TEXT_READ) and scene descriptions require the camera frame,
        // so they always decline to the camera layer's isCapturing-gated
        // paths — a sightless answer to a sight question is never allowed.
        // The caller (fragment text-only branch) classifies via the same
        // isTextOnlyQuery gate the legacy path uses, so VLM_VOICE_QUERY is
        // the semantically exact action here.
        if (!shadowEnabled || decision.action != RouterDecision.Action.VLM_VOICE_QUERY ||
            !snapshot.engineReady || !vlmInferenceAllowed()
        ) {
            return null // decline → caller falls back to the legacy dispatch
        }

        // One live agent-path generation at a time (decline, never queue —
        // queuing would reorder answers against the legacy pipeline).
        if (isLiveGenerationActive) return null

        // NOTE: the ADK InMemoryRunner session/execution wiring lands with the
        // Phase 5 runner-integration task; until then the live path exercises
        // the same router decision + episode + engine-op chain directly.
        val sessionId = "adk_text_${textSessionSeq.incrementAndGet()}"
        episodeManager.openOrTouch(sessionId)
        if (!liveGenerationActive.compareAndSet(false, true)) return null

        try {
            val answer = analyzeText(textOnlyQuery, sessionId)
            if (answer == null) {
                Log.w(TAG, "Live agent-path text query returned null (declining)")
                return null
            }
            Log.i(TAG, "Live agent-path text query answered via $sessionId")
            return answer
        } catch (t: Throwable) {
            Log.w(TAG, "Live agent-path text query failed: ${t.message}")
            return null
        } finally {
            liveGenerationActive.set(false)
            episodeManager.close(sessionId)
        }
    }

    @Synchronized
    fun ensureRunner(snapshotProvider: () -> RouterSnapshot): com.google.adk.kt.runners.InMemoryRunner? {
        if (!shadowEnabled) return null
        if (runner == null) {
            routerAgent = VyzeRouterAgent(snapshotProvider)
            runner = com.google.adk.kt.runners.InMemoryRunner(
                agent = routerAgent!!, 
                appName = "vyze",
            )
            Log.i(TAG, "Shadow router runner constructed (shadow mode)")
        }
        return runner
    }

    fun episodes(): SessionEpisodeManager = episodeManager

    @Synchronized
    fun releaseForTests() {
        runner = null
        routerAgent = null
        liveGenerationActive.set(false)
        shadowEnabled = false
    }
}
