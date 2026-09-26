package me.rerere.rikkahub.costguards

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

/**
 * Read-only task usage. The caller is explicitly bound at tool construction, never
 * inferred from whichever assistant the user happens to have open on screen.
 */

private fun errEnv(error: String, detail: String): List<UIMessagePart> {
    val obj = buildJsonObject {
        put("error", error)
        put("detail", detail)
    }
    return listOf(UIMessagePart.Text(obj.toString()))
}

fun checkTokenUsageTool(
    settingsStore: SettingsStore,
    conversationRepo: ConversationRepository,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
): Tool = Tool(
    name = "check_token_usage",
    description = """
        Read measured input/output token usage for this task, including its parent and
        descendant chats and stored alternate answers. Compare with the root assistant's
        soft/hard caps. WARN means wrap up; OVER_HARD means the configured measured budget
        is exhausted. Missing provider usage is reported separately, never as measured zero.
        Omit conversation_id to use the invoking chat. Read-only.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("conversation_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Conversation UUID; omit to use the invoking chat.")
                })
            },
            required = emptyList(),
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val rawConvId = params["conversation_id"]?.jsonPrimitive?.contentOrNull ?: invocationContext.callerConversationId
        val settings = settingsStore.settingsFlow.first()
        val convId = rawConvId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: return@Tool errEnv("invalid_conversation", "A valid conversation_id or invoking chat is required")
        val conv = conversationRepo.getConversationById(convId) ?: return@Tool errEnv(
            "no_conversation",
            "no conversation found to compute token usage against"
        )
        val root = TokenBudgetTracker.taskRoot(conv, conversationRepo)
        val assistant = settings.getAssistantById(root.assistantId)
        val snapshot = TokenBudgetTracker.taskSnapshot(
            conversation = conv,
            conversationRepo = conversationRepo,
            softCap = assistant?.tokenBudgetSoftCap,
            hardCap = assistant?.tokenBudgetHardCap,
        )
        val payload = buildJsonObject {
            put("conversation_id", conv.id.toString())
            put("task_conversation_id", root.id.toString())
            put("conversation_count", snapshot.conversationCount)
            put("unmeasured_messages", snapshot.totals.unmeasuredMessages)
            put("usage_complete", snapshot.totals.unmeasuredMessages == 0)
            put("input_tokens", snapshot.totals.inputTokens)
            put("output_tokens", snapshot.totals.outputTokens)
            put("total_tokens", snapshot.totals.totalTokens)
            put("per_message_max", snapshot.totals.perMessageMax)
            put("message_count", snapshot.totals.messageCount)
            if (snapshot.softCap != null) put("soft_cap", snapshot.softCap)
            if (snapshot.hardCap != null) put("hard_cap", snapshot.hardCap)
            put("status", snapshot.status.name)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)
