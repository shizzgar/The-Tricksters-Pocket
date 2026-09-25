package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Message02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.subagent.SubAgentRegistry
import me.rerere.rikkahub.data.agentrun.AgentRunRepository
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

internal val SubAgentToolUIs: List<ToolUIRenderer> = listOf("subagent_dispatch", "subagent_get", "subagent_list", "subagent_cancel", "subagent_send")
    .map { SubAgentToolUI(it) }

internal fun subAgentRunIds(arguments: JsonElement, output: JsonElement?): Set<String> = buildSet {
    arguments.getStringContent("id")?.let(::add)
    output.getStringContent("id")?.let(::add)
    (output as? JsonObject)?.get("runs")?.let { it as? JsonArray }?.forEach { item ->
        item.getStringContent("id")?.let(::add)
    }
}

@Composable
private fun childChats(context: ToolUIContext): List<Conversation> {
    val repository = koinInject<ConversationRepository>()
    val parentId = remember(context.conversationId) { context.conversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() } }
    val children by produceState(emptyList<Conversation>(), parentId) {
        if (parentId != null) repository.observeChildConversations(parentId).collect { value = it }
    }
    val ids = remember(context.arguments, context.content) { subAgentRunIds(context.arguments, context.content) }
    return children.filter {
        it.subAgentRunId in ids || (context.tool.toolName == "subagent_dispatch" && it.parentToolCallId == context.tool.toolCallId)
    }
}

@Composable
internal fun childRunStatuses(): Map<String, String> {
    val registry = koinInject<SubAgentRegistry>()
    val ledger = koinInject<AgentRunRepository>()
    val service = koinInject<me.rerere.rikkahub.service.ChatService>()
    val jobs by remember(service) { service.getConversationJobs() }.collectAsState(emptyMap())
    val live by registry.runs.collectAsState()
    val recent by remember(ledger) { ledger.observeRecent(1000) }.collectAsState(emptyList())
    return remember(live, recent, jobs) {
        recent.filter { it.kind == "subagent" }.associate { it.domainId to it.status } +
            live.mapValues { it.value.status.name } + jobs.keys.associate { it.toString() to "RUNNING" }
    }
}

@Composable
internal fun ChildChatButton(conversation: Conversation, onOpened: () -> Unit = {}) {
    val navigator = LocalNavController.current
    val repository = koinInject<ConversationRepository>()
    val scope = rememberCoroutineScope()
    var missing by remember(conversation.id) { mutableStateOf(false) }
    TextButton(modifier = Modifier.testTag("open-child-${conversation.id}"), enabled = !missing, onClick = {
        scope.launch {
            if (repository.getConversationById(conversation.id) == null) missing = true
            else {
                onOpened()
                navigator.navigate(Screen.Chat(id = conversation.id.toString())) { launchSingleTop = true }
            }
        }
    }) {
        Icon(HugeIcons.Message02, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (missing) R.string.pocket_chat_missing else R.string.pocket_open_chat))
    }
}

@Composable
internal fun ChildChatCard(conversation: Conversation, status: String? = null, assistant: Assistant? = null, action: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (assistant != null) UIAvatar(assistant.name, assistant.avatar, Modifier.size(44.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (assistant != null) Text(assistant.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(conversation.title.removePrefix("[Sub-agent] "), style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(subAgentStatusLabel(status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            action()
        }
    }
}

@Composable
internal fun subAgentStatusLabel(status: String?): String = stringResource(when (status?.lowercase()) {
    "pending", "queued" -> R.string.pocket_agent_queued
    "running" -> R.string.pocket_agent_running
    "succeeded" -> R.string.pocket_agent_done
    "failed", "timed_out", "process_lost" -> R.string.pocket_agent_stopped
    "cancelled" -> R.string.pocket_agent_cancelled
    else -> R.string.pocket_agent_saved
})

private class SubAgentToolUI(override val toolName: String) : ToolUIRenderer {
    override fun icon(context: ToolUIContext) = HugeIcons.Message02
    override fun hasSummary(context: ToolUIContext) = true
    @Composable override fun title(context: ToolUIContext) = stringResource(R.string.pocket_subagent_title,
        context.arguments.getStringContent("agent") ?: context.arguments.getStringContent("label") ?: toolName.removePrefix("subagent_"))

    @Composable override fun Summary(context: ToolUIContext) {
        val chats = childChats(context)
        val statuses = childRunStatuses()
        if (chats.isEmpty()) {
            Text(context.content.getStringContent("error") ?: context.arguments.getStringContent("task").orEmpty(),
                maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        chats.take(3).forEach { child ->
            ChildChatCard(child, statuses[child.subAgentRunId] ?: context.content.getStringContent("status"), LocalSettings.current.getAssistantById(child.assistantId)) {
                ChildChatButton(child)
            }
        }
    }

    @Composable override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val chats = childChats(context)
        val statuses = childRunStatuses()
        var raw by remember(context.tool.toolCallId) { mutableStateOf(false) }
        if (raw) { DefaultToolPreview(context) { TextButton(onClick = { raw = false }) { Text(stringResource(R.string.pocket_readable)) } }; return }
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.85f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(title(context), style = MaterialTheme.typography.titleLarge) }
            item { Text(stringResource(R.string.pocket_child_chat_hint), style = MaterialTheme.typography.bodyMedium) }
            items(chats, key = { it.id.toString() }) { child ->
                ChildChatCard(child, statuses[child.subAgentRunId] ?: context.content.getStringContent("status"), LocalSettings.current.getAssistantById(child.assistantId)) {
                    ChildChatButton(child, onDismissRequest)
                }
            }
            context.arguments.getStringContent("task")?.let { task -> item { MarkdownBlock(task.take(30000)) } }
            (context.content.getStringContent("latest_reply") ?: context.content.getStringContent("result"))?.let { result -> item { MarkdownBlock(result.take(30000)) } }
            context.content.getStringContent("error")?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            item { TextButton(onClick = { raw = true }) { Text(stringResource(R.string.pocket_raw_response)) } }
        }
    }
}
