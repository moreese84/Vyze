package com.vyze.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [InteractionLogRow] — the pure mapping core of the
 * debug-only interaction export (Phase 1). No Android dependencies.
 */
class InteractionLogRowTest {

    // ── extractQuery: built VLM prompt → raw spoken query ─────────

    @Test
    fun `extractQuery pulls the raw query from the marker line`() {
        val built = buildString {
            append("[OUTPUT LANGUAGE: en]\n")
            append("...rules...\n")
            append("Task: The user asked: What color is this shirt?\n")
            append("Respond with the answer only.\n")
        }
        assertEquals("What color is this shirt?", InteractionLogRow.extractQuery(built))
    }

    @Test
    fun `extractQuery stops at the end of the marker line`() {
        val built = "prefix\nTask: The user asked: apa kat depan saya?\nmore lines"
        assertEquals("apa kat depan saya?", InteractionLogRow.extractQuery(built))
    }

    @Test
    fun `extractQuery handles a marker at end of prompt`() {
        val built = "rules\nTask: The user asked: 我面前是什么？"
        assertEquals("我面前是什么？", InteractionLogRow.extractQuery(built))
    }

    @Test
    fun `extractQuery returns null when the marker is absent`() {
        assertNull(InteractionLogRow.extractQuery("User triggered a camera snapshot."))
        assertNull(InteractionLogRow.extractQuery(""))
    }

    @Test
    fun `extractQuery returns null for a blank query`() {
        assertNull(InteractionLogRow.extractQuery("Task: The user asked:   \nnext"))
    }

    // ── toJson: stable, escaped, harness-compatible output ────────

    @Test
    fun `toJson emits harness-aligned fields in order`() {
        val row = InteractionLogRow(
            id = "ir_42",
            lang = "unknown",
            query = "What about this?",
            answer = "That is a pair of reading glasses.",
            previous = "What is in front of me?",
            ts = 1_700_000_000_000,
            source = "interaction_records",
        )
        val json = row.toJson()
        assertTrue(json.startsWith("{\"id\": \"ir_42\", \"lang\": \"unknown\", "))
        assertTrue(json.contains("\"query\": \"What about this?\""))
        assertTrue(json.contains("\"previous\": \"What is in front of me?\""))
        assertTrue(json.contains("\"answer\": \"That is a pair of reading glasses.\""))
        assertTrue(json.contains("\"ts\": 1700000000000"))
        assertTrue(json.endsWith(", \"source\": \"interaction_records\", \"lane\": \"voice\"}"))
    }

    @Test
    fun `toJson escapes quotes backslashes and newlines`() {
        val row = InteractionLogRow(
            id = "vm_1",
            lang = "unknown",
            query = "said \"hi\" \\ ok",
            answer = "line1\nline2\ttabbed",
            previous = null,
            ts = 5L,
            source = "vyze_memory",
        )
        val json = row.toJson()
        assertTrue(json.contains("\"query\": \"said \\\"hi\\\" \\\\ ok\""))
        assertTrue(json.contains("\"answer\": \"line1\\nline2\\ttabbed\""))
        assertTrue(json.contains("\"previous\": null"))
    }

    @Test
    fun `toJson emits null for a missing previous query`() {
        val row = InteractionLogRow(
            id = "ir_1", lang = "unknown", query = "q", answer = "a",
            previous = null, ts = 0L, source = "interaction_records",
        )
        assertTrue(row.toJson().contains("\"previous\": null"))
    }

    @Test
    fun `toJson keeps CJK text intact`() {
        val row = InteractionLogRow(
            id = "vm_9", lang = "unknown", query = "那这个呢？", answer = "那是一副眼镜。",
            previous = "我面前是什么？", ts = 9L, source = "vyze_memory",
        )
        val json = row.toJson()
        assertTrue(json.contains("\"query\": \"那这个呢？\""))
        assertTrue(json.contains("\"previous\": \"我面前是什么？\""))
    }

    // ── laneOf: voice vs tap classification (v2) ──────────────────

    @Test
    fun `laneOf classifies tap queries by the CameraFragment prefix`() {
        assertEquals(
            InteractionLogRow.LANE_TAP,
            InteractionLogRow.laneOf("User tapped at position (679, 1487)"),
        )
        assertEquals(InteractionLogRow.LANE_VOICE, InteractionLogRow.laneOf("what about this"))
        assertEquals(InteractionLogRow.LANE_VOICE, InteractionLogRow.laneOf("apa kat depan saya?"))
        assertEquals(InteractionLogRow.LANE_VOICE, InteractionLogRow.laneOf(""))
    }

    // ── inferLanguage: pure language detection (v2) ───────────────

    @Test
    fun `inferLanguage recovers the garbled Malay query from the device audit`() {
        // THE v2 regression: ir_6. "inipula appa" (Ini pula apa?) scored
        // zero under v1 detection → answered in English. The alias (appa)
        // plus fused-word evidence (inipula ⊃ pula/ini) must now reach ms.
        assertEquals("ms", InteractionLogRow.inferLanguage("inipula appa"))
    }

    @Test
    fun `inferLanguage detects clean Malay queries`() {
        assertEquals("ms", InteractionLogRow.inferLanguage("apa kat depan saya?"))
        assertEquals("ms", InteractionLogRow.inferLanguage("APA Ini"))
        assertEquals("ms", InteractionLogRow.inferLanguage("tolong baca label"))
        assertEquals("ms", InteractionLogRow.inferLanguage("apa ini pula"))
    }

    @Test
    fun `inferLanguage detects English and Chinese`() {
        assertEquals("en", InteractionLogRow.inferLanguage("What is in front of me?"))
        assertEquals("en", InteractionLogRow.inferLanguage("what about this"))
        assertEquals("zh", InteractionLogRow.inferLanguage("我面前是什么？"))
        assertEquals("zh", InteractionLogRow.inferLanguage("那这个呢"))
    }

    @Test
    fun `inferLanguage returns unknown for uninterpretable or non-linguistic text`() {
        assertEquals("unknown", InteractionLogRow.inferLanguage(""))
        assertEquals("unknown", InteractionLogRow.inferLanguage("   "))
        assertEquals("unknown", InteractionLogRow.inferLanguage("123 456"))
    }

    @Test
    fun `inferLanguage keeps plain English below the Malay threshold`() {
        // English words must not trip Malay signals (no 'sama'-style
        // single-hit false positives on everyday English queries).
        assertEquals("en", InteractionLogRow.inferLanguage("is that a refrigerator"))
        assertEquals("en", InteractionLogRow.inferLanguage("read the label please"))
    }
}
