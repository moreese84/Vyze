# ruff: noqa: E501
"""Routing judgment: which RouterDecision.Action handles a spoken query?

One `system_one` call per query carries BOTH questions (they are
independent judgments over the same state, so they batch in parallel —
per SKILL.md, batching independent questions is cheaper and faster than
separate calls).

Judgments:
  route_action  Choice over the real RouterDecision.Action set + an
                explicit no-match option (`none_of_these`), per the
                TypeSafe "candidate coverage" rule.
  is_fast_path  Noul: could this be answered WITHOUT waking the VLM?

Per the TypeSafe confidence guidance, confidence is distribution
concentration, not permission to act — thresholds are evaluated in the
report, never hardcoded as universal rules here.
"""

from .taxonomy import ACTION_CRITERIA, FAST_PATH_CRITERIA, SYSTEM_DESCRIPTION

# Lazy SDK import: dry-run mode must work without typesafe-sdk installed.


def route_questions() -> dict:
    """Fresh question objects per call (SDK objects are not reused across calls)."""
    from typesafe_sdk import Choice, Noul

    # Question ids are code-only; the model never sees them.
    return {
        "route_action": Choice(
            instructions=(
                "Which handler should run for this spoken request? Consider "
                "the full utterance, including any previous turn when present. "
                "Choose exactly one."
            ),
            criteria=ACTION_CRITERIA,
        ),
        "is_fast_path": Noul(
            instructions=(
                "Could this request be answered correctly WITHOUT waking the "
                "vision-language model — using only deterministic on-device "
                "pipelines (OCR text extraction, pixel color sampling, "
                "ambient light sensing)?"
            ),
            criteria=FAST_PATH_CRITERIA,
        ),
    }


def build_state(item: dict) -> dict:
    """Named state fields per TypeSafe state guidance (no prompt-stuffing)."""
    state = {
        "system_description": SYSTEM_DESCRIPTION,
        "spoken_query": item["query"],
    }
    if item.get("previous"):
        state["previous_user_query"] = item["previous"]
    if item.get("lang"):
        state["query_language"] = item["lang"]
    return state


def extract(result) -> dict:
    """Pull routing fields from a system_one response."""
    route = result.choices["route_action"]
    fast = result.nouls["is_fast_path"]
    return {
        "jev_action": route.choice,
        "jev_probabilities": dict(route.probabilities),
        "jev_confidence": route.confidence,
        "jev_fast_path_noul": fast.noul,
    }
