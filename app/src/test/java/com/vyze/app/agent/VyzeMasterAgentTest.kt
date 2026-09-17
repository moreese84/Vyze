package com.vyze.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the SINGLE MASTER ORCHESTRATOR (migration plan v3):
 * the unified tool registry ([VyzeMasterAgent] native tools), the sector
 * mapper parity with the native gridSectorFor logic, and the
 * [MasterModelAdapter] prompt folding. Android-context wiring
 * (VyzeToolWiring.masterToolHooks) is intentionally NOT covered here — it
 * touches TTSManager / ML Kit and belongs to instrumented tests.
 */
class VyzeMasterAgentTest {

    // ── Unified registry: all native tools bound to ONE agent ─────

    @Test
    fun `master agent registers all native tools in one array`() {
        val agent = VyzeMasterAgent.create(
            bridgeProvider = { MasterModelBridge({ _, _ -> null }, { _, _, _ -> null }) },
            frameProvider = { null },
            ocrFrame = { null },
            speakImmediate = { false },
            hapticPattern = {},
        )
        val names = agent.tools.map { it.name }.toSet()
        assertEquals(
            setOf(
                "read_scene_text",
                "get_camera_frame",
                "speak_immediate_tts",
                "trigger_haptics",
                "find_spatial_sector",
            ),
            names,
        )
        assertEquals("vyze_master", agent.name)
    }

    // ── Sector mapper (SpatialSectoringEngine parity) ─────────────

    @Test
    fun `defaultGridSectorMapper produces all nine sectors`() {
        val m = VyzeToolWiring::defaultGridSectorMapper
        assertEquals("Top-Left", m(0, 0, 300, 300))
        assertEquals("Top-Center", m(100, 0, 300, 300))
        assertEquals("Top-Right", m(299, 0, 300, 300))
        assertEquals("Middle-Left", m(0, 150, 300, 300))
        assertEquals("Middle-Center", m(150, 150, 300, 300))
        assertEquals("Middle-Right", m(200, 150, 300, 300))
        assertEquals("Bottom-Left", m(0, 299, 300, 300))
        assertEquals("Bottom-Center", m(150, 250, 300, 300))
        assertEquals("Bottom-Right", m(299, 299, 300, 300))
    }

    @Test
    fun `defaultGridSectorMapper boundary semantics match native third rules`() {
        val m = VyzeToolWiring::defaultGridSectorMapper
        // x == frameW/3 is Center (x < frameW/3 is false at exactly one third)
        assertEquals("Top-Left", m(99, 0, 300, 300))
        assertEquals("Top-Center", m(100, 0, 300, 300))
        // y == 2*frameH/3 is Bottom
        assertEquals("Middle-Left", m(0, 199, 300, 300))
        assertEquals("Bottom-Left", m(0, 200, 300, 300))
    }

    // ── One-pass instruction contract ─────────────────────────────

    @Test
    fun `master instruction mandates one-pass native dispatch and no sub-agents`() {
        val i = VyzeMasterAgent.MASTER_INSTRUCTION
        assertTrue(i.contains("read_scene_text"))
        assertTrue(i.contains("trigger_haptics"))
        assertTrue(i.contains("find_spatial_sector"))
        assertTrue("no sub-agents", i.contains("no sub-agents"))
        assertTrue("one pass", i.contains("SAME turn") || i.contains("SAME pass"))
    }

    // ── Prompt folding (MasterModelAdapter semantics, pure part) ──

    @Test
    fun `query context mirrors native persona and style directives`() {
        val ctx = VyzeQueryContext()
        val p = ctx.instructionFor("what is paracetamol used for?")
        assertTrue(p.contains(VyzeQueryContext.DEFAULT_PERSONA_DIRECTIVE))
        assertTrue(p.contains(VyzeQueryContext.DEFAULT_ANSWER_STYLE_DIRECTIVE))
        assertTrue(p.endsWith("User question: what is paracetamol used for?"))
        assertEquals("adk_master", ctx.asSessionState()["lane"])
    }

    // ── Bridge ────────────────────────────────────────────────────

    @Test
    fun `engine bridge forwards text op`() = runBlocking {
        var textPrompt: String? = null
        val bridge = VyzeToolWiring.engineBridge(
            analyzeText = { prompt, _ -> textPrompt = prompt; "text-answer" },
            analyzeImage = { _, _, _ -> "image-answer" },
        )
        assertEquals("text-answer", bridge.generateText("q", "s1"))
        assertEquals("q", textPrompt)
    }
}
