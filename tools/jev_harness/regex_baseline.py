# ruff: noqa: E501
"""Pure-Python mirror of VyzeShadowRouter.decideSpeech — the regex baseline.

Faithful to the Kotlin (do not "improve" it here; any real rule improvement
belongs in the student router, Phase 3):

    READ_KEYWORDS = read, text, label, sign, baca, teks, 字, 读, 念,
                    letter, surat, document, 信, 信件
    empty transcript            -> IGNORE
    contains any READ_KEYWORD   -> VLM_TEXT_READ
    otherwise                   -> VLM_VOICE_QUERY

Everything else (gestures, SOS-from-gesture) is outside the speech router
and therefore outside this baseline.
"""

READ_KEYWORDS = [
    "read", "text", "label", "sign", "baca", "teks", "字", "读", "念",
    "letter", "surat", "document", "信", "信件",
]


def decide_speech(spoken_text: str) -> str:
    """Mirror of VyzeShadowRouter.decideSpeech — returns an Action name."""
    text = spoken_text.strip()
    if not text:
        return "IGNORE"
    lower = text.lower()
    if any(kw in lower for kw in READ_KEYWORDS):
        return "VLM_TEXT_READ"
    return "VLM_VOICE_QUERY"
