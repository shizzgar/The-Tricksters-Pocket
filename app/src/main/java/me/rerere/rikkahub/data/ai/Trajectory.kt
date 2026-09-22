package me.rerere.rikkahub.data.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Small authenticated index metadata: opening a trace never needs to inflate every prompt. */
@Serializable
data class TraceSummary(
    val operation: String = "",
    val kind: String = "event",
    val title: String = "",
    val phase: String = "event",
    val state: String = "recorded",
    val parent: String? = null,
    val preview: String = "",
    val elapsedMs: Long? = null,
    val firstContentMs: Long? = null,
    val receivingMs: Long? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val childConversation: String? = null,
)

private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
private fun JsonObject.flag(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull
private fun parseObject(value: String?) = value?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

internal fun traceSummary(source: String, data: JsonObject): TraceSummary {
    val kind = if (source == "tool.checkpoint") "event" else source.substringBefore('.')
    val operation = when (kind) {
        "model" -> data.text("request_id")
        "tool" -> data.text("tool_call_id")
        "subagent", "task" -> data.text("run_id")
        "compaction" -> data.text("operation_id")
        else -> null
    }.orEmpty()
    val compaction = parseObject((data["event"] as? JsonObject)?.text("input"))
    val phase = when {
        source in setOf("model.request", "tool.started", "subagent.started", "task.started", "task.resumed") -> "start"
        source in setOf("model.response", "tool.result", "subagent.result", "task.cancelled", "task.deadline", "task.failed") -> "end"
        source == "task.checkpoint" && data.flag("continuing") == false -> "end"
        source == "compaction.event" -> if (compaction?.text("state") in setOf("completed", "failed", "cancelled")) "end" else "update"
        else -> "update"
    }
    val outputError = (data["output"] as? JsonArray)?.any { part ->
        val obj = parseObject((part as? JsonObject)?.text("text"))
        obj?.get("error")?.let { it != JsonNull && it != JsonPrimitive(false) && it != JsonPrimitive("") } == true ||
            obj?.flag("success") == false || (obj?.number("exit_code") ?: 0) != 0L
    } == true
    val reason = data.text("reason").orEmpty()
    val status = data.text("status")?.lowercase()
    val state = when {
        source == "task.cancelled" || (data.text("error_type")?.endsWith("CancellationException") == true && data.text("error_type")?.contains("Timeout") != true) || status == "cancelled" || compaction?.text("state") == "cancelled" -> "cancelled"
        data.text("error_type") != null || data.text("error")?.isNotBlank() == true || outputError || status in setOf("failed", "timed_out") || compaction?.text("state") == "failed" || source in setOf("task.failed", "task.deadline") -> "error"
        source == "model.response" && data.flag("stream_finished") == false -> "incomplete"
        source == "task.checkpoint" && phase == "end" && reason != "COMPLETED" -> "paused"
        phase == "end" -> "completed"
        operation.isNotBlank() -> "running"
        else -> "recorded"
    }
    val input = parseObject(data.text("input"))
    val usage = ((data["response"] as? JsonObject)?.get("usage") as? JsonObject)
        ?: (data["chunks"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("usage") as? JsonObject }?.lastOrNull()
    val title = when (kind) {
        "model" -> data.text("model")
        "tool" -> data.text("tool") ?: source
        "subagent" -> data.text("task")?.lineSequence()?.firstOrNull()?.take(100)
        "task" -> ""
        "compaction" -> compaction?.text("phase")
        else -> source
    }.orEmpty()
    return TraceSummary(
        operation = operation, kind = kind, title = title.take(120), phase = phase, state = state,
        parent = data.text("parent_request_id"),
        preview = (input?.text("command") ?: input?.text("name") ?: data.text("error") ?: data.text("error_type") ?: reason).take(240),
        elapsedMs = data.number("elapsed_ms") ?: compaction?.number("elapsed_ms"),
        firstContentMs = data.number("first_content_ms"),
        receivingMs = data.number("receiving_ms"),
        inputTokens = data.number("prompt_tokens") ?: usage?.number("promptTokens"),
        outputTokens = data.number("completion_tokens") ?: usage?.number("completionTokens"),
        childConversation = data.text("child_conversation"),
    )
}

data class TraceEntry(val record: TraceRecord, val summary: TraceSummary)
data class TrajectoryPage(val entries: List<TraceEntry>, val total: Long, val hasEarlier: Boolean, val error: String?)

data class TraceSpan(
    val id: String,
    val kind: String,
    val title: String,
    val state: String,
    val start: Long,
    val end: Long?,
    val durationMs: Long?,
    val firstContentMs: Long?,
    val receivingMs: Long?,
    val run: String?,
    val parent: String?,
    val preview: String,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val childConversation: String?,
    val partial: Boolean,
    val entries: List<TraceEntry>,
) {
    fun matches(query: String) = query.isBlank() || listOf(title, preview, kind, state, id)
        .any { it.contains(query, ignoreCase = true) }
}

/** Correlation IDs, never adjacency, join concurrent operations. Missing endpoints stay explicit. */
internal fun buildTraceSpans(entries: List<TraceEntry>, active: Boolean): List<TraceSpan> {
    val groups = linkedMapOf<String, MutableList<TraceEntry>>()
    val owners = mutableMapOf<String, String?>()
    var run: String? = null
    entries.sortedBy { it.record.sequence }.forEach { entry ->
        val s = entry.summary
        if (entry.record.source == "tool.checkpoint" && run != null) {
            groups[run]?.add(entry)
            return@forEach
        }
        val key = if (s.operation.isBlank()) "event:${entry.record.sequence}" else "${s.kind}:${s.operation}"
        if (s.kind == "task" && s.operation.isNotBlank()) run = key
        if (key !in groups) owners[key] = run?.takeUnless { it == key }
        groups.getOrPut(key) { mutableListOf() }.add(entry)
    }
    val activeSince = entries.lastOrNull { it.summary.kind == "task" && it.summary.phase == "start" }?.record?.sequence
        ?: entries.lastOrNull { it.summary.phase == "start" }?.record?.sequence ?: Long.MAX_VALUE
    val latestRun = run
    val lastRunEnd = groups[latestRun]?.lastOrNull { it.summary.phase == "end" }?.record?.sequence ?: 0L
    return groups.map { (key, records) ->
        val first = records.first()
        val last = records.last()
        val startEvent = records.firstOrNull { it.summary.phase == "start" }
            ?: records.firstOrNull { it.summary.kind == "compaction" && it.summary.state == "running" }
        val finish = records.lastOrNull { it.summary.phase == "end" }
        val resumed = finish != null && records.any { it.summary.phase == "start" && it.record.sequence > finish.record.sequence }
        val ended = finish?.takeUnless { resumed }
        val summary = last.summary
        val measured = records.asReversed().firstNotNullOfOrNull { it.summary.elapsedMs }
        val liveSequence = records.lastOrNull { it.summary.phase == "start" }?.record?.sequence ?: first.record.sequence
        val start = startEvent?.record?.timestamp ?: (ended?.record?.timestamp?.minus(measured ?: 0) ?: first.record.timestamp)
        TraceSpan(
            id = key, kind = first.summary.kind,
            title = records.firstOrNull { it.summary.title.isNotBlank() }?.summary?.title.orEmpty(),
            state = ended?.summary?.state ?: if (first.summary.operation.isBlank()) "recorded" else if (active && liveSequence >= activeSince && liveSequence > lastRunEnd) "running" else "incomplete",
            start = start, end = ended?.record?.timestamp,
            durationMs = measured ?: ended?.record?.timestamp?.minus(start)?.coerceAtLeast(0)?.takeIf { startEvent != null },
            firstContentMs = records.asReversed().firstNotNullOfOrNull { it.summary.firstContentMs },
            receivingMs = records.asReversed().firstNotNullOfOrNull { it.summary.receivingMs },
            run = owners[key], parent = records.firstNotNullOfOrNull { it.summary.parent }?.let { "model:$it" },
            preview = records.lastOrNull { it.summary.preview.isNotBlank() }?.summary?.preview.orEmpty(),
            inputTokens = records.asReversed().firstNotNullOfOrNull { it.summary.inputTokens },
            outputTokens = records.asReversed().firstNotNullOfOrNull { it.summary.outputTokens },
            childConversation = records.firstNotNullOfOrNull { it.summary.childConversation },
            partial = startEvent == null && first.summary.operation.isNotBlank(), entries = records,
        )
    }.sortedWith(compareBy<TraceSpan> { it.start }.thenBy { it.entries.first().record.sequence })
}
