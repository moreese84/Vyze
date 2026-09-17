package com.vyze.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure decision router (single-master migration:
 * the ADK-native sub-agents were removed — execution now lives in
 * [AdkAgentManager] / [VyzeMasterAgent]).
 *
 * Scope: the PURE decision function ([VyzeShadowRouter.decide]) and the
 * [SessionEpisodeManager] lifecycle. The master-agent runner pipeline
 * belongs to instrumented tests in a later phase.
 */
class VyzeShadowRouterTest {

    private fun snapshot(
        engineReady: Boolean = true,
        isInferring: Boolean = false,
        captureAvailable: Boolean = true,
    ) = RouterSnapshot(engineReady, isInferring, captureAvailable)

    // ── Gesture routing ───────────────────────────────────────────

    @Test
    fun `single tap routes to VLM scene describe with frame`() {
        val d = VyzeShadowRouter.decide(
            RouterSignal(kind = RouterSignal.Kind.SINGLE_TAP, tapX = 100, tapY = 200),
            snapshot(),
        )
        assertEquals(RouterDecision.Action.VLM_SCENE_DESCRIBE, d.action)
        assertTrue(d.requiresVlm)
        assertTrue(d.includeCameraFrame)
    }

    @Test
    fun `long press routes to light check without VLM`() {
        val d = VyzeShadowRouter.decide(
            RouterSignal(kind = RouterSignal.Kind.LONG_PRESS),
            snapshot(),
        )
        assertEquals(RouterDecision.Action.LIGHT_CHECK, d.action)
        assertFalse(d.requiresVlm)
    }

    @Test
    fun `double tap defers to the UI voice session`() {
        val d = VyzeShadowRouter.decide(
            RouterSignal(kind = RouterSignal.Kind.DOUBLE_TAP),
            snapshot(),
        )
        assertEquals(RouterDecision.Action.IGNORE, d.action)
        assertFalse(d.requiresVlm)
    }

    // ── Speech routing ────────────────────────────────────────────

    @Test
    fun `speech with reading keyword routes to text read`() {
        val d = VyzeShadowRouter.decide(
            RouterSignal(kind = RouterSignal.Kind.SPEECH, spokenText = "read the label for me"),
            snapshot(),
        )
        assertEquals(RouterDecision.Action.VLM_TEXT_READ, d.action)
        assertTrue(d.requiresVlm)
        assertTrue(d.includeCameraFrame)
    }

    @Test
    fun `malay reading keyword routes to text read`() {
        val d = VyzeShadowRouter.decide(
            RouterSignal(kind = RouterSignal.Kind.SPEECH, spokenText = "baca teks ini"),
            snapshot(),
        )
        assertEquals(RouterDecision.Action.VLM_TEXT_READ, d.action)
    }

    @Test
    fun `chinese reading keyword routes to text read`() {
        val d = VyzeShadowRouter.decide(
            RouterSignal(kind = RouterSignal.Kind.SPEECH, spokenText = "帮我读一下这个字"),
            snapshot(),
        )
        assertEquals(RouterDecision.Action.VLM_TEXT_READ, d.action)
    }

    @Test
    fun `general speech routes to voice query`() {
        val d = VyzeShadowRouter.decide(
            RouterSignal(kind = RouterSignal.Kind.SPEECH, spokenText = "ini apa?"),
            snapshot(),
        )
        assertEquals(RouterDecision.Action.VLM_VOICE_QUERY, d.action)
        assertTrue(d.requiresVlm)
        assertTrue(d.includeCameraFrame)
    }

    @Test
    fun `empty speech is ignored`() {
        val d = VyzeShadowRouter.decide(
            RouterSignal(kind = RouterSignal.Kind.SPEECH, spokenText = "   "),
            snapshot(),
        )
        assertEquals(RouterDecision.Action.IGNORE, d.action)
        assertFalse(d.requiresVlm)
    }

    @Test
    fun `speech is case-insensitive on reading keywords`() {
        val d = VyzeShadowRouter.decide(
            RouterSignal(kind = RouterSignal.Kind.SPEECH, spokenText = "READ THIS LABEL"),
            snapshot(),
        )
        assertEquals(RouterDecision.Action.VLM_TEXT_READ, d.action)
    }

    // ── Snapshot influence ────────────────────────────────────────

    @Test
    fun `engine-not-ready annotates the reason but keeps the legacy action`() {
        val d = VyzeShadowRouter.decide(
            RouterSignal(kind = RouterSignal.Kind.SPEECH, spokenText = "what is this"),
            snapshot(engineReady = false),
        )
        assertEquals(RouterDecision.Action.VLM_VOICE_QUERY, d.action)
        assertTrue(d.reason.contains("engine NOT ready"))
    }

    @Test
    fun `decide is pure - same input same output`() {
        val signal = RouterSignal(kind = RouterSignal.Kind.SPEECH, spokenText = "apa ini")
        val s = snapshot()
        assertEquals(VyzeShadowRouter.decide(signal, s), VyzeShadowRouter.decide(signal, s))
    }

    // ── SessionEpisodeManager (Section 6 thermal design) ─────────

    @Test
    fun `episode openOrTouch refreshes lastUsedAt`() {
        var now = 1_000L
        val m = SessionEpisodeManager(nowMs = { now })
        m.openOrTouch("s1")
        now = 60_000L
        m.openOrTouch("s1")
        // 60s idle but touched at 60s → 0s idle → survives the 90s thermal window
        val evicted = m.evictIdle(isThermallyConstrained = true)
        assertTrue(evicted.isEmpty())
        assertEquals(1, m.size())
    }

    @Test
    fun `idle episodes evicted after the normal window`() {
        var now = 1_000L
        val m = SessionEpisodeManager(nowMs = { now }, idleEvictMs = 300_000L)
        m.openOrTouch("s1")
        now = 1_000L + 301_000L
        val evicted = m.evictIdle(isThermallyConstrained = false)
        assertEquals(listOf("s1"), evicted)
        assertEquals(0, m.size())
    }

    @Test
    fun `thermal pressure shrinks the eviction window`() {
        var now = 1_000L
        val m = SessionEpisodeManager(nowMs = { now }, thermalEvictMs = 90_000L)
        m.openOrTouch("s1")
        now = 1_000L + 100_000L // 100s idle: survives 5min normal, dies under thermal
        assertTrue(m.evictIdle(isThermallyConstrained = false).isEmpty())
        m.openOrTouch("s1") // re-open (fresh lastUsedAt)
        now += 100_000L
        val evicted = m.evictIdle(isThermallyConstrained = true)
        assertTrue(evicted.contains("s1"))
    }

    @Test
    fun `close removes the episode immediately`() {
        val m = SessionEpisodeManager(nowMs = { 0L })
        m.openOrTouch("s1")
        m.close("s1")
        assertEquals(0, m.size())
    }
}
