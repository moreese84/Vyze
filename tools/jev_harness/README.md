# Vyze × TypeSafe Jev harness (Phase 0)

**Jev is cloud-only and runs on the dev machine. Nothing in this folder ever
ships in the APK** — the release binary stays 100% offline, with no INTERNET
permission and no network code. The harness exists to (a) audit the prompt
pipeline and (b) produce the labeled corpus from which the offline **student
router** (Phase 3) is distilled.

## Architecture in one line

```
dev machine, online:   corpus ──► Jev API ──► labels + audit reports
                                              │
phone, offline forever:        student rules (distilled in Phase 3) ◄┘
```

## Setup (dev machine only)

```sh
pip install typesafe-sdk          # API key: https://console.typesafe.ai/
export TYPESAFE_API_KEY=...       # never commit this
```

Dry-run needs neither the SDK nor a key.

## Usage

```sh
# 1. Smoke-test the whole pipeline offline (no key needed)
python -m tools.jev_harness route                      # built-in seed corpus
python -m tools.jev_harness audit --corpus transcripts.jsonl

# 2. Live labeling against the seed corpus
python -m tools.jev_harness route --live

# 3. Live labeling over a bigger corpus file (.py / .json / .jsonl)
python -m tools.jev_harness route --corpus corpus/device_queries.jsonl --live \
    --out out/route_labels.jsonl

# 4. Audit recorded (query, answer) transcripts — the echo / mirror reports
#    (rows need at least: query, answer; optional: id, lang, previous)
python -m tools.jev_harness audit --corpus corpus/transcripts.jsonl --live
```

## What it measures

**Routing pass (`route`)** — per query, one batched Jev request:
- `route_action` — `Choice` over the real `RouterDecision.Action` taxonomy
  (+ explicit `none_of_these`), with per-option probabilities and confidence
- `is_fast_path` — `Noul`: could this skip the VLM wake?

It compares **Jev vs regex baseline vs expected** overall and per language,
lists **regex-wrong/Jev-right rows** (the student's training signal), and
shows the confidence distribution so thresholds are chosen from *our* data.

**Audit pass (`audit`)** — per (query, answer) transcript, one batched request:
- `echoed_question` — `Noul` (the echo-bug regression metric)
- `language_matches_query` — `Noul` (en/ms/zh mirroring compliance)
- `relevance` — `Score` (0–2, averaged)

## Corpus conventions

Rows are dicts: `id`, `lang` (`en|ms|zh`), `query`, `expected`
(action name or null), optional `previous` (follow-up context), optional
`answer` (audit rows only), optional `note`. See `queries.py` for the seed
set, including follow-up echo traps like "what about this?" / "bagaimana
dengan ini?" / "那这个呢？".

Two corpus kinds:
1. **Seed corpus** (`queries.py`, committed): hand-written, balanced, safe
   to commit.
2. **Device corpus** (`corpus/`, git-ignored): real dogfooding transcripts
   exported from the `interaction_records` table (Phase 1's debug-only
   JSONL export). **Never commit real usage transcripts** — they can
   contain health/banking context.

## Known limits (documented, not discovered-the-hard-way)

- **Jev's primary language is English.** Per TypeSafe's docs, other
  languages including CJK have *lower accuracy* today. The per-language
  breakdown in the routing report exists precisely to measure that gap
  before any student rule is distilled from ms/zh labels.
- Confidence ≠ permission to act: thresholds in this harness are run
  parameters validated on our data, never universal rules.
- `SOS` from speech is telemetry only — Vyze triggers SOS by gesture; the
  router never executes it.

## Gate discipline (from the comprehensive plan)

- **Phase 2→3 gate**: Jev routing agreement ≥ regex baseline on the same
  corpus. If Jev can't beat the regex on *our* data, the student has
  nothing to learn — stop, keep the regex.
- **Phase 3→4 gate**: distilled student ≥ regex baseline on a frozen
  `labels.jsonl` snapshot, with per-language coverage intact.

## Migration note

The retired `eval_vyze_router.py` (repo root) used the pre-v1
`pydantic_ai` integration, a wrong tool enum, and free-text confidence —
superseded by this harness. Safe to delete.
