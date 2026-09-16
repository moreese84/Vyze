package com.vyze.app.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the Phase 1 tool wiring contracts.
 *
 * Scope: delegation semantics of [FastPerceptionToolContracts] /
 * [VlmReasoningToolContracts] and sector-mapper parity with the native
 * gridSectorFor logic. Android-context wiring (VyzeToolWiring.fromSingletons)
 * is intentionally NOT covered here — it touches TTSManager / ML Kit and
 * belongs to instrumented tests in a later phase.
 */
class VyzeToolWiringTest {

    private fun contracts(
        ocr: suspend (Boolean) -> String? = { null },
        grid: (Int, Int, Int, Int) -> String = { _, _, _, _ -> "Middle-Center" },
        speak: (String) -> Boolean = { true },
        haptic: (String) -> Unit = {},
    ) = FastPerceptionToolContracts(ocr, grid, speak, haptic)

    // ── runFastOcr ────────────────────────────────────────────────

    @Test
    fun `runFastOcr coalesces null frame text to empty string`() = runBlocking {
        var receivedInclude: Boolean? = null
        val c = contracts(ocr = { include ->
            receivedInclude = include
            null
        })
        val out = c.runFastOcr(includeChinese = true)
        assertEquals(true, receivedInclude)
        assertEquals("", out["text"])
    }

    @Test
    fun `runFastOcr returns recognized text`() = runBlocking {
        val c = contracts(ocr = { "RM12.90 total" })
        val out = c.runFastOcr(includeChinese = false)
        assertEquals("RM12.90 total", out["text"])
    }

    // ── calculateSpatialGrid ──────────────────────────────────────

    @Test
    fun `calculateSpatialGrid passes tap and frame dimensions through`() {
        var captured: List<Int> = emptyList()
        val c = contracts(grid = { x, y, w, h ->
            captured = listOf(x, y, w, h)
            "Top-Left"
        })
        val out = c.calculateSpatialGrid(x = 120, y = 400, frameW = 960, frameH = 1280)
        assertEquals(listOf(120, 400, 960, 1280), captured)
        assertEquals("Top-Left", out["sector"])
    }

    // ── speakImmediateTts / triggerHaptics ────────────────────────

    @Test
    fun `speakImmediateTts reports queued flag from delegate`() {
        val spoken = mutableListOf<String>()
        val c = contracts(speak = { text ->
            spoken.add(text)
            text.isNotBlank()
        })
        assertEquals(mapOf("queued" to "true"), c.speakImmediateTts("Reading label"))
        assertEquals(mapOf("queued" to "false"), c.speakImmediateTts(""))
        assertEquals(listOf("Reading label", ""), spoken)
    }

    @Test
    fun `triggerHaptics uppercases pattern name for delegate`() {
        var received: String? = null
        val c = contracts(haptic = { received = it })
        val out = c.triggerHaptics("tap")
        assertEquals("TAP", received)
        assertEquals(mapOf("triggered" to "TAP"), out)
    }

    // ── defaultGridSectorMapper parity with native semantics ──────

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

    // ── generated ADK tool descriptors ────────────────────────────

    @Test
    fun `generated tools cover all four fast-path functions`() {
        val tools = contracts().generatedTools()
        assertEquals(4, tools.size)
        val names = tools.mapNotNull { it.declaration()?.name }.toSet()
        assertEquals(
            setOf("runFastOcr", "calculateSpatialGrid", "speakImmediateTts", "triggerHaptics"),
            names,
        )
    }

    @Test
    fun `generated VLM tool is exposed`() {
        val tools = VyzeToolWiring.vlmContracts { "ok" }.generatedTools()
        assertEquals(1, tools.size)
        assertEquals("runVlmQuery", tools.single().declaration()?.name)
    }

    // ── VLM delegate wiring ───────────────────────────────────────

    @Test
    fun `vlmContracts delegates prompt verbatim and wraps answer`() = runBlocking {
        var captured: String? = null
        val v = VyzeToolWiring.vlmContracts { prompt ->
            captured = prompt
            "answer-42"
        }
        val out = v.runVlmQuery("what is this?")
        assertEquals("what is this?", captured)
        assertEquals(mapOf("answer" to "answer-42"), out)
        assertTrue(captured != null)
    }
}
