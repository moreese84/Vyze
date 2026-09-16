package com.vyze.app.agent

import com.google.adk.kt.sessions.SessionKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 6 JVM tests: shadow→live evaluation evidence
 * ([ShadowEvalRecorder] + [VyzeAgentRuntime.shouldPromoteToLive]) and
 * session maintenance ([VyzeAgentRuntime.maintenanceTick]).
 *
 * The flip-predicate tests seed the window END-TO-END — by running real
 * (fast, fake-op) queries through [VyzeAgentRuntime.tryLiveRouteTextOnly]
 * — so the tally reflects exactly what production would record.
 */
class VyzeAgentEvalTest {

    @Before
    fun isolate() {
        VyzeAgentRuntime.shadowEnabled = false // flag is per-process state
        VyzeAgentRuntime.resetLiveRunnerForTests()
        VyzeAgentRuntime.resetEvalForTests()
    }

    private fun snapshot(
        engineReady: Boolean = true,
        isInferring: Boolean = false,
        captureAvailable: Boolean = false,
    ) = RouterSnapshot(engineReady, isInferring, captureAvailable)

    private fun enabledRuntime() = VyzeAgentRuntime.apply { shadowEnabled = true }

    private suspend fun runQuery(
        rt: VyzeAgentRuntime,
        query: String = "what is that",
        thermalAllowed: Boolean = true,
        answer: String? = "ok",
    ): String? = rt.tryLiveRouteTextOnly(
        textOnlyQuery = query,
        snapshotProvider = { snapshot() },
        vlmInferenceAllowed = { thermalAllowed },
        analyzeText = { _, _ -> answer },
    )

    // ── Recorder math ─────────────────────────────────────────────

    @Test
    fun `summary tallies outcomes and averages answered latency only`() {
        val rec = ShadowEvalRecorder(capacity = 10)
        rec.record(ShadowEvalRecord("q1", "s1", ShadowEvalRecord.Outcome.ANSWERED, latencyMs = 100))
        rec.record(ShadowEvalRecord("q2", "s2", ShadowEvalRecord.Outcome.ANSWERED, latencyMs = 300))
        rec.record(ShadowEvalRecord("q3", null, ShadowEvalRecord.Outcome.DECLINED))
        rec.record(ShadowEvalRecord("q4", "s4", ShadowEvalRecord.Outcome.FAILED))
        rec.record(ShadowEvalRecord("q5", null, ShadowEvalRecord.Outcome.DECLINED_BUSY))

        val s = rec.summarize()
        assertEquals(5, s.total)
        assertEquals(2, s.answered)
        assertEquals(1, s.failed)
        assertEquals(1, s.declined)
        assertEquals(1, s.declinedBusy)
        assertEquals("avg latency must ignore non-answered rows", 200L, s.avgLatencyMs)
        assertEquals(0.4, s.successRate, 1e-9)
    }

    @Test
    fun `ring keeps only the most recent capacity observations`() {
        val rec = ShadowEvalRecorder(capacity = 3)
        for (i in 1..4) {
            rec.record(ShadowEvalRecord("q$i", "s$i", ShadowEvalRecord.Outcome.ANSWERED, latencyMs = 1))
        }
        assertEquals(3, rec.summarize().total)
        val ids = rec.snapshot().map { it.query }
        assertFalse("oldest row evicted", ids.contains("q1"))
        assertTrue("newest row kept", ids.contains("q4"))
    }

    @Test
    fun `empty window summarizes to zeros`() {
        val s = ShadowEvalRecorder().summarize()
        assertEquals(0, s.total)
        assertEquals(0.0, s.successRate, 1e-9)
        assertEquals(0L, s.avgLatencyMs)
    }

    // ── Flip predicate (seeded end-to-end through the runtime) ────

    @Test
    fun `promote is false while the window is too small`() = runBlocking {
        val rt = enabledRuntime()
        repeat(5) { runQuery(rt) }
        assertFalse(rt.shouldPromoteToLive())
    }

    @Test
    fun `promote is true at the promotion bar`() = runBlocking {
        val rt = enabledRuntime()
        // 24 answered of 30 = exactly the 0.80 rate, above both minimums.
        repeat(24) { assertEquals("ok", runQuery(rt)) }
        repeat(6) { assertNull(runQuery(rt, thermalAllowed = false)) }
        assertTrue(rt.shouldPromoteToLive())
        val s = rt.evalSummary()
        assertEquals(30, s.total)
        assertEquals(24, s.answered)
        assertEquals(6, s.declined)
    }

    @Test
    fun `promote is false when the answer rate is below the bar`() = runBlocking {
        val rt = enabledRuntime()
        repeat(20) { runQuery(rt) }
        repeat(10) { runQuery(rt, thermalAllowed = false) } // declined, not failed
        val s = rt.evalSummary()
        assertEquals(30, s.total)
        assertEquals(20, s.answered)
        assertFalse("20/30 = 0.67 < 0.80", rt.shouldPromoteToLive())
    }

    @Test
    fun `promote is false when failures dominate even with enough attempts`() = runBlocking {
        val rt = enabledRuntime()
        repeat(10) { runQuery(rt, answer = null) } // executed but FAILED
        repeat(20) { runQuery(rt, thermalAllowed = false) } // declined
        val s = rt.evalSummary()
        assertEquals(30, s.total)
        assertEquals(0, s.answered)
        assertFalse(rt.shouldPromoteToLive())
    }

    @Test
    fun `declines are tallied as the baseline, never as failures`() = runBlocking {
        val rt = VyzeAgentRuntime // flag OFF → every call declines
        assertNull(runQuery(rt))
        assertNull(runQuery(rt))
        val s = rt.evalSummary()
        assertEquals(2, s.total)
        assertEquals(2, s.declined)
        assertEquals(0, s.failed)
    }

    // ── Maintenance tick ──────────────────────────────────────────

    @Test
    fun `normal turn leaves no orphans and tick deletes nothing`() = runBlocking {
        val rt = enabledRuntime()
        assertEquals("ok", runQuery(rt))
        assertEquals(0, rt.maintenanceTick { false })
    }

    @Test
    fun `tick sweeps orphaned lane sessions but keeps foreign sessions`() = runBlocking {
        val rt = enabledRuntime()
        assertEquals("ok", runQuery(rt)) // constructs the live runner
        val svc = rt.liveSessionService()!!

        // Plant an orphan in the agent lane (as a cancelled/aborted turn
        // would leave behind) and a foreign session outside the lane.
        svc.createSession(SessionKey("vyze", "vyze_user", "adk_text_999"), emptyMap())
        svc.createSession(SessionKey("vyze", "vyze_user", "vyze_foreign"), emptyMap())

        assertEquals("exactly the orphan is deleted", 1, rt.maintenanceTick { false })

        val remaining = svc.listSessions("vyze", "vyze_user")
            .sessions.mapNotNull { it.key.id }
        assertFalse("orphan swept", remaining.contains("adk_text_999"))
        assertTrue("foreign session untouched", remaining.contains("vyze_foreign"))
    }

    @Test
    fun `tick without a live runner is a no-op`() = runBlocking {
        // No query ever ran → liveSessionService() is null.
        assertEquals(0, VyzeAgentRuntime.maintenanceTick { false })
    }

    @Test
    fun `tick is clean with an open fresh episode and thermal pressure`() = runBlocking {
        val rt = enabledRuntime()
        rt.episodes().openOrTouch("adk_text_probe")
        // Fresh episode: nothing to evict even under thermal shrink.
        assertEquals(0, rt.maintenanceTick { true })
        rt.episodes().close("adk_text_probe")
    }

    @Test
    fun `tick default-arg contract holds for the fragment ticker`() = runBlocking {
        // The production ticker (CameraFragment, every 60s) calls
        // maintenanceTick() with the default lambda, letting the runtime
        // apply its NORMAL-window episode policy. Locks the signature so
        // the no-arg call site keeps compiling.
        assertEquals(0, VyzeAgentRuntime.maintenanceTick())
    }
}
