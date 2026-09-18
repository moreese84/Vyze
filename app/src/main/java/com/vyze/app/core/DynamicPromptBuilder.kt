package com.vyze.app.core

import android.util.Log
import com.vyze.app.data.MemoryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Constructs dynamic system prompts for the on-device VLM (Gemma 4 E2B).
 *
 * ## Intent-Based Prompt Branching
 * Automatically selects the appropriate system rules based on whether the user
 * asked a targeted question (e.g., "What medicine is this?") or triggered a
 * generic tap/snapshot (e.g., automatic spatial description).
 *
 * - **Navigation mode** (`BASE_RULES_NAVIGATION`): spatial layouts, obstacles, distances
 * - **Direct query mode** (`BASE_RULES_DIRECT_QUERY`): text extraction, OCR, direct answers
 */
class DynamicPromptBuilder(private val memoryDao: MemoryDao) {

    // ── Public API ─────────────────────────────────────────────────

    suspend fun buildPrompt(
        snapshotDescription: String = "",
        queryOverride: String? = null,
        continuousMode: Boolean = false,
        userLocale: Locale = Locale.US,
        ocrText: String? = null,
        currencyMode: Boolean = false,
        bankCardMode: Boolean = false,
        memoryContext: String? = null,
        textOnlyMode: Boolean = false,
        brevityLevel: com.vyze.app.memory.PreferenceLearner.BrevityLevel = com.vyze.app.memory.PreferenceLearner.BrevityLevel.NORMAL,
        dialogueContext: String? = null
    ): String {
        return try {
            val sb = StringBuilder()
            val isDirectQuery = !queryOverride.isNullOrBlank()

            // 0. LANGUAGE MIRROR — TOP OF PROMPT (before any English rules)
            //    This is the strongest lever for non-English output.
            //    MIRRORING SYMMETRY (Phase 1): English gets an explicit anchor
            //    too. Without it, Malay dialogue history / few-shot residue in
            //    the prompt pulls a small model back toward Malay on MS→EN
            //    switches — the asymmetric direction that failed on device.
            val langName = languageNameForLocale(userLocale)
            if (userLocale != Locale.US && userLocale.language != "en") {
                sb.appendLine("[OUTPUT LANGUAGE: $langName] Write EVERY word of your response in $langName. Do NOT use English. Do NOT translate. This is mandatory.")
            } else {
                sb.appendLine("[OUTPUT LANGUAGE: English] Write every word of your response in English. Do not continue in any other language.")
            }
            sb.appendLine()

            // 1. Inject appropriate rules based on query intent
            when {
                // Text-only Q&A — general knowledge, NO camera frame exists.
                // The model must answer from its own knowledge, never invent
                // a scene, and stay concise for spoken delivery.
                textOnlyMode -> sb.appendLine(TEXT_ONLY_RULES)
                continuousMode -> sb.appendLine(CONTINUOUS_MODE_RULES)
                isDirectQuery -> sb.appendLine(directQueryRulesFor(userLocale.language))
                else -> sb.appendLine(navigationRulesFor(userLocale.language))
            }

            // 2. OCR pre-extracted text (if available — feeds clean text to model)
            if (!ocrText.isNullOrBlank()) {
                sb.appendLine("OCR: $ocrText")
            // 2b. Reading guidance — the model sometimes echoes OCR text
            //     letter-by-letter ("H-U-R-I-X") or stops early on long
            //     passages (box back panels). Anchor the desired behavior.
            //     OCR CARVE-OUT (persona override): a full label/document read
            //     is ground-truth playback — the conversational 2-sentence cap
            //     in the system directive must never truncate it, so reading
            //     tasks explicitly override the cap here.
            sb.appendLine("The OCR text above is the ground truth. Read it as whole words and " +
                "continuous sentences — never spell it letter by letter. When asked to read " +
                "text, read ALL of it in reading order; do not summarize, skip, or stop early. " +
                "When reading text aloud, the two sentence limit does NOT apply — read every " +
                "word of the OCR text completely. Output plain spoken text only: never " +
                "markdown symbols, bullets, dashes, asterisks, or emoji.")
            }

            // 2b-bis. LEARNED BREVITY — silently adapted answer length.
            //     (Placed after OCR so the reading directive above always wins
            //     for text reads: OCR reading is ground-truth playback and
            //     must never be cut short, regardless of brevity.)
            when (brevityLevel) {
                com.vyze.app.memory.PreferenceLearner.BrevityLevel.BRIEF -> {
                    // PERSPECTIVE FIX: the global persona now caps answers at ONE
                    // short spoken sentence (VlmEngineManager.SYSTEM_DIRECTIVE) —
                    // the adaptive BRIEF level must not instruct a LOOSER cap.
                    sb.appendLine("LENGTH: Answer in ONE short sentence, kept tight — the user is pressed for time.")
                    sb.appendLine()
                }
                com.vyze.app.memory.PreferenceLearner.BrevityLevel.TERSE -> {
                    sb.appendLine("LENGTH: Answer in ONE short sentence with only the essential detail. The user consistently prefers minimal answers.")
                    sb.appendLine()
                }
                com.vyze.app.memory.PreferenceLearner.BrevityLevel.NORMAL -> { /* no adaptation */ }
            }

            // 2c-pre. DIALOGUE MEMORY — recent voice exchanges for follow-ups.
            //     Enables pronoun resolution across turns ("what about the one
            //     behind it?") and lets the model answer CONVERSATIONALLY
            //     instead of re-describing the whole scene from scratch.
            //     Injected only for genuine voice follow-ups (see controller).
            if (!dialogueContext.isNullOrBlank()) {
                sb.appendLine("Recent conversation:")
                sb.appendLine(dialogueContext)
                sb.appendLine("This is a follow-up in an ongoing conversation. Resolve words like 'it', 'that', 'the one' using the conversation above. Answer the follow-up directly — do NOT re-describe the whole scene.")
                sb.appendLine()
            }

            // 2c. Prior scene memory (context injection — never a substitute)
            //     Only present when the current frame strongly matches a recent
            //     past scan. The model still analyzes the FRESH frame; the memory
            //     just lets it confirm continuity instead of describing from zero.
            if (!memoryContext.isNullOrBlank()) {
                sb.appendLine("Prior scene memory: $memoryContext")
                sb.appendLine("If this is the same scene you described before, confirm it briefly in " +
                    "your answer. If it is a different scene, or something has changed, describe " +
                    "ONLY what you now see — never repeat the prior description if it does not " +
                    "match the current image.")
                sb.appendLine()
            }

            // 3. Task specification
            if (isDirectQuery) {
                sb.appendLine("Answer: \"$queryOverride\"")
            } else {
                sb.appendLine(DEFAULT_NAVIGATION_QUERY)
            }

            // 3b. Currency reading rules (banknotes + coins)
            if (currencyMode) {
                sb.appendLine(CURRENCY_RULES)
            }

            // 3c. Bank card identification rules
            if (bankCardMode) {
                sb.appendLine(BANK_CARD_RULES)
            }

            // 4. Language mirror — reinforce at bottom (ALL languages now:
            //    symmetric anchor, English included — see the top mirror note)
            sb.appendLine("REMEMBER: Respond only in $langName.")

            val prompt = sb.toString()
            Log.d(TAG, "Built prompt: ${prompt.length} chars, " +
                "mode=${if (isDirectQuery) "DIRECT_QUERY" else "NAVIGATION"}")
            prompt

        } catch (e: Exception) {
            Log.e(TAG, "Failed to build dynamic prompt, using fallback", e)
            FALLBACK_PROMPT
        }
    }

    // ── Memory Write Operations ────────────────────────────────────

    suspend fun setPreference(key: String, value: String) {
        withContext(Dispatchers.IO) {
            memoryDao.upsert(
                com.vyze.app.data.VyzeMemoryEntity(
                    category = "preference",
                    key = key,
                    value = value
                )
            )
        }
    }

    suspend fun storeEnvironmentObservation(description: String) {
        withContext(Dispatchers.IO) {
            memoryDao.upsert(
                com.vyze.app.data.VyzeMemoryEntity(
                    category = "environment",
                    key = "scene_${System.currentTimeMillis()}",
                    value = description
                )
            )
            val cutoff = System.currentTimeMillis() - (24L * 60 * 60 * 1000)
            memoryDao.pruneOlderThan(cutoff)
        }
    }

    suspend fun storeInteraction(query: String, response: String) {
        withContext(Dispatchers.IO) {
            memoryDao.upsert(
                com.vyze.app.data.VyzeMemoryEntity(
                    category = "interaction",
                    key = "query_${System.currentTimeMillis()}",
                    value = response,
                    metadata = query
                )
            )
        }
    }

    // ── Section Formatters ─────────────────────────────────────────

    // ── Helpers ────────────────────────────────────────────────────

    /**
     * Map a Locale to a human-readable language name for the prompt directive.
     * Uses Locale.getDisplayLanguage() for dynamic resolution — no hardcoded list.
     */
    private fun languageNameForLocale(locale: Locale): String {
        return locale.getDisplayLanguage(Locale.US).ifBlank { locale.language }
    }

    // ── Constants ──────────────────────────────────────────────────

    companion object {
        private const val TAG = "DynamicPromptBuilder"

        /**
         * NAVIGATION MODE — used for generic taps and automatic spatial descriptions.
         */
        private const val NAV_RULES_PROSE =
            "Describe the scene in 1-2 complete, natural spoken sentences — the way you " +
            "would tell a person standing next to you. " +
            "Write full sentences with a subject and a verb; NEVER answer with a list of " +
            "attributes separated by commas. " +
            "For each key object say what it is plus the details you can ACTUALLY SEE: " +
            "color, size, material, and state (open/closed, full/empty, lying/standing). " +
            "Add left/center/right + distance when it places the object for the user. " +
            "For a packaged product (packet, box, bottle, can), first say its BRAND name " +
            "and product type exactly as printed, then its details. " +
            "Read printed words verbatim in ORIGINAL language as whole words — never spell them letter by letter. " +
            "Vehicle plates, codes, serial and phone numbers are read CHARACTER BY CHARACTER — letters one by one, digits one by one ('QLB 3469' is spoken 'Q L B, three four six nine'), never as a quantity. " +
            "Skip phrases like 'in the image' or 'it appears', but natural speech like " +
            "'there is' or 'a person is standing' is exactly right. " +
            "SPATIAL-FIRST PHRASING: state the location or the object first — say 'In front of " +
            "you is a mug' or 'To your left is a door', and NEVER begin a sentence with 'You are " +
            "in front of', 'You are looking at', or 'You are facing'. " +
            "Your reply is read aloud by text to speech, so it must be pure plain text: " +
            "NEVER output markdown symbols, never output bullets, dashes, asterisks, " +
            "number signs, underscores, or emoji, and never use lists or headings. " +
            "If unsure about an object, say 'not clearly visible'. Do NOT guess or hallucinate. " +
            "Mirrors/glass: describe the surface itself.\n"

        private const val DIRECT_RULES_PROSE =
            "Answer directly in the first sentence. " +
            "Write in complete, natural spoken sentences with a subject and a verb — NEVER a " +
            "list of attributes separated by commas. " +
            "Name the object and give compact, useful details — size, color, material, " +
            "state (open/closed, full/empty) — only what you can ACTUALLY see, never long prose. " +
            "If it is a packaged product (packet, box, bottle, can), first say its BRAND name and product type " +
            "exactly as printed on it, then its details. " +
            "Read text verbatim in ORIGINAL language as whole words and sentences — never spell letter by letter. " +
            "Vehicle plates, codes, serial and phone numbers are read CHARACTER BY CHARACTER — letters one by one, digits one by one ('QLB 3469' is spoken 'Q L B, three four six nine'), never as a quantity. " +
            "When asked to read text, read the ENTIRE text in reading order; never stop halfway or summarize. " +
            "Keep the whole answer to 1-3 short spoken sentences. " +
            "If text is blurry or unreadable, say 'Text is unclear' — NEVER guess. " +
            "If no text visible, say 'No text visible'. " +
            "NEVER begin a sentence with 'You are in front of', 'You are looking at', or 'You " +
            "are facing' — state the location or object first instead: 'In front of you is the " +
            "mug.' " +
            "Your reply is read aloud by text to speech, so it must be pure plain text: " +
            "NEVER output markdown symbols, never output bullets, dashes, asterisks, " +
            "number signs, underscores, or emoji, and never use lists or headings. " +
            "Only what you see in THIS image.\n"

        // ── Language-matched few-shot examples ────────────────────────
        // For a small on-device model, in-context examples dominate abstract
        // instructions. English-only examples taught structure but pulled the
        // OUTPUT language toward English even when the language directives
        // said otherwise. The examples below are the SAME six/four scenes
        // rendered in Malay / Chinese, so the demonstrated structure AND the
        // demonstrated output language always match the user's spoken
        // language. Natural Malaysian loanwords (Maggi, cushion-style mixing)
        // are kept where locals actually speak that way.

        private const val NAV_EXAMPLES_EN =
            "Examples:\n" +
            "Input: what is in front of me? Output: In front of you is a closed brown wooden door, about two steps away.\n" +
            "Input: is someone near me? Output: To your left stands a person, about one step away.\n" +
            "Input: describe the room. Output: Ahead of you is a grey fabric sofa with soft cushions about three steps away, and in front of it sits a low wooden table.\n" +
            "Input: what is on the table? Output: On the table is a clear glass water bottle, half full, about one step ahead.\n" +
            "Input: what is around me? Output: The room is dark, and no obstacles are visible within three steps.\n" +
            "Input: red packet on table. Output: On the table about one step ahead is a small red packet of Maggi instant noodles, and the label reads Maggi Kari.\n" +
            "Input: car plate ahead. Output: The vehicle plate ahead reads Q L B, three four six nine."

        private const val NAV_EXAMPLES_MS =
            "Examples:\n" +
            "Input: apa ada di hadapan saya? Output: Di hadapan anda ialah sebuah pintu kayu perang yang tertutup, kira-kira dua langkah jauhnya.\n" +
            "Input: ada orang dekat dengan saya? Output: Di sebelah kiri anda berdiri seseorang, kira-kira satu langkah jauhnya.\n" +
            "Input: terangkan bilik ini. Output: Di hadapan anda ialah sebuah sofa kain kelabu dengan cushion lembut kira-kira tiga langkah jauhnya, dan di hadapannya ada meja kayu rendah.\n" +
            "Input: apa di atas meja? Output: Di atas meja ialah sebuah botol air kaca lutsinar, separuh penuh, kira-kira satu langkah di hadapan.\n" +
            "Input: apa ada sekeliling saya? Output: Bilik ini gelap, dan tiada halangan yang kelihatan dalam tiga langkah.\n" +
            "Input: paket merah di atas meja. Output: Di atas meja kira-kira satu langkah ialah satu paket kecil mi Maggi berwarna merah, dan label tertulis Maggi Kari.\n" +
            "Input: plat kereta di hadapan. Output: Plat kenderaan di hadapan tertulis Q L B, tiga empat enam sembilan."

        private const val NAV_EXAMPLES_ZH =
            "Examples:\n" +
            "Input: 我前面有什么？Output: 您的正前方大约两步远，有一扇关着的棕色木门。\n" +
            "Input: 我附近有人吗？Output: 您的左边大约一步远站着一个人。\n" +
            "Input: 描述一下这个房间。Output: 您正前方大约三步远有一张带软垫的灰色布沙发，沙发前面还有一张矮木桌。\n" +
            "Input: 桌上有什么？Output: 桌上大约一步远有一个透明的玻璃水瓶，半满。\n" +
            "Input: 我周围有什么？Output: 房间很暗，三步之内看不到任何障碍物。\n" +
            "Input: 桌上有红色包装。Output: 桌上大约一步远有一包红色的小包装Maggi快熟面，标签写着Maggi Kari。\n" +
            "Input: 前面有车牌。Output: 前方的车牌是Q L B，三、四、六、九。"

        private const val DIRECT_EXAMPLES_EN =
            "Examples:\n" +
            "Input: what is this? Image shows a red packet. Output: This is a small red packet of Maggi instant noodles, and the label reads Maggi Kari — noodles and seasoning sachets are inside.\n" +
            "Input: what medicine is this? Image shows Diclac Retard box. Output: This is Diclac Retard, diclofenac sodium 100 milligram — take one tablet daily after meals.\n" +
            "Input: read this label. Image shows price tag RM12.90. Output: The price is 12 Ringgit and 90 sen.\n" +
            "Input: what is in front of me? Image shows a white mug on a table. Output: In front of you on the table is a white mug.\n" +
            "Input: what does this sign say? Image blurry. Output: The text is unclear."

        private const val DIRECT_EXAMPLES_MS =
            "Examples:\n" +
            "Input: apa ini? Imej menunjukkan paket mi merah. Output: Ini adalah satu paket kecil mi Maggi berwarna merah, dan label tertulis Maggi Kari — mi dan sachet perencah berada di dalamnya.\n" +
            "Input: ubat apa ini? Imej menunjukkan kotak Diclac Retard. Output: Ini adalah Diclac Retard, diklofenak natrium 100 miligram — ambil satu tablet sehari selepas makan.\n" +
            "Input: baca label ini. Imej menunjukkan tag harga RM12.90. Output: Harganya ialah 12 Ringgit dan 90 sen.\n" +
            "Input: apa di hadapan saya? Imej menunjukkan cawan putih di atas meja. Output: Di hadapan anda di atas meja ialah sebuah cawan putih.\n" +
            "Input: apa yang tertulis di papan tanda ini? Imej kabur. Output: Teksnya tidak jelas."

        private const val DIRECT_EXAMPLES_ZH =
            "Examples:\n" +
            "Input: 这是什么？图像显示一个红色包装。Output: 这是一包红色的小包装Maggi快熟面，标签写着Maggi Kari——里面是面条和调味包。\n" +
            "Input: 这是什么药？图像显示Diclac Retard药盒。Output: 这是Diclac Retard，双氯芬酸钠100毫克——每天饭后服用一片。\n" +
            "Input: 读一下这个标签。图像显示价格标签RM12.90。Output: 价格是12令吉90仙。\n" +
            "Input: 我前面是什么？图像显示桌上有一个白色马克杯。Output: 您面前的桌上是一个白色马克杯。\n" +
            "Input: 这个牌子上写什么？图像模糊。Output: 文字不清楚。"

        // ── Memoized static rule blocks (cache micro-win) ──────────
        // buildPrompt runs on every capture; re-concatenating identical
        // immutable strings each turn is pure allocation churn. The six
        // static blocks are built once (lazy = thread-safe) and reused.
        private val navRulesEn by lazy { NAV_RULES_PROSE + NAV_EXAMPLES_EN }
        private val navRulesMs by lazy { NAV_RULES_PROSE + NAV_EXAMPLES_MS }
        private val navRulesZh by lazy { NAV_RULES_PROSE + NAV_EXAMPLES_ZH }
        private val directRulesEn by lazy { DIRECT_RULES_PROSE + DIRECT_EXAMPLES_EN }
        private val directRulesMs by lazy { DIRECT_RULES_PROSE + DIRECT_EXAMPLES_MS }
        private val directRulesZh by lazy { DIRECT_RULES_PROSE + DIRECT_EXAMPLES_ZH }

        /** Navigation rules with few-shot examples in the user's language. */
        private fun navigationRulesFor(language: String): String = when (language) {
            "ms" -> navRulesMs
            "zh" -> navRulesZh
            else -> navRulesEn
        }

        /** Direct-query rules with few-shot examples in the user's language. */
        private fun directQueryRulesFor(language: String): String = when (language) {
            "ms" -> directRulesMs
            "zh" -> directRulesZh
            else -> directRulesEn
        }

        private const val DEFAULT_NAVIGATION_QUERY =
            "Describe environment: obstacles, doors, people, text."

        private const val OUTPUT_CONSTRAINTS =
            "Keep answers to 1 to 2 spoken sentences. Never output markdown " +
            "symbols, bullets, or emoji — plain text only for text to speech."

        /** Fallback prompt — used if dynamic prompt construction fails. */
        private const val FALLBACK_PROMPT = """You are Vyze, an accessible vision engine. Describe spatial layouts and obstacles directly. Do NOT use filler words like 'I see' or 'This photo shows'. Keep answers under 2 sentences.

Describe the immediate environment for navigation. Focus on obstacles, doors, people, and visible text.

Output 1 to 2 spoken sentences with spatial positioning. Your reply is read aloud by text to speech, so it must be pure plain text: never output markdown symbols, bullets, dashes, asterisks, number signs, or emoji. Plain sentences only, no filler."""

        /**
         * Money-reading rules — banknotes and coins.
         * Priority is exact value + no guessing: a wrong denomination is far
         * worse than "I cannot read it clearly" for a blind user.
         */
        private const val CURRENCY_RULES =
            "This is money — a banknote or a coin. Identify its VALUE from the " +
            "large numerals and printed text. State the value and the currency " +
            "(for example: 50 Ringgit, or 10 cents) and the dominant color. " +
            "If the value cannot be read clearly, say exactly: I cannot read this " +
            "clearly. NEVER guess or invent a value. Do not mention serial numbers. " +
            "Reply as pure plain text for text to speech: never output markdown " +
            "symbols, bullets, dashes, asterisks, or emoji."

        /**
         * Bank card identification rules.
         * Priority is exact bank name + card type from logos and printed text.
         * A wrong bank name is far worse than "I cannot identify this card"
         * for a blind user.
         */
        private const val BANK_CARD_RULES =
            "This is a bank card — debit, credit, or ATM card. Identify the BANK " +
            "name from the logo and printed text (for example: Maybank, CIMB, " +
            "Public Bank, HSBC). Then state the card type (debit, credit, or ATM) " +
            "if visible. If you cannot clearly identify the bank or card type, say " +
            "exactly: I cannot identify this card clearly. NEVER guess or invent " +
            "a bank name. Do not read or mention the card number. " +
            "Reply as pure plain text for text to speech: never output markdown " +
            "symbols, bullets, dashes, asterisks, or emoji."

        private const val CONTINUOUS_MODE_RULES =
            "Instant assistant. ONE short natural spoken sentence, about 15 words maximum: " +
            "the key objects, their positions and their states. Write a complete sentence " +
            "with a verb, and end it with a period — never a comma-separated list. " +
            "Reply as pure plain text for text to speech: never output markdown " +
            "symbols, bullets, dashes, asterisks, or emoji."

        /**
         * Text-only Q&A rules — used when NO camera frame is available
         * (general-knowledge voice questions). The model answers from its
         * own training, must not pretend to see anything, and stays
         * concise for spoken delivery.
         */
        private const val TEXT_ONLY_RULES =
            "Answer the question from your own knowledge. No camera image is " +
            "available, so do NOT describe any scene, object, or text — answer " +
            "the question directly. Use clear punctuation: periods to end " +
            "sentences, commas for pauses. Keep the answer concise: 1-3 sentences. " +
            "Reply as pure plain text for text to speech: never output markdown " +
            "symbols, bullets, dashes, asterisks, or emoji. " +
            "If you do not know the answer, say 'I do not know that' — never guess."

        /**
         * Language mirror directive — forces Gemma to respond in the same
         * language as the user's spoken query. {lang} is replaced dynamically
         * with the detected language name (e.g., "Malay", "Chinese").
         */
        private const val LANGUAGE_MIRROR_DIRECTIVE =
            "[OUTPUT LANGUAGE: {lang}] Write every word in {lang}. No English."
    }
}
