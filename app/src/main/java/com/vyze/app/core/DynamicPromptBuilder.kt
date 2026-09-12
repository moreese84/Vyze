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
            val langName = languageNameForLocale(userLocale)
            if (userLocale != Locale.US && userLocale.language != "en") {
                sb.appendLine("[OUTPUT LANGUAGE: $langName] Write EVERY word of your response in $langName. Do NOT use English. Do NOT translate. This is mandatory.")
                sb.appendLine()
            }

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
                sb.appendLine("The OCR text above is the ground truth. Read it as whole words and " +
                    "continuous sentences — never spell it letter by letter. When asked to read " +
                    "text, read ALL of it in reading order; do not summarize, skip, or stop early.")
            }

            // 2b-bis. LEARNED BREVITY — silently adapted answer length.
            //     (Placed after OCR so the reading directive above always wins
            //     for text reads: OCR reading is ground-truth playback and
            //     must never be cut short, regardless of brevity.)
            when (brevityLevel) {
                com.vyze.app.memory.PreferenceLearner.BrevityLevel.BRIEF -> {
                    sb.appendLine("LENGTH: Keep your answer concise — at most 2 short sentences. The user is pressed for time.")
                    sb.appendLine()
                }
                com.vyze.app.memory.PreferenceLearner.BrevityLevel.TERSE -> {
                    sb.appendLine("LENGTH: Answer in ONE short sentence. The user consistently prefers minimal answers.")
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

            // 4. Language mirror — reinforce at bottom
            if (userLocale != Locale.US && userLocale.language != "en") {
                sb.appendLine("REMEMBER: Respond only in $langName.")
            }

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
         * System directive — injected as <|turn|>system block.
         * Prevents Gemma from wasting cycles on internal English reasoning chains.
         */
        private const val SYSTEM_DIRECTIVE =
            "You are a fast, concise visual assistant. Describe scene layouts and spatial objects " +
            "directly in the language requested by the user without cross-translating or outputting " +
            "internal reasoning chains. Use clear, natural punctuation (commas, periods, and short clauses) " +
            "to guide spoken delivery. Respond only in the requested language."

        /**
         * NAVIGATION MODE — used for generic taps and automatic spatial descriptions.
         */
        private const val NAV_RULES_PROSE =
            "Describe the scene in 1-2 DENSE sentences — more useful detail per word, never wordy prose. " +
            "For each key object say what it is plus the details you can ACTUALLY SEE: " +
            "color, size, material, and state (open/closed, full/empty, lying/standing). " +
            "Add left/center/right + distance when it places the object for the user. " +
            "For a packaged product (packet, box, bottle, can), first say its BRAND name " +
            "and product type exactly as printed, then its details. " +
            "Read printed words verbatim in ORIGINAL language as whole words — never spell them letter by letter. " +
            "Vehicle plates, codes, serial and phone numbers are read CHARACTER BY CHARACTER — letters one by one, digits one by one ('QLB 3469' is spoken 'Q L B, three four six nine'), never as a quantity. " +
            "No filler phrases ('there is', 'I see', 'in the image', 'it appears'). " +
            "Use clear punctuation: periods to end sentences, commas to separate details. " +
            "If unsure about an object, say 'not clearly visible'. Do NOT guess or hallucinate. " +
            "Mirrors/glass: describe the surface itself.\n"

        private const val DIRECT_RULES_PROSE =
            "Answer directly in the first sentence. " +
            "Name the object and give compact, useful details — size, color, material, " +
            "state (open/closed, full/empty) — only what you can ACTUALLY see, never long prose. " +
            "If it is a packaged product (packet, box, bottle, can), first say its BRAND name and product type " +
            "exactly as printed on it, then its details. " +
            "Read text verbatim in ORIGINAL language as whole words and sentences — never spell letter by letter. " +
            "Vehicle plates, codes, serial and phone numbers are read CHARACTER BY CHARACTER — letters one by one, digits one by one ('QLB 3469' is spoken 'Q L B, three four six nine'), never as a quantity. " +
            "When asked to read text, read the ENTIRE text in reading order; never stop halfway or summarize. " +
            "Keep the whole answer to 1-3 short spoken sentences. " +
            "Use clear punctuation: periods to end sentences, commas for pauses between details. " +
            "If text is blurry or unreadable, say 'Text is unclear' — NEVER guess. " +
            "If no text visible, say 'No text visible'. " +
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
            "Input: door in front. Output: Brown wooden door, closed, center, about 2 steps ahead.\n" +
            "Input: person nearby. Output: Person on your left, about 1 step away.\n" +
            "Input: sofa scene. Output: Grey fabric sofa, soft cushions, about 3 steps ahead. Low wooden table in front of it.\n" +
            "Input: bottle on table. Output: Clear glass water bottle, half full, on the table about 1 step ahead.\n" +
            "Input: dark room. Output: Dark room. No obstacles detected within 3 steps.\n" +
            "Input: red packet on table. Output: Small red Maggi instant noodle packet, closed, on the table about 1 step ahead — label reads Maggi Kari.\n" +
            "Input: car plate ahead. Output: Vehicle plate Q L B, three four six nine."

        private const val NAV_EXAMPLES_MS =
            "Examples:\n" +
            "Input: pintu di hadapan. Output: Pintu kayu perang, tertutup, di tengah, kira-kira 2 langkah di hadapan.\n" +
            "Input: orang berdekatan. Output: Orang di sebelah kiri anda, kira-kira 1 langkah.\n" +
            "Input: sofa. Output: Sofa kain kelabu, cushion lembut, kira-kira 3 langkah di hadapan. Meja kayu rendah di hadapannya.\n" +
            "Input: botol di atas meja. Output: Botol air kaca lutsinar, separuh penuh, di atas meja kira-kira 1 langkah.\n" +
            "Input: bilik gelap. Output: Bilik gelap. Tiada halangan dikesan dalam 3 langkah.\n" +
            "Input: paket merah di atas meja. Output: Paket mi Maggi merah kecil, tertutup, di atas meja kira-kira 1 langkah — label tertulis Maggi Kari.\n" +
            "Input: plat kereta di hadapan. Output: Plat kenderaan Q L B, tiga empat enam sembilan."

        private const val NAV_EXAMPLES_ZH =
            "Examples:\n" +
            "Input: 前面有门。Output: 棕色木门，关着，在正前方，大约两步远。\n" +
            "Input: 附近有人。Output: 有人在你的左边，大约一步远。\n" +
            "Input: 沙发。Output: 灰色布沙发，软垫，大约三步远。前面有一张矮木桌。\n" +
            "Input: 桌上有瓶子。Output: 透明玻璃水瓶，半满，在桌上，大约一步远。\n" +
            "Input: 黑暗的房间。Output: 黑暗的房间。三步内没有检测到障碍物。\n" +
            "Input: 桌上有红色包装。Output: 桌上有一包红色的小包装Maggi快熟面，封着口——标签写着Maggi Kari。\n" +
            "Input: 前面有车牌。Output: 车牌Q L B，数字是三、四、六、九。"

        private const val DIRECT_EXAMPLES_EN =
            "Examples:\n" +
            "Input: what is this? Image shows a red packet. Output: Small red Maggi instant noodle packet, closed. Label reads Maggi Kari — noodles and seasoning sachets inside.\n" +
            "Input: what medicine is this? Image shows Diclac Retard box. Output: Diclac Retard, diclofenac sodium 100 milligram. Take one tablet daily after meals.\n" +
            "Input: read this label. Image shows price tag RM12.90. Output: Price is 12 Ringgit and 90 sen.\n" +
            "Input: what does this sign say? Image blurry. Output: Text is unclear."

        private const val DIRECT_EXAMPLES_MS =
            "Examples:\n" +
            "Input: apa ini? Imej menunjukkan paket mi merah. Output: Paket mi Maggi merah kecil, tertutup. Label tertulis Maggi Kari — mi dan sachet perencah di dalamnya.\n" +
            "Input: ubat apa ini? Imej menunjukkan kotak Diclac Retard. Output: Diclac Retard, diklofenak natrium 100 miligram. Ambil satu tablet sehari selepas makan.\n" +
            "Input: baca label ini. Imej menunjukkan tag harga RM12.90. Output: Harganya 12 Ringgit dan 90 sen.\n" +
            "Input: apa yang tertulis di papan tanda ini? Imej kabur. Output: Teks tidak jelas."

        private const val DIRECT_EXAMPLES_ZH =
            "Examples:\n" +
            "Input: 这是什么？图像显示一个红色包装。Output: 一包红色的小包装Maggi快熟面，封着口。标签写着Maggi Kari——里面是面条和调味包。\n" +
            "Input: 这是什么药？图像显示Diclac Retard药盒。Output: Diclac Retard，双氯芬酸钠100毫克。每天饭后服用一片。\n" +
            "Input: 读一下这个标签。图像显示价格标签RM12.90。Output: 价格是12令吉90仙。\n" +
            "Input: 这个牌子上写什么？图像模糊。Output: 文字不清楚。"

        /** Navigation rules with few-shot examples in the user's language. */
        private fun navigationRulesFor(language: String): String = when (language) {
            "ms" -> NAV_RULES_PROSE + NAV_EXAMPLES_MS
            "zh" -> NAV_RULES_PROSE + NAV_EXAMPLES_ZH
            else -> NAV_RULES_PROSE + NAV_EXAMPLES_EN
        }

        /** Direct-query rules with few-shot examples in the user's language. */
        private fun directQueryRulesFor(language: String): String = when (language) {
            "ms" -> DIRECT_RULES_PROSE + DIRECT_EXAMPLES_MS
            "zh" -> DIRECT_RULES_PROSE + DIRECT_EXAMPLES_ZH
            else -> DIRECT_RULES_PROSE + DIRECT_EXAMPLES_EN
        }

        private const val DEFAULT_NAVIGATION_QUERY =
            "Describe environment: obstacles, doors, people, text."

        private const val OUTPUT_CONSTRAINTS =
            "1-2 sentences. No bullet/markdown."

        /** Fallback prompt — used if dynamic prompt construction fails. */
        private const val FALLBACK_PROMPT = """You are Vyze, an accessible vision engine. Describe spatial layouts and obstacles directly. Do NOT use filler words like 'I see' or 'This photo shows'. Keep answers under 2 sentences.

Describe the immediate environment for navigation. Focus on obstacles, doors, people, and visible text.

Output 1-2 spoken sentences with spatial positioning. No filler, no formatting."""

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
            "clearly. NEVER guess or invent a value. Do not mention serial numbers."

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
            "a bank name. Do not read or mention the card number."

        private const val CONTINUOUS_MODE_RULES =
            "Instant assistant. Key objects + position. 15 words max. Use commas between items, period at end."

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
