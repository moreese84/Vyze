# ruff: noqa: E501
"""Compliance audit: did the on-device answer violate the prompt contract?

Grades (query, answer) pairs from recorded Vyze interactions on the two
contract dimensions the prompt pipeline was hardened for, plus relevance:

  echoed_question        Noul — did the answer start by repeating the
                         user's question (the echo bug we fixed)?
  language_matches_query Noul — is the answer in the same language and
                         script as the query (en / ms / zh mirroring)?
  relevance              Score — does the answer actually answer the query?

Jev is text-only, so this audits TRANSCRIPTS, never audio. The three
questions are independent judgments over one state → one batched request
per pair (parallel questions, one call), per SKILL.md guidance.
"""

from .taxonomy import RELEVANCE_LEVELS


def audit_questions() -> dict:
    """Fresh question objects per call (never reuse SDK objects across requests)."""
    # Lazy SDK import: dry-run mode must work without typesafe-sdk installed.
    from typesafe_sdk import Noul, Score

    return {
        "echoed_question": Noul(
            instructions=(
                "Did the assistant's answer start by repeating or echoing "
                "the user's question — for example beginning with the "
                "question's own words — instead of answering directly?"
            ),
            criteria={
                "true": (
                    "The answer repeats the question's words before or "
                    "instead of answering it"
                ),
                "false": "The answer begins directly with the answer content",
            },
        ),
        "language_matches_query": Noul(
            instructions=(
                "Is the assistant's answer written in the SAME language and "
                "script as the user's query? The app must mirror exactly: an "
                "English query gets an English answer, a Malay query a "
                "Malay answer, a Chinese query a Chinese answer."
            ),
            criteria={
                "true": "Answer language and script match the query",
                "false": (
                    "Answer is in a different language or script than the "
                    "query"
                ),
            },
        ),
        "relevance": Score(
            instructions=(
                "How well does the assistant's answer address the user's "
                "query? Judge only whether the answer content responds to "
                "what was asked."
            ),
            criteria=list(RELEVANCE_LEVELS),
        ),
    }


def build_state(item: dict) -> dict:
    """Named state fields per TypeSafe state guidance."""
    state = {"user_query": item["query"]}
    if item.get("previous"):
        state["previous_user_query"] = item["previous"]
    if item.get("answer"):
        state["assistant_answer"] = item["answer"]
    if item.get("lang"):
        state["query_language"] = item["lang"]
    return state


def extract(result) -> dict:
    """Pull audit fields from a system_one response."""
    relevance = result.scores["relevance"]
    return {
        "audit_echoed_noul": result.nouls["echoed_question"].noul,
        "audit_language_match_noul": result.nouls["language_matches_query"].noul,
        "audit_relevance_level": relevance.score,
        "audit_relevance_probabilities": dict(getattr(relevance, "probabilities", {}) or {}),
    }
