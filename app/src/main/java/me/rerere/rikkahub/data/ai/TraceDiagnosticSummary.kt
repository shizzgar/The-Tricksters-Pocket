package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.*

/** No free-form data, titles, identifiers, hashes, messages, URLs or source names enter this export. */
internal fun diagnosticSummary(page: TracePage): String {
    val kinds = setOf("model", "tool", "subagent", "compaction", "task", "conversation", "event")
    val phases = setOf("start", "end", "update", "event")
    val states = setOf("cancelled", "error", "incomplete", "paused", "completed", "running", "recorded")
    val start = page.records.minOfOrNull { it.timestamp } ?: 0
    val body = buildJsonObject {
        put("format", "pocket-diagnostic-summary")
        put("version", 1)
        put("scope", "current_chat_recent_events")
        put("content_included", false)
        put("events_in_trace", page.total)
        put("events_in_export", page.records.size)
        put("truncated", page.total > page.records.size)
        put("integrity_warning", page.error != null)
        put("events", JsonArray(page.records.asReversed().map { record -> buildJsonObject {
            val summary = record.summary
            put("offset_ms", (record.timestamp - start).coerceAtLeast(0))
            put("kind", summary?.kind?.takeIf { it in kinds } ?: "event")
            put("phase", summary?.phase?.takeIf { it in phases } ?: "event")
            put("state", summary?.state?.takeIf { it in states } ?: "recorded")
            summary?.elapsedMs?.let { put("elapsed_ms", it.coerceAtLeast(0)) }
            summary?.firstContentMs?.let { put("first_content_ms", it.coerceAtLeast(0)) }
            summary?.receivingMs?.let { put("receiving_ms", it.coerceAtLeast(0)) }
            summary?.inputTokens?.let { put("input_tokens", it.coerceAtLeast(0)) }
            summary?.outputTokens?.let { put("output_tokens", it.coerceAtLeast(0)) }
        } }))
    }
    return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), body)
}
