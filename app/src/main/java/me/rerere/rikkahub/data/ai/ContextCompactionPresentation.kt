package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.Job
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationCompaction
import kotlin.uuid.Uuid

/**
 * Presentation-only representation of a manual or automatic context compaction.
 *
 * The summary remains in [ConversationCompaction] as the request-context replacement. This
 * synthetic tool is attached to the assistant message that triggered compaction only so the
 * chat UI can explain what happened. It is always stripped before a request or a later
 * compaction source is built.
 */
internal object ContextCompactionPresentation {
    const val TOOL_NAME = "context_compaction"
    private const val DISPLAY_ONLY_METADATA_KEY = "rikkahub_display_only_context_compaction"
    val runtimeId: String = Uuid.random().toString()
    private val operations = ConcurrentHashMap<String, Job>()

    fun register(id: String, job: Job) { operations[id] = job }
    fun unregister(id: String) { operations.remove(id) }
    fun canCancel(id: String): Boolean = operations[id]?.isActive == true
    fun cancel(id: String) { operations[id]?.cancel() }

    fun startTool(
        isAuto: Boolean, startedAt: Long, startedElapsed: Long, sourceTokens: Int, targetTokens: Int,
    ): UIMessagePart.Tool = UIMessagePart.Tool(
        toolCallId = "context_compaction_${Uuid.random()}",
        toolName = TOOL_NAME,
        executionStartedAt = startedAt,
        input = buildJsonObject {
            put("mode", if (isAuto) "automatic" else "manual")
            put("state", "running")
            put("phase", "preparing")
            put("runtime_id", runtimeId)
            put("started_at_ms", startedAt)
            put("started_elapsed_ms", startedElapsed)
            put("source_token_estimate", sourceTokens)
            put("target_tokens", targetTokens)
        }.toString(),
        metadata = buildJsonObject { put(DISPLAY_ONLY_METADATA_KEY, true) },
    )

    fun update(tool: UIMessagePart.Tool, fields: JsonObject, output: String? = null): UIMessagePart.Tool = tool.copy(
        input = buildJsonObject {
            (runCatching { Json.parseToJsonElement(tool.input) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap()))
                .forEach { (key, value) -> put(key, value) }
            fields.forEach { (key, value) -> put(key, value) }
        }.toString(),
        output = output?.let { listOf(UIMessagePart.Text(it)) } ?: tool.output,
    )

    fun completeTool(tool: UIMessagePart.Tool, compaction: ConversationCompaction, elapsedMs: Long): UIMessagePart.Tool =
        update(tool, buildJsonObject {
            Json.parseToJsonElement(createTool(compaction).input).jsonObject.forEach { (key, value) -> put(key, value) }
            put("state", "completed")
            put("elapsed_ms", elapsedMs.coerceAtLeast(0))
            put("finished_at_ms", compaction.createdAt.toEpochMilli())
        }, compaction.summary)

    fun isRunning(tool: UIMessagePart.Tool): Boolean = isDisplayTool(tool) &&
        runCatching {
            val input = Json.parseToJsonElement(tool.input).jsonObject
            input["state"]?.jsonPrimitive?.contentOrNull == "running" &&
                input["runtime_id"]?.jsonPrimitive?.contentOrNull == runtimeId
        }.getOrDefault(false)

    fun hasAutomaticDisplayTool(message: UIMessage): Boolean = message.parts.any { part ->
        isDisplayTool(part) && runCatching {
            Json.parseToJsonElement((part as UIMessagePart.Tool).input).jsonObject["mode"]?.jsonPrimitive?.contentOrNull != "manual"
        }.getOrDefault(false)
    }

    fun createTool(compaction: ConversationCompaction): UIMessagePart.Tool = UIMessagePart.Tool(
        toolCallId = "context_compaction_${compaction.createdAt.toEpochMilli()}_${compaction.sourceEndNodeId}",
        toolName = TOOL_NAME,
        input = buildJsonObject {
            put("mode", JsonPrimitive(if (compaction.isAuto) "automatic" else "manual"))
            put("state", JsonPrimitive("completed"))
            put("source_token_estimate", JsonPrimitive(compaction.sourceTokenEstimate))
            put("summary_token_estimate", JsonPrimitive(ContextCompactionPlanner.estimateTokens(compaction.summary)))
            put("original_history_available", JsonPrimitive(true))
            put("summary_model_id", JsonPrimitive(compaction.summaryModelId.toString()))
            put(
                "retained_raw_tool_calls",
                JsonPrimitive(ContextCompactionPlanner.retainedRawToolCallCount(compaction.summary)),
            )
        }.toString(),
        output = listOf(UIMessagePart.Text(compaction.summary)),
        metadata = buildJsonObject {
            put(DISPLAY_ONLY_METADATA_KEY, JsonPrimitive(true))
        },
    )

    fun isDisplayTool(part: UIMessagePart): Boolean =
        part is UIMessagePart.Tool &&
            part.toolName == TOOL_NAME &&
            part.metadata?.get(DISPLAY_ONLY_METADATA_KEY)?.jsonPrimitive?.booleanOrNull == true

    fun hasDisplayTool(message: UIMessage): Boolean = message.parts.any(::isDisplayTool)

    /** Removes UI-only compaction cards before messages become model input. */
    fun stripDisplayTools(messages: List<UIMessage>): List<UIMessage> = messages.map { message ->
        val requestParts = message.parts.filterNot(::isDisplayTool)
        if (requestParts == message.parts) message else message.copy(parts = requestParts)
    }

    /**
     * Adds the card to the assistant message that immediately preceded the compaction. The
     * operation is idempotent for a specific generated card, so a streaming state update cannot
     * add duplicate cards.
     */
    fun attachToMessage(
        conversation: Conversation,
        messageId: Uuid,
        tool: UIMessagePart.Tool,
    ): Conversation {
        var changed = false
        val updatedNodes = conversation.messageNodes.map { node ->
            val updatedMessages = node.messages.map { message ->
                if (message.id != messageId) {
                    message
                } else {
                    val index = message.parts.indexOfFirst { it is UIMessagePart.Tool && it.toolCallId == tool.toolCallId }
                    val parts = if (index < 0) message.parts + tool else message.parts.toMutableList().apply { this[index] = tool }
                    if (parts != message.parts) changed = true
                    message.copy(parts = parts)
                }
            }
            if (updatedMessages == node.messages) node else node.copy(messages = updatedMessages)
        }
        return if (changed) conversation.copy(messageNodes = updatedNodes) else conversation
    }

    /**
     * Generation requests intentionally omit presentation tools. Preserve an already attached
     * card while copying their latest raw message snapshot back into the conversation.
     */
    fun preserveDisplayTools(previous: UIMessage, replacement: UIMessage): UIMessage {
        /**
         * [replacement] is the request-side streaming snapshot, so it intentionally omits
         * display-only tools. Do not append the cards to its tail: the model can append more
         * reasoning/tool parts to the same assistant message after compaction, which would make
         * the compaction card move to the bottom on every stream update. Record each card's
         * position among the real parts and restore it at that boundary instead.
         */
        val displayEntries = previous.parts.mapIndexedNotNull { index, part ->
            if (!isDisplayTool(part)) return@mapIndexedNotNull null
            index to previous.parts.take(index).count { !isDisplayTool(it) }
        }
        if (displayEntries.isEmpty()) return replacement

        val replacementParts = replacement.parts.filterNot(::isDisplayTool)
        val cardsByRawIndex = displayEntries
            .groupBy { (_, rawIndex) -> rawIndex.coerceAtMost(replacementParts.size) }
            .mapValues { (_, entries) -> entries.map { (index, _) -> previous.parts[index] } }
        val restoredParts = buildList {
            replacementParts.forEachIndexed { rawIndex, part ->
                cardsByRawIndex[rawIndex]?.let(::addAll)
                add(part)
            }
            cardsByRawIndex[replacementParts.size]?.let(::addAll)
        }
        return replacement.copy(parts = restoredParts)
    }
}
