"""DEPRECATED — superseded by tools/jev_harness (Phase 0 harness).

Why retired:
  * pre-v1 integration (`pydantic_ai` Agent('typesafe:jev-latest')) — the
    shipping API is the TypeSafe SDK's Choice/Noul/Score primitives;
  * the tool enum did not match the real registry (emergency_sos and
    trigger_haptic_feedback do not exist as speech-routable tools);
  * free-text `confidence_score` is not a calibrated probability.

Run the replacement instead:

    python -m tools.jev_harness route --live     # routing labels + agreement
    python -m tools.jev_harness audit --live     # echo / mirror / relevance
"""

raise SystemExit(
    "eval_vyze_router.py is retired. Use: python -m tools.jev_harness "
    "(see tools/jev_harness/README.md)"
)
