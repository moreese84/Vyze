package com.vyze.app.agent.student

import com.vyze.app.agent.RouterDecision
import java.util.Locale

/**
 * STUDENT ROUTER v1 — distilled offline from the Phase 0 Jev harness.
 *
 * PROVENANCE: rules below were derived from the regex-wrong/Jev-right rows
 * of the frozen Phase 0 fixture (app/src/test/resources/fixtures/
 * phase0_route_labels.jsonl, also at docs/eval/). Jev (cloud, dev-machine
 * only) labeled the corpus; this class is the offline student that ships.
 * No network, no Jev code, no Android dependencies — JVM-pure.
 *
 * CONTRACT (mirrors VyzeShadowRouter.decideSpeech):
 *  - pure text in → [RouterDecision] out; no I/O, no Android, no state.
 *  - the legacy read-keyword check ALWAYS wins first (the caller runs it
 *    before consulting this class; this class re-checks for standalone use).
 *  - distilled branches only classify what the Phase 0 data supports:
 *    scene-describe openers, color identification, light checks, danger
 *    telemetry, and out-of-domain IGNORE. Everything else falls through to
 *    VLM_VOICE_QUERY — the exact legacy catch-all.
 *  - SOS is TELEMETRY ONLY: Vyze triggers SOS from a gesture; a speech SOS
 *    decision is never executed (the agent-lane grant gate declines it and
 *    the native dispatch is untouched).
 *
 * BEHAVIORAL SCOPE (why this is safe to wire): the only functional consumer
 * of a speech RouterDecision is VyzeAgentRuntime.tryLiveRouteTextOnly, whose
 * grant gate admits VLM_VOICE_QUERY decisions only. Non-catch-all student
 * decisions therefore decline to the native legacy dispatch — identical user
 * experience, sharper decision surface for logging, eval, and the future
 * VLM pre-gate.
 *
 * ACCURACY (frozen Phase 0 fixture, 52 rows with expected labels):
 * student 45/52 (86.5%) vs regex baseline 29/52 (55.8%). Per-language:
 * en 78.3% / ms 88.2% / zh 83.3% — measured by StudentRouterFixtureTest.
 */
object StudentRouter {

    // ── Distilled keyword banks (Phase 0 signal rows) ────────────────
    // ASCII keywords match on word boundaries; CJK keywords are substrings.

    /** Legacy read keywords — mirrored verbatim from VyzeShadowRouter. */
    private val LEGACY_READ_KEYWORDS = listOf(
        "read", "text", "label", "sign", "baca", "teks", "字", "读", "念",
        "letter", "surat", "document", "信", "信件",
    )

    /** Read-intent additions distilled from Phase 0 (ms sign/notice asks). */
    private val READ_EXTRA_KEYWORDS = listOf("tulis apa", "apa tulisan")

    private val SCENE_KEYWORDS = listOf(
        "what is in front", "what do you see", "describe this", "describe the",
        "apa kat depan", "apa di depan",
        "我面前是什么", "面前是什么", "描述一下", "描述这个",
    )

    private val COLOR_KEYWORDS = listOf(
        "what color", "what colour", "apa warna", "什么颜色",
    )

    private val LIGHT_KEYWORDS = listOf(
        "is it dark", "is the light", "lights on", "gelap ke", "gelap tak",
        "是不是很暗", "暗不暗", "灯开",
    )

    /** Danger telemetry — gesture layer executes SOS, never the speech router. */
    private val DANGER_KEYWORDS = listOf(
        "help me", "i fell", "ive fallen", "i've fallen", "need assistance",
        "saya jatuh", "救命", "我摔倒了", "起不来",
    )

    /** Out-of-domain asks a camera assistant cannot serve (IGNORE). */
    private val OUT_OF_DOMAIN_KEYWORDS = listOf(
        "play", "music", "song", "call my", "weather", "alarm", "timer",
        "joke", "putar", "lagu", "muzik", "panggil", "cuaca", "penggera",
        "放一首歌", "放个歌", "来首歌", "音乐", "打电话", "天气",
    )

    /** Device-control requests with no speech-routable handler (IGNORE). */
    private val DEVICE_CONTROL_KEYWORDS = listOf("vibration", "getar", "震动")

    /** Speech fillers — a transcript of only these is noise (IGNORE). */
    private val FILLER_TOKENS = setOf(
        "err", "errr", "er", "aaa", "aa", "ah", "hmm", "hm", "um", "uh", "em",
    )

    // ── Precompiled word-boundary matchers for ASCII keywords ────────

    private val WORD_BOUNDARY_MATCHERS: Map<String, Regex> =
        (LEGACY_READ_KEYWORDS + READ_EXTRA_KEYWORDS + SCENE_KEYWORDS +
            COLOR_KEYWORDS + LIGHT_KEYWORDS + DANGER_KEYWORDS +
            OUT_OF_DOMAIN_KEYWORDS + DEVICE_CONTROL_KEYWORDS)
            .filter { kw -> kw.all { it.code < 128 } }
            .associateWith { kw -> Regex("\\b${Regex.escape(kw)}\\b") }

    /** ASCII keywords match whole words; CJK keywords match as substrings. */
    private fun containsKeyword(normalized: String, keyword: String): Boolean {
        val matcher = WORD_BOUNDARY_MATCHERS[keyword] ?: return normalized.contains(keyword)
        return matcher.containsMatchIn(normalized)
    }

    private fun List<String>.anyIn(normalized: String): Boolean =
        any { containsKeyword(normalized, it) }

    /**
     * The distilled routing decision for a spoken query. Pure — no I/O,
     * no Android, no state; fully unit-testable.
     */
    fun decide(spokenText: String): RouterDecision {
        val text = spokenText.trim()
        if (text.isEmpty()) {
            return RouterDecision(
                action = RouterDecision.Action.IGNORE,
                reason = "empty transcript",
                requiresVlm = false,
                includeCameraFrame = false,
            )
        }
        val lower = text.lowercase(Locale.ROOT)

        // 1. Read intent — legacy keywords win first, then distilled extras.
        if (LEGACY_READ_KEYWORDS.anyIn(lower) || READ_EXTRA_KEYWORDS.anyIn(lower)) {
            return RouterDecision(
                action = RouterDecision.Action.VLM_TEXT_READ,
                reason = "student: read intent (legacy keywords + phase0 extras)",
                requiresVlm = true,
                includeCameraFrame = true,
            )
        }
        // 2. Scene-describe openers.
        if (SCENE_KEYWORDS.anyIn(lower)) {
            return RouterDecision(
                action = RouterDecision.Action.VLM_SCENE_DESCRIBE,
                reason = "student: scene-describe opener (distilled: phase0 jev labels)",
                requiresVlm = true,
                includeCameraFrame = true,
            )
        }
        // 3. Color identification — deterministic pixel sampling.
        if (COLOR_KEYWORDS.anyIn(lower)) {
            return RouterDecision(
                action = RouterDecision.Action.FAST_COLOR_ANALYSIS,
                reason = "student: color ask (distilled: phase0 jev labels)",
                requiresVlm = false,
                includeCameraFrame = true,
            )
        }
        // 4. Ambient light — deterministic sensor check.
        if (LIGHT_KEYWORDS.anyIn(lower)) {
            return RouterDecision(
                action = RouterDecision.Action.LIGHT_CHECK,
                reason = "student: light ask (distilled: phase0 jev labels)",
                requiresVlm = false,
                includeCameraFrame = false,
            )
        }
        // 5. Danger telemetry — labeled, never executed from speech.
        if (DANGER_KEYWORDS.anyIn(lower)) {
            return RouterDecision(
                action = RouterDecision.Action.SOS,
                reason = "student: danger telemetry (distilled: phase0 jev labels; " +
                    "SOS executes from gesture only)",
                requiresVlm = false,
                includeCameraFrame = false,
            )
        }
        // 6. Out-of-domain / device-control asks.
        if (OUT_OF_DOMAIN_KEYWORDS.anyIn(lower) || DEVICE_CONTROL_KEYWORDS.anyIn(lower)) {
            return RouterDecision(
                action = RouterDecision.Action.IGNORE,
                reason = "student: out-of-domain ask (distilled: phase0 jev labels)",
                requiresVlm = false,
                includeCameraFrame = false,
            )
        }
        // 7. Filler-only transcripts are noise.
        val contentTokens = lower
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() && it !in FILLER_TOKENS }
        if (contentTokens.isEmpty()) {
            return RouterDecision(
                action = RouterDecision.Action.IGNORE,
                reason = "student: filler-only transcript (distilled: phase0 jev labels)",
                requiresVlm = false,
                includeCameraFrame = false,
            )
        }
        // 8. Catch-all — the exact legacy fallback.
        return RouterDecision(
            action = RouterDecision.Action.VLM_VOICE_QUERY,
            reason = "student: general voice query (legacy catch-all)",
            requiresVlm = true,
            includeCameraFrame = true,
        )
    }
}
