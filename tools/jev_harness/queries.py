# ruff: noqa: E501
"""Seed evaluation corpus for the Jev harness.

Three sources of queries:
  1. The five probes from the retired eval_vyze_router.py (kept for parity).
  2. Hand-written queries in en / ms / zh covering every action, including
     deliberately AMBIGUOUS ones (the "middle" the regex router cannot
     classify) — the region the distilled student is meant to win.
  3. Follow-up pairs: `previous` + `query`. Follow-ups like "what about
     this?" are the historically hardest case AND the echo-trap case the
     prompt pipeline was hardened against.

Schema: list of dicts with keys:
  id        unique stable id
  lang      "en" | "ms" | "zh"
  query     the spoken query as transcribed
  expected  the correct RouterDecision.Action name (None = only audit, no
            routing ground truth)
  previous  optional previous-turn user query (follow-up context)
  note      optional human note
"""

CORPUS = [
    # ── Retired script probes (parity) ────────────────────────────────
    {"id": "p1", "lang": "en", "query": "What does this medicine label say?", "expected": "VLM_TEXT_READ"},
    {"id": "p2", "lang": "en", "query": "Describe the entire room in front of me.", "expected": "VLM_SCENE_DESCRIBE"},
    {"id": "p3", "lang": "en", "query": "Is the door on my left or right?", "expected": "VLM_VOICE_QUERY"},
    {"id": "p4", "lang": "en", "query": "Give me a double tap vibration.", "expected": "IGNORE"},
    {"id": "p5", "lang": "en", "query": "Help, I fell down and need assistance!", "expected": "SOS"},

    # ── English: clear cases ──────────────────────────────────────────
    {"id": "e1", "lang": "en", "query": "Read this letter for me.", "expected": "VLM_TEXT_READ"},
    {"id": "e2", "lang": "en", "query": "What color is this shirt?", "expected": "FAST_COLOR_ANALYSIS"},
    {"id": "e3", "lang": "en", "query": "Is it dark in here?", "expected": "LIGHT_CHECK"},
    {"id": "e4", "lang": "en", "query": "What is in front of me?", "expected": "VLM_SCENE_DESCRIBE"},
    {"id": "e5", "lang": "en", "query": "Play some music.", "expected": "IGNORE"},
    {"id": "e6", "lang": "en", "query": "How much money is this banknote?", "expected": "VLM_VOICE_QUERY"},

    # ── English: the ambiguous middle (regex will likely fail these) ──
    {"id": "a1", "lang": "en", "query": "Is someone standing near me?", "expected": "VLM_VOICE_QUERY"},
    {"id": "a2", "lang": "en", "query": "Can you tell what this fruit is?", "expected": "VLM_VOICE_QUERY"},
    {"id": "a3", "lang": "en", "query": "Is this the exit sign?", "expected": "VLM_VOICE_QUERY"},
    {"id": "a4", "lang": "en", "query": "Is my bus number showing on that board?", "expected": "VLM_VOICE_QUERY"},
    {"id": "a5", "lang": "en", "query": "Does this look spoiled?", "expected": "VLM_VOICE_QUERY"},
    {"id": "a6", "lang": "en", "query": "Is there a chair I can sit on?", "expected": "VLM_VOICE_QUERY"},
    {"id": "a7", "lang": "en", "query": "Check if this bill is a fifty.", "expected": "VLM_VOICE_QUERY"},

    # ── Bahasa Malaysia ───────────────────────────────────────────────
    {"id": "m1", "lang": "ms", "query": "Baca label ubat ini.", "expected": "VLM_TEXT_READ"},
    {"id": "m2", "lang": "ms", "query": "Apa warna baju ini?", "expected": "FAST_COLOR_ANALYSIS"},
    {"id": "m3", "lang": "ms", "query": "Gelap ke di sini?", "expected": "LIGHT_CHECK"},
    {"id": "m4", "lang": "ms", "query": "Apa kat depan saya?", "expected": "VLM_SCENE_DESCRIBE"},
    {"id": "m5", "lang": "ms", "query": "Ada orang dekat dengan saya ke?", "expected": "VLM_VOICE_QUERY"},
    {"id": "m6", "lang": "ms", "query": "Nota ini berapa ringgit?", "expected": "VLM_VOICE_QUERY"},
    {"id": "m7", "lang": "ms", "query": "Adakah buah ini masak?", "expected": "VLM_VOICE_QUERY"},
    {"id": "m8", "lang": "ms", "query": "Pintu tu di kiri atau kanan saya?", "expected": "VLM_VOICE_QUERY"},
    {"id": "m9", "lang": "ms", "query": "Putar lagu sikit.", "expected": "IGNORE"},
    {"id": "m10", "lang": "ms", "query": "Tolong, saya jatuh! Saya tak boleh bangun.", "expected": "SOS"},

    # ── Bahasa Malaysia: ambiguous middle ─────────────────────────────
    {"id": "ma1", "lang": "ms", "query": "Papan tanda tu tulis apa?", "expected": "VLM_TEXT_READ"},
    {"id": "ma2", "lang": "ms", "query": "Boleh tak you tengok jalan ni ada kereta?", "expected": "VLM_VOICE_QUERY"},

    # ── Chinese ───────────────────────────────────────────────────────
    {"id": "z1", "lang": "zh", "query": "帮我读一读这个药瓶上的标签。", "expected": "VLM_TEXT_READ"},
    {"id": "z2", "lang": "zh", "query": "这件衣服是什么颜色？", "expected": "FAST_COLOR_ANALYSIS"},
    {"id": "z3", "lang": "zh", "query": "这里是不是很暗？", "expected": "LIGHT_CHECK"},
    {"id": "z4", "lang": "zh", "query": "我面前是什么？", "expected": "VLM_SCENE_DESCRIBE"},
    {"id": "z5", "lang": "zh", "query": "有人站在我旁边吗？", "expected": "VLM_VOICE_QUERY"},
    {"id": "z6", "lang": "zh", "query": "这张钞票是多少钱？", "expected": "VLM_VOICE_QUERY"},
    {"id": "z7", "lang": "zh", "query": "这水果熟了没有？", "expected": "VLM_VOICE_QUERY"},
    {"id": "z8", "lang": "zh", "query": "放一首歌来听。", "expected": "IGNORE"},
    {"id": "z9", "lang": "zh", "query": "救命，我摔倒了，起不来！", "expected": "SOS"},

    # ── Follow-up pairs: the echo-trap / pronoun-resolution cases ─────
    {"id": "f1", "lang": "en", "query": "What is in front of me?", "expected": "VLM_SCENE_DESCRIBE"},
    {"id": "f2", "lang": "en", "query": "What about this?", "expected": "VLM_VOICE_QUERY", "previous": "What is in front of me?"},

    {"id": "f3", "lang": "ms", "query": "Apa kat depan saya?", "expected": "VLM_SCENE_DESCRIBE"},
    {"id": "f4", "lang": "ms", "query": "Bagaimana dengan ini?", "expected": "VLM_VOICE_QUERY", "previous": "Apa kat depan saya?"},

    {"id": "f5", "lang": "zh", "query": "我面前是什么？", "expected": "VLM_SCENE_DESCRIBE"},
    {"id": "f6", "lang": "zh", "query": "那这个呢？", "expected": "VLM_VOICE_QUERY", "previous": "我面前是什么？"},

    {"id": "f7", "lang": "en", "query": "And this one?", "expected": "VLM_VOICE_QUERY", "previous": "What color is this shirt?"},
    {"id": "f8", "lang": "ms", "query": "Yang ini pula?", "expected": "VLM_VOICE_QUERY", "previous": "Apa warna baju ini?"},
    {"id": "f9", "lang": "zh", "query": "这个呢？", "expected": "VLM_VOICE_QUERY", "previous": "这件衣服是什么颜色？"},

    # A follow-up that should still be a READ (context shifts the object,
    # not the intent) — the student must learn "baca/读" intent persistence.
    {"id": "f10", "lang": "ms", "query": "Yang ini juga baca.", "expected": "VLM_TEXT_READ", "previous": "Baca label ubat ini."},
    {"id": "f11", "lang": "en", "query": "Read this one too.", "expected": "VLM_TEXT_READ", "previous": "What does this medicine label say?"},

    # Empty / noise
    {"id": "n1", "lang": "en", "query": "", "expected": "IGNORE"},
    {"id": "n2", "lang": "ms", "query": "errr... aaa...", "expected": "IGNORE"},
]
