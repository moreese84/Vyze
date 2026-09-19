package com.vyze.app.agent.student

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Phase 2→3 GATE, frozen as a test: the distilled [StudentRouter] must
 * beat the regex baseline on the Phase 0 Jev-labeled corpus, overall AND
 * per language, with the Jev teacher's own accuracy pinned as reference.
 *
 * Fixture: 52 seed queries (en/ms/zh) labeled live by Jev
 * (tools/jev_harness), frozen at
 * app/src/test/resources/fixtures/phase0_route_labels.jsonl and projected
 * into [StudentRouterFixture]. Regenerate the Kotlin projection with
 * `python tools/jev_harness/gen_kotlin_fixture.py` after a re-label —
 * never hand-edit it.
 */
class StudentRouterFixtureTest {

    private val rows = StudentRouterFixture.ROWS

    private fun studentAction(query: String): String =
        StudentRouter.decide(query).action.name

    @Test
    fun `fixture integrity - all rows labeled with known actions`() {
        assertTrue("fixture must not be empty", rows.isNotEmpty())
        for (r in rows) {
            assertTrue("row ${r.id}: expected action unknown", r.expected in StudentRouterFixture.GATE_ACTIONS)
            assertTrue("row ${r.id}: regex action unknown", r.regexAction in StudentRouterFixture.GATE_ACTIONS)
        }
    }

    @Test
    fun `GATE - student beats the regex baseline overall`() {
        val studentHits = rows.count { studentAction(it.query) == it.expected }
        val regexHits = rows.count { it.regexAction == it.expected }
        assertTrue(
            "Student ($studentHits/${rows.size}) must beat regex ($regexHits/${rows.size})",
            studentHits >= regexHits,
        )
        val rate = studentHits.toDouble() / rows.size
        assertTrue(
            "Student accuracy ${(rate * 100).toInt()}% below the 80% absolute bar",
            rate >= 0.80,
        )
    }

    @Test
    fun `GATE - student never loses to regex within any language`() {
        for (lang in rows.map { it.lang }.distinct()) {
            val sub = rows.filter { it.lang == lang }
            val studentHits = sub.count { studentAction(it.query) == it.expected }
            val regexHits = sub.count { it.regexAction == it.expected }
            assertTrue(
                "[$lang] student ($studentHits/${sub.size}) must not lose to regex ($regexHits/${sub.size})",
                studentHits >= regexHits,
            )
        }
    }

    @Test
    fun `GATE - Jev teacher reference stays above the regex baseline`() {
        // The Phase 2 gate that justified distillation, pinned so a future
        // re-label that degrades the teacher below the baseline surfaces
        // loudly (it would mean the student has nothing left to learn).
        val jevHits = rows.count { it.jevAction == it.expected }
        val regexHits = rows.count { it.regexAction == it.expected }
        assertTrue(
            "Jev teacher ($jevHits/${rows.size}) fell to regex level ($regexHits/${rows.size}) — re-distill",
            jevHits >= regexHits,
        )
    }

    @Test
    fun `distilled signal - student recovers the regex-wrong Jev-right rows`() {
        // The rows that motivated the student in the first place: where the
        // regex was wrong and Jev was right, the student must now agree
        // with Jev's label far more often than the regex did.
        val signal = rows.filter { it.jevAction == it.expected && it.regexAction != it.expected }
        assertTrue("fixture lost its training signal rows", signal.isNotEmpty())
        val studentRecovered = signal.count { studentAction(it.query) == it.expected }
        val regexRecovered = signal.count { it.regexAction == it.expected }
        assertTrue(
            "Student recovered $studentRecovered/${signal.size} signal rows (regex: $regexRecovered) — must dominate",
            studentRecovered > regexRecovered,
        )
    }
}
