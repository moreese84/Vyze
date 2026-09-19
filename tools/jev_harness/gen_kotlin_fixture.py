# ruff: noqa: E501
"""Generate the JVM test fixture for the student-router gate test.

Reads the frozen Phase 0 labeling output (route_labels.jsonl) and emits
app/src/test/java/com/vyze/app/agent/student/StudentRouterFixture.kt so
JVM unit tests can evaluate StudentRouter against the frozen Jev labels
without needing a JSON parser on the test classpath.

Usage (from repo root, after a live `python -m tools.jev_harness route --live`):

    python tools/jev_harness/gen_kotlin_fixture.py [path/to/route_labels.jsonl]

The committed JSONL (app/src/test/resources/fixtures/phase0_route_labels.jsonl,
also at docs/eval/) stays the canonical frozen artifact; this file is a
mechanical projection of it. Regenerate, never hand-edit.
"""

import json
import sys
from pathlib import Path

DEFAULT_SOURCE = "app/src/test/resources/fixtures/phase0_route_labels.jsonl"
TARGET = Path("app/src/test/java/com/vyze/app/agent/student/StudentRouterFixture.kt")

HEADER = """\
// GENERATED from app/src/test/resources/fixtures/phase0_route_labels.jsonl
// (Phase 0 Jev labeling of the seed corpus — frozen gate fixture).
// Regenerate with: python tools/jev_harness/gen_kotlin_fixture.py
// Do not hand-edit.
package com.vyze.app.agent.student

/**
 * Frozen Phase 0 route labels: regex baseline, Jev (teacher), and the
 * hand-assigned expected action per seed query. Consumed by
 * StudentRouterFixtureTest to prove the student router meets the
 * Phase 2→3 gate (student ≥ regex baseline on this corpus).
 */
object StudentRouterFixture {

    data class Row(
        val id: String,
        val lang: String,
        val query: String,
        val previous: String?,
        val expected: String,
        val regexAction: String,
        val jevAction: String,
        val jevConfidence: Double,
        val jevFastPathNoul: Double,
    )

    val ROWS: List<Row> = listOf(
"""

FOOTER = """\
    )

    /** Actions whose speech-routing correctness the gate measures. */
    val GATE_ACTIONS = setOf(
        "VLM_SCENE_DESCRIBE", "VLM_TEXT_READ", "VLM_VOICE_QUERY",
        "FAST_COLOR_ANALYSIS", "LIGHT_CHECK", "SOS", "IGNORE",
    )
}
"""


def kstr(value: str) -> str:
    """Kotlin string literal with escapes for quotes/backslashes."""
    escaped = (
        value.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("$", "\\$")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
    )
    return f'"{escaped}"'


def knullable_str(value: str | None) -> str:
    return kstr(value) if value is not None else "null"


def main() -> int:
    source = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SOURCE
    rows = []
    with open(source, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            r = json.loads(line)
            if r.get("error") is not None:
                raise SystemExit(
                    f"Fixture source has error rows ({r.get('id')}: {r['error']}) — "
                    "freeze a clean run: every row must be live-labeled."
                )
            expected = r.get("expected")
            if not expected:
                raise SystemExit(f"Row {r.get('id')} has no expected label.")
            rows.append(r)
    if not rows:
        raise SystemExit(f"No rows found in {source}.")

    body = []
    for r in rows:
        body.append(
            "        Row(\n"
            f"            id = {kstr(r['id'])},\n"
            f"            lang = {kstr(r.get('lang') or '')},\n"
            f"            query = {kstr(r['query'])},\n"
            f"            previous = {knullable_str(r.get('previous'))},\n"
            f"            expected = {kstr(r['expected'])},\n"
            f"            regexAction = {kstr(r['regex_action'])},\n"
            f"            jevAction = {kstr(r['jev_action'])},\n"
            f"            jevConfidence = {r['jev_confidence']!r},\n"
            f"            jevFastPathNoul = {r['jev_fast_path_noul']!r},\n"
            "        ),"
        )

    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(HEADER + "\n".join(body) + "\n" + FOOTER, encoding="utf-8")
    print(f"Wrote {len(rows)} rows -> {TARGET}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
