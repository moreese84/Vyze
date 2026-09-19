package com.vyze.app.data

/**
 * One harness-compatible row of the debug-only interaction export (Phase 1
 * of the Jev distillation pipeline).
 *
 * PURE data mapping — no I/O, no Android, no network. The only writer is
 * the debug-sourceSet [com.vyze.app.debug.InteractionLogExporter]; the
 * release variant never references this class, so R8 strips it from the
 * shipped APK. The release binary stays 100% offline regardless.
 *
 * The schema mirrors tools/jev_harness corpus rows so the exported JSONL
 * feeds `python -m tools.jev_harness audit --corpus ...` directly.
 *
 * PRIVACY: the FULL built VLM prompt is deliberately never emitted — the
 * built prompt embeds memory context, OCR text, and scene descriptions
 * (bank cards, medication labels). Only the extracted user query and the
 * model's spoken answer are exported.
 */
data class InteractionLogRow(
    val id: String,
    val lang: String,
    val query: String,
    val answer: String,
    val previous: String?,
    val ts: Long,
    val source: String,
) {
    /** JSON string with proper escaping; fields ordered for grep-ability. */
    fun toJson(): String {
        val sb = StringBuilder(128)
        sb.append('{')
        sb.append("\"id\": ").append(quote(id)).append(", ")
        sb.append("\"lang\": ").append(quote(lang)).append(", ")
        sb.append("\"query\": ").append(quote(query)).append(", ")
        sb.append("\"previous\": ").append(if (previous == null) "null" else quote(previous)).append(", ")
        sb.append("\"answer\": ").append(quote(answer)).append(", ")
        sb.append("\"ts\": ").append(ts).append(", ")
        sb.append("\"source\": ").append(quote(source))
        sb.append('}')
        return sb.toString()
    }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 8)
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch.code < 0x20 -> sb.append("\\u").append(String.format("%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    companion object {
        /**
         * Marker the prompt builder wraps the raw spoken query in
         * (DynamicPromptBuilder: "Task: The user asked: <query>").
         */
        const val QUERY_MARKER = "Task: The user asked: "

        /**
         * Extract the raw spoken query from a built VLM prompt. Returns
         * null when the prompt carries no query marker (e.g. gesture
         * scene-describes) — such rows are useless for router distillation
         * and are deliberately skipped by the exporter.
         */
        fun extractQuery(builtPrompt: String): String? {
            val idx = builtPrompt.indexOf(QUERY_MARKER)
            if (idx < 0) return null
            val start = idx + QUERY_MARKER.length
            if (start > builtPrompt.length) return null
            val end = builtPrompt.indexOf('\n', start)
                .let { if (it < 0) builtPrompt.length else it }
            val query = builtPrompt.substring(start, end).trim()
            return query.ifEmpty { null }
        }
    }
}
