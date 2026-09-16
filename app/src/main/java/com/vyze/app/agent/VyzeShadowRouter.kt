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
                        "model",
                        "shadow:${decision.action} reason=${decision.reason}",
                    ),
                )
            )
            emitEndOfAgent(ctx) // member-extension on FlowCollector; completion event
        }
}

// ── Phase 5: LIVE query agent (orchestrates the native engine op) ────

/**
 * The Phase 5 live-query orchestrator. Like [VyzeRouterAgent], this is a
 * real ADK [BaseAgent] whose [runAsyncImpl] emits real ADK [Event]s — the
 * live answer flows through the actual runner machinery.
 *
 * HYBRID PATHING (Phase 0 directive #3): there is NO external LLM here.
 * The heavyweight reasoning is performed by the native LiteRT-LM engine
 * through the injected [answerOp] (bound to
 * VyzeCoreController.analyzeTextDirect by the caller). The agent's job is
 * the ORCHESTRATION that previously lived inline in the fragment's legacy
 * ladder, moved here as data:
 *
 *  - CONTEXT ASSEMBLY: [queryContext] mirrors what the native text-query
 *    path assembles (persona/directive lines, session state) — assembled
 *    INSIDE the agent and applied to session state so every turn of the
 *    episode carries it, instead of every call site re-string-building it.
 *    The native path is untouched; this mirrors its behavior for the
 *    agent lane only.
 *  - SESSION EPISODES: the runner's session id IS the `adk_text_<n>`
 *    episode id managed by [SessionEpisodeManager] — one ADK session per
 *    live query, thermally-shrunk idle eviction, no cross-talk with the
 *    native pipeline's sessions (stale-session gate).
 *  - HARDWARE INVARIANTS: no frame access of any kind (text-only lane);
 *    ThermalPowerController consulted read-only by the CALLER's gates,
 *    never overridden here.
 *
 * Engine busy-state is declined by [com.vyze.app.core.VyzeCoreController
 * .analyzeTextDirect] itself (isInferring CAS) — the agent can never
 * race or reorder a native generation.
 */
class VyzeLiveQueryAgent(
    /**
     * The engine op: (prompt, sessionId) -> answer?. Bound to
     * VyzeCoreController.analyzeTextDirect — its internal isInferring CAS
     * + watchdog + cancelInference discipline is the decline path.
     */
    private val answerOp: suspend (prompt: String, sessionId: String) -> String?,
    /**
     * Context assembly mirroring the native text-query path (persona
     * lines, directives). Pure over its inputs so tests can assert on it.
     */
    private val queryContext: QueryContext = QueryContext(),
) : BaseAgent(
    name = "vyze_live_query",
    description = "Vyze live text-query orchestrator over the native LiteRT-LM engine",
) {

    /**
     * Context assembly inputs — mirrors DynamicPromptBuilder's text-only
     * behavior: persona/directive lines + locale brevity, applied as ADK
     * session state so the instruction text travels with the episode.
     */
    data class QueryContext(
        val personaDirective: String = DEFAULT_PERSONA_DIRECTIVE,
        val answerStyleDirective: String = DEFAULT_ANSWER_STYLE_DIRECTIVE,
    ) {
        /** The system-style preamble prepended to the user's question. */
        fun instructionFor(question: String): String =
            "$personaDirective\n$answerStyleDirective\n\nUser question: $question"

        fun asSessionState(): Map<String, Any> = mapOf(
            "persona" to personaDirective,
            "answer_style" to answerStyleDirective,
            "lane" to "adk_live_text",
        )

        companion object {
            /** Mirrors the native persona: concise, sighted-assistant voice. */
            const val DEFAULT_PERSONA_DIRECTIVE =
                "You are Vyze, a concise sighted assistant for a blind user. " +
                    "Answer in the user's language (English, Bahasa Melayu, or Chinese)."

            /** Mirrors the native brevity/audibility style for TTS delivery. */
            const val DEFAULT_ANSWER_STYLE_DIRECTIVE =
                "Answer in 1-3 short spoken sentences. No markdown, no lists, " +
                    "no emoji. Lead with the direct answer."
        }
    }

    override fun runAsyncImpl(ctx: InvocationContext): kotlinx.coroutines.flow.Flow<Event> =
        kotlinx.coroutines.flow.flow {
            val sessionId = ctx.session.key.id ?: "adk_orphan"
            val question = ctx.userContent?.parts
                ?.mapNotNull { it.text }
                ?.joinToString(" ")
                ?.trim()
                .orEmpty()

            if (question.isEmpty()) {
                emit(
                    Event(
                        invocationId = ctx.invocationId,
                        author = name,
                        content = Content.fromText("model", ""),
                    )
                )
                emitEndOfAgent(ctx)
                return@flow
            }

            // CONTEXT ASSEMBLY inside the agent (Phase 5): the prompt that
            // reaches the engine carries the persona + style directives.
            val prompt = queryContext.instructionFor(question)

            val answer = answerOp(prompt, sessionId)

            emit(
                Event(
                    invocationId = ctx.invocationId,
                    author = name,
                    // Content.fromText(role, text) — role FIRST (0.2.0 API).
                    content = Content.fromText("model", answer.orEmpty()),
                )
            )
            emitEndOfAgent(ctx)
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

    /** True when [sessionId] currently has an open episode. */
    @Synchronized
    fun exists(sessionId: String): Boolean = episodes.containsKey(sessionId)

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
}

// ── Phase 6: shadow→live evaluation evidence ─────────────────────────

/** One bounded eval observation (pure data; no Android deps). */
data class ShadowEvalRecord(
    val query: String,
    /** The adk_text_ session id when the query EXECUTED; null on declines. */
    val sessionId: String?,
    val outcome: Outcome,
    val latencyMs: Long? = null,
    val recordedAtMs: Long = System.currentTimeMillis(),
) {
    enum class Outcome {
        /** Executed through the ADK runner; a non-empty answer returned. */
        ANSWERED,
        /** Executed but the engine returned null/empty or threw. */
        FAILED,
        /** A grant gate refused (flag/action/readiness/thermal). */
        DECLINED,
        /** Refused because another agent-path generation was in flight. */
        DECLINED_BUSY,
    }
}

/**
 * Bounded ring of eval observations + pure summary math. NOT a general
 * telemetry sink: its only consumer is the Phase 6 flip review
 * ([VyzeAgentRuntime.shouldPromoteToLive]) and JVM tests.
 */
class ShadowEvalRecorder(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    data class Summary(
        val total: Int,
        val answered: Int,
        val failed: Int,
        val declined: Int,
        val declinedBusy: Int,
        val avgLatencyMs: Long,
        val successRate: Double,
    )

    private val ring = ArrayDeque<ShadowEvalRecord>(capacity)

    @Synchronized
    fun record(record: ShadowEvalRecord) {
        if (ring.size >= capacity) ring.removeFirst()
        ring.addLast(record)
    }

    @Synchronized
    fun snapshot(): List<ShadowEvalRecord> = ring.toList()

    @Synchronized
    fun clear() = ring.clear()

    @Synchronized
    fun summarize(): Summary {
        if (ring.isEmpty()) {
            return Summary(0, 0, 0, 0, 0, 0L, 0.0)
        }
        var answered = 0
        var failed = 0
        var declined = 0
        var declinedBusy = 0
        var latencySum = 0L
        var latencyCount = 0
        for (r in ring) {
            when (r.outcome) {
                ShadowEvalRecord.Outcome.ANSWERED -> {
                    answered++
                    r.latencyMs?.let { latencySum += it; latencyCount++ }
                }
                ShadowEvalRecord.Outcome.FAILED -> failed++
                ShadowEvalRecord.Outcome.DECLINED -> declined++
                ShadowEvalRecord.Outcome.DECLINED_BUSY -> declinedBusy++
            }
        }
        val total = ring.size
        return Summary(
            total = total,
            answered = answered,
            failed = failed,
            declined = declined,
            declinedBusy = declinedBusy,
            avgLatencyMs = if (latencyCount == 0) 0L else latencySum / latencyCount,
            successRate = answered.toDouble() / total,
        )
    }

    companion object {
        const val DEFAULT_CAPACITY = 200

        /** Promotion bar: at least this many EXECUTED-or-refused observations. */
        const val PROMOTION_MIN_ATTEMPTS = 30

        /** Promotion bar: at least this many executed answers. */
        const val PROMOTION_MIN_ANSWERED = 20

        /** Promotion bar: answered/total at or above this ratio. */
        const val PROMOTION_ANSWER_RATE = 0.80
    }
}

// ── Runtime holder (flag-gated; ships dark) ─────────────────────────

/**
 * Lazily constructs the ADK-native pieces, executes the LIVE route when
 * the shadow flag is enabled (Phase 4), through the REAL ADK
 * [com.google.adk.kt.runners.InMemoryRunner] (Phase 5), and records the
 * shadow→live evaluation evidence + runs session maintenance (Phase 6).
 *
 * LIVE ROUTING SCOPE (approved constraint: never race native generation):
 * the ADK agent path routes TEXT-ONLY queries end-to-end. Frame-dependent
 * decisions decline here and fall back to the legacy dispatch — frame
 * capture remains exclusively inside the camera layer's isCapturing-gated
 * paths.
 *
 * PHASE 6 — the flip is EVAL-DRIVEN and MANUAL: [shouldPromoteToLive]
 * summarizes the recorded outcomes so a human decides when the agent lane
 * has earned the flag flip. The runtime never flips [shadowEnabled] by
 * itself and never disables the legacy path.
 */
object VyzeAgentRuntime {

    private const val TAG = "VyzeAgentRuntime"
    private const val APP_NAME = "vyze"
    private const val USER_ID = "vyze_user"
    private var runner: com.google.adk.kt.runners.InMemoryRunner? = null
    private var routerAgent: VyzeRouterAgent? = null

    /** Phase 5: the live query orchestrator + its runner (same session service). */
    private var liveAgent: VyzeLiveQueryAgent? = null
    private var liveRunner: com.google.adk.kt.runners.InMemoryRunner? = null
    private val episodeManager = SessionEpisodeManager()

    /** Phase 6: bounded shadow→live evaluation evidence for the flip review. */
    private val evalRecorder = ShadowEvalRecorder()

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
        queryContext: VyzeLiveQueryAgent.QueryContext = VyzeLiveQueryAgent.QueryContext(),
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
            // Phase 6: the decline tally is the eval baseline — a decline is
            // NOT a legacy mismatch; it just means the agent lane passed.
            evalRecorder.record(
                ShadowEvalRecord(
                    query = textOnlyQuery,
                    sessionId = null,
                    outcome = ShadowEvalRecord.Outcome.DECLINED,
                )
            )
            return null // decline → caller falls back to the legacy dispatch
        }

        // One live agent-path generation at a time (decline, never queue —
        // queuing would reorder answers against the legacy pipeline).
        if (isLiveGenerationActive) {
            evalRecorder.record(
                ShadowEvalRecord(
                    query = textOnlyQuery,
                    sessionId = null,
                    outcome = ShadowEvalRecord.Outcome.DECLINED_BUSY,
                )
            )
            return null
        }

        // PHASE 5: the live path executes through the REAL ADK InMemoryRunner
        // (runAsync event pipeline). The session id IS the episode id — the
        // runner auto-creates the missing session, the agent applies context
        // and calls the injected engine op, and the final model Event carries
        // the answer back to the caller.
        val sessionId = "adk_text_${textSessionSeq.incrementAndGet()}"
        episodeManager.openOrTouch(sessionId)
        if (!liveGenerationActive.compareAndSet(false, true)) return null
        val startedAt = kotlin.time.TimeSource.Monotonic.markNow()

        var execRunner: com.google.adk.kt.runners.InMemoryRunner? = null
        try {
            execRunner = ensureLiveRunner(analyzeText, queryContext)
            if (execRunner == null) {
                Log.w(TAG, "Live runner unavailable (flag off?) — declining")
                return null
            }
            val events = execRunner.runAsync(
                userId = USER_ID,
                sessionId = sessionId,
                invocationId = null,
                // Content.fromText(role, text) — role FIRST (0.2.0 API).
                newMessage = Content.fromText("user", textOnlyQuery),
                // Context assembly travels as ADK session state — applied by
                // the runner at invocation setup, so the whole episode carries
                // the persona/style directives (Phase 5 design).
                stateDelta = queryContext.asSessionState(),
                runConfig = com.google.adk.kt.agents.RunConfig(),
            )
            // The agent emits exactly one text event (empty on decline) plus
            // the end-of-agent marker; take the LAST text as the answer.
            var answer: String? = null
            events.collect { event ->
                val text = event.content?.parts
                    ?.mapNotNull { it.text }
                    ?.joinToString("")
                    .orEmpty()
                if (text.isNotEmpty()) answer = text
            }
            if (answer == null) {
                Log.w(TAG, "Live agent-path text query returned null (declining)")
                evalRecorder.record(
                    ShadowEvalRecord(
                        query = textOnlyQuery,
                        sessionId = sessionId,
                        outcome = ShadowEvalRecord.Outcome.FAILED,
                    )
                )
                return null
            }
            Log.i(TAG, "Live agent-path text query answered via $sessionId (ADK runner)")
            evalRecorder.record(
                ShadowEvalRecord(
                    query = textOnlyQuery,
                    sessionId = sessionId,
                    outcome = ShadowEvalRecord.Outcome.ANSWERED,
                    latencyMs = startedAt.elapsedNow().inWholeMilliseconds,
                )
            )
            return answer
        } catch (t: Throwable) {
            Log.w(TAG, "Live agent-path text query failed: ${t.message}")
            evalRecorder.record(
                ShadowEvalRecord(
                    query = textOnlyQuery,
                    sessionId = sessionId,
                    outcome = ShadowEvalRecord.Outcome.FAILED,
                )
            )
            return null
        } finally {
            liveGenerationActive.set(false)
            episodeManager.close(sessionId)
            // Session-per-turn hygiene: the ADK session is never reused (each
            // query gets a fresh adk_text_<n> id), so delete it to keep the
            // runner's in-memory session store bounded. NonCancellable so the
            // cleanup also runs when the caller's coroutine was cancelled.
            if (execRunner != null) {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        execRunner!!.sessionService.deleteSession(
                            com.google.adk.kt.sessions.SessionKey(APP_NAME, USER_ID, sessionId)
                        )
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "ADK session cleanup failed: ${t.message}")
                }
            }
        }
    }

    /** Lazily constructs the Phase 5 live agent + runner over its own session service. */
    @Synchronized
    private fun ensureLiveRunner(
        analyzeText: suspend (prompt: String, sessionId: String) -> String?,
        queryContext: VyzeLiveQueryAgent.QueryContext,
    ): com.google.adk.kt.runners.InMemoryRunner? {
        if (!shadowEnabled) return null
        if (liveRunner == null || liveAgent == null) {
            liveAgent = VyzeLiveQueryAgent(answerOp = analyzeText, queryContext = queryContext)
            liveRunner = com.google.adk.kt.runners.InMemoryRunner(
                agent = liveAgent!!, 
                appName = "vyze",
            )
            Log.i(TAG, "Live query runner constructed (agent lane over native engine)")
        }
        return liveRunner
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

    // ── Phase 6: eval evidence + manual flip review ──────────────────

    /** Immutable summary of the bounded eval window (pure data). */
    data class EvalSummary(
        val total: Int,
        val answered: Int,
        val failed: Int,
        val declined: Int,
        val declinedBusy: Int,
        val avgLatencyMs: Long,
        val successRate: Double,
    )

    /** Current bounded eval evidence (a snapshot; cheap to read). */
    fun evalSummary(): EvalSummary {
        val s = evalRecorder.summarize()
        return EvalSummary(
            total = s.total,
            answered = s.answered,
            failed = s.failed,
            declined = s.declined,
            declinedBusy = s.declinedBusy,
            avgLatencyMs = s.avgLatencyMs,
            successRate = s.successRate,
        )
    }

    /**
     * Whether the bounded eval window meets the promotion bar for a HUMAN
     * to flip [shadowEnabled]. The runtime NEVER flips the flag itself and
     * NEVER disables the legacy path — this predicate only informs review.
     *
     * Bar: enough executed attempts, answer rate at/above
     * [ShadowEvalRecorder.PROMOTION_ANSWER_RATE], and the window not
     * dominated by declines (which would say more about routing than
     * about answer quality).
     */
    fun shouldPromoteToLive(): Boolean {
        val s = evalRecorder.summarize()
        if (s.total < ShadowEvalRecorder.PROMOTION_MIN_ATTEMPTS) return false
        if (s.answered < ShadowEvalRecorder.PROMOTION_MIN_ANSWERED) return false
        if (s.answered.toDouble() / s.total < ShadowEvalRecorder.PROMOTION_ANSWER_RATE) return false
        if (s.declined + s.declinedBusy > s.total / 2) return false
        return true
    }

    /** Clears the eval window (after a flip review is recorded elsewhere). */
    @Synchronized
    fun resetEvalForTests() {
        evalRecorder.clear()
    }

    /**
     * PHASE 6 — PERIODIC MAINTENANCE (call from a lifecycle-aware scope,
     * e.g. every 60 s while the fragment is started):
     *
     *  1. Episode eviction via [SessionEpisodeManager.evictIdle] — the
     *     idle window shrinks automatically under thermal pressure
     *     (Section 6 design; [ThermalPowerController]-equivalent status is
     *     supplied read-only by the caller).
     *  2. Defense-in-depth orphan sweep: any ADK session in the live lane
     *     that no longer has an open episode is deleted. Normal turns
     *     already delete their session in `finally`; this catches leaks
     *     from cancelled/aborted turns so the in-memory store stays
     *     bounded no matter what.
     *
     * No VLM/TTS/hardware interaction; safe to call at any time. Returns
     * the number of orphaned ADK sessions deleted.
     */
    suspend fun maintenanceTick(
        isThermallyConstrained: () -> Boolean = { false },
    ): Int {
        val evicted = episodeManager.evictIdle(isThermallyConstrained())
        if (evicted.isNotEmpty()) {
            Log.d(TAG, "Maintenance: evicted ${evicted.size} idle episode(s)")
        }

        val service = liveSessionService() ?: return 0
        val live = runCatching {
            service.listSessions(APP_NAME, USER_ID)
        }.getOrElse { return 0 }
        var deleted = 0
        for (session in live.sessions) {
            val id = session.key.id ?: continue
            if ("adk_text_" !in id) continue // only our lane's sessions
            if (episodeManager.exists(id)) continue
            runCatching {
                service.deleteSession(com.google.adk.kt.sessions.SessionKey(APP_NAME, USER_ID, id))
                deleted++
            }.onFailure { Log.w(TAG, "Orphan sweep delete failed for $id: ${it.message}") }
        }
        if (deleted > 0) {
            Log.d(TAG, "Maintenance: deleted $deleted orphaned ADK session(s)")
        }
        return deleted
    }

    /**
     * The live lane's ADK session service — ops/inspection hook (Phase 6
     * eviction tooling); null until the live runner is first constructed.
     */
    @Synchronized
    fun liveSessionService(): com.google.adk.kt.sessions.SessionService? = liveRunner?.sessionService

    @Synchronized
    fun releaseForTests() {
        runner = null
        routerAgent = null
        liveRunner = null
        liveAgent = null
        liveGenerationActive.set(false)
        evalRecorder.clear()
        shadowEnabled = false
    }

    /** Forces live-lane runner reconstruction with the NEXT bound op — test isolation only. */
    @Synchronized
    fun resetLiveRunnerForTests() {
        liveRunner = null
        liveAgent = null
    }
}
