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
    private const val APP_NAME = "vyze"
    private const val USER_ID = "vyze_user"
    private var runner: com.google.adk.kt.runners.InMemoryRunner? = null
    private var routerAgent: VyzeRouterAgent? = null

    /** Phase 5: the live query orchestrator + its runner (same session service). */
    private var liveAgent: VyzeLiveQueryAgent? = null
    private var liveRunner: com.google.adk.kt.runners.InMemoryRunner? = null
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
            return null // decline → caller falls back to the legacy dispatch
        }

        // One live agent-path generation at a time (decline, never queue —
        // queuing would reorder answers against the legacy pipeline).
        if (isLiveGenerationActive) return null

        // PHASE 5: the live path executes through the REAL ADK InMemoryRunner
        // (runAsync event pipeline). The session id IS the episode id — the
        // runner auto-creates the missing session, the agent applies context
        // and calls the injected engine op, and the final model Event carries
        // the answer back to the caller.
        val sessionId = "adk_text_${textSessionSeq.incrementAndGet()}"
        episodeManager.openOrTouch(sessionId)
        if (!liveGenerationActive.compareAndSet(false, true)) return null

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
                return null
            }
            Log.i(TAG, "Live agent-path text query answered via $sessionId (ADK runner)")
            return answer
        } catch (t: Throwable) {
            Log.w(TAG, "Live agent-path text query failed: ${t.message}")
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
        shadowEnabled = false
    }

    /** Forces live-lane runner reconstruction with the NEXT bound op — test isolation only. */
    @Synchronized
    fun resetLiveRunnerForTests() {
        liveRunner = null
        liveAgent = null
    }
}
