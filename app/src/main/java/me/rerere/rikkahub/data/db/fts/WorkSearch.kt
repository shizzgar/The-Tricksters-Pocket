package me.rerere.rikkahub.data.db.fts

import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.*

enum class WorkSearchKind { ALL, TEXT, TOOL, FILE }
data class WorkSearchFilter(
    val kind: WorkSearchKind = WorkSearchKind.ALL,
    val conversationIds: Set<String>? = null,
    val includeChildren: Boolean = true,
    val after: Long? = null,
    val before: Long? = null,
    val toolName: String? = null,
)

data class WorkSearchText(val kind: WorkSearchKind, val text: String, val toolName: String = "")

/** Separates tool evidence from prose. Binary payloads are never rendered into the index. */
fun extractWorkSearchText(parts: List<UIMessagePart>): List<WorkSearchText> = parts.flatMap { part ->
    when (part) {
        is UIMessagePart.Text -> listOf(WorkSearchText(WorkSearchKind.TEXT, part.text))
        is UIMessagePart.Tool -> listOf(WorkSearchText(WorkSearchKind.TOOL, part.toolName + "\n" + part.input + "\n" + part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }, part.toolName)) + extractWorkSearchText(part.output).filter { it.kind == WorkSearchKind.FILE } + fileResultText(part)
        is UIMessagePart.ToolResult -> listOf(WorkSearchText(WorkSearchKind.TOOL, "${part.toolName}\n${part.arguments}\n${part.content}", part.toolName))
        is UIMessagePart.ServerTool -> listOf(WorkSearchText(WorkSearchKind.TOOL, "${part.toolName}\n${part.input}\n${part.output}", part.toolName))
        is UIMessagePart.Document -> listOf(WorkSearchText(WorkSearchKind.FILE, "${part.fileName}\n${part.mime}\n${part.url}"))
        is UIMessagePart.Image -> listOf(WorkSearchText(WorkSearchKind.FILE, part.url.takeUnless { it.startsWith("data:") } ?: "image"))
        is UIMessagePart.Audio -> listOf(WorkSearchText(WorkSearchKind.FILE, part.url.takeUnless { it.startsWith("data:") } ?: "audio"))
        is UIMessagePart.Video -> listOf(WorkSearchText(WorkSearchKind.FILE, part.url.takeUnless { it.startsWith("data:") } ?: "video"))
        else -> emptyList()
    }
}.filter { it.text.isNotBlank() }

internal data class WorkSearchSql(val where: String, val args: List<Any>)
internal fun workSearchSql(filter: WorkSearchFilter, assistantId: String?): WorkSearchSql {
    val clauses = mutableListOf<String>()
    val args = mutableListOf<Any>()
    if (assistantId != null) {
        clauses += "EXISTS (SELECT 1 FROM conversationentity c WHERE c.id = conversation_id AND c.assistant_id = ?)"
        args += assistantId
    }
    filter.conversationIds?.let { ids ->
        if (ids.isEmpty()) clauses += "0" else {
            val placeholders = ids.joinToString(",") { "?" }
            clauses += if (filter.includeChildren) "conversation_id IN (WITH RECURSIVE family(id) AS (SELECT id FROM conversationentity WHERE id IN ($placeholders) UNION SELECT c.id FROM conversationentity c JOIN family f ON c.parent_conversation_id = f.id) SELECT id FROM family)" else "conversation_id IN ($placeholders)"
            args.addAll(ids)
        }
    }
    if (filter.kind != WorkSearchKind.ALL) { clauses += "kind = ?"; args += filter.kind.name }
    filter.after?.let { clauses += "CAST(update_at AS INTEGER) >= ?"; args += it }
    filter.before?.let { clauses += "CAST(update_at AS INTEGER) < ?"; args += it }
    filter.toolName?.takeIf { it.isNotBlank() }?.let { clauses += "tool_name = ?"; args += it }
    return WorkSearchSql(clauses.joinToString(" AND ").ifBlank { "1" }, args)
}

private fun fileResultText(tool: UIMessagePart.Tool): List<WorkSearchText> {
    val write = me.rerere.rikkahub.data.task.successfulWorkspaceFileOutput(tool)
    if (write != null) return listOf(WorkSearchText(WorkSearchKind.FILE, write.toString() + "\n" + tool.input, tool.toolName))
    if (tool.toolName != "register_artifact") return emptyList()
    val registered = tool.output.filterIsInstance<UIMessagePart.Text>().mapNotNull { runCatching { Json.parseToJsonElement(it.text) as? JsonObject }.getOrNull() }
        .firstOrNull { (it["registered"] as? JsonPrimitive)?.booleanOrNull == true && it["error"] == null } ?: return emptyList()
    return listOf(WorkSearchText(WorkSearchKind.FILE, registered.toString(), tool.toolName))
}
