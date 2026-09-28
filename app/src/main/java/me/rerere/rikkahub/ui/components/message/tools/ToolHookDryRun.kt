package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.hooks.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ToolHook
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.ProjectRepository
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

internal data class ToolHookPastCall(val key: String, val tool: UIMessagePart.Tool, val event: ToolHookEvent)

/** Read-only event reconstruction. An observation uses only launch information preceding it. */
internal fun toolHookPastCalls(tools: List<UIMessagePart.Tool>): List<ToolHookPastCall> {
    val origins = mutableMapOf<String, ToolHookJobOrigin>()
    return buildList {
        tools.filter { it.isExecuted && it.executionStartedAt != null }.forEach { tool ->
            val arguments = runCatching { tool.inputAsJson() }.getOrNull()
            val observations = ToolHookRuntime.observations(tool.output)
            observations.forEachIndexed { index, observation ->
                val initial = ToolHookEventNormalizer.normalize(tool.toolName, arguments, observation, tool.toolCallId)
                val confirmedLaunch = ToolHookRuntime.confirmsJobLaunch(tool.toolName, observation)
                if (initial.jobId != null && confirmedLaunch) {
                    origins[initial.jobId] = ToolHookJobOrigin(tool.toolName, arguments, tool.toolCallId)
                }
                val origin = if (ToolHookRuntime.isJobLaunchTool(tool.toolName) && !confirmedLaunch) null
                    else initial.jobId?.let(origins::get)
                val event = if (origin == null) initial else ToolHookEventNormalizer.normalize(
                    tool.toolName, arguments, observation, tool.toolCallId, origin,
                )
                add(ToolHookPastCall("${tool.toolCallId}:$index", tool, event))
            }
        }
    }
}

/** This entry point reads stored calls and installed instructions; it has no dispatcher dependency. */
@Composable
fun ToolHookDryRunSheet(rule: ToolHook, onDismiss: () -> Unit, currentConversationId: Uuid? = null) {
    val repository = koinInject<ConversationRepository>()
    val projects = koinInject<ProjectRepository>()
    val store = koinInject<SettingsStore>()
    val skills = koinInject<SkillManager>()
    val settings by store.settingsFlow.collectAsState()
    var assistantId by remember { mutableStateOf(settings.assistantId) }
    var conversationId by remember(currentConversationId) { mutableStateOf(currentConversationId) }
    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var conversation by remember { mutableStateOf<Conversation?>(null) }
    var selectedCallKey by remember(conversationId) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var scopeContext by remember { mutableStateOf<ToolHookScopeContext?>(null) }
    var scopeUnavailable by remember { mutableStateOf(false) }
    var resolution by remember(rule, conversationId, selectedCallKey) { mutableStateOf<HookContentResolution?>(null) }

    LaunchedEffect(currentConversationId) {
        currentConversationId?.let { id ->
            try { repository.getConversationById(id)?.let { assistantId = it.assistantId } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message.orEmpty() }
        }
    }
    LaunchedEffect(assistantId) {
        loading = true
        error = null
        try {
            conversations = repository.getConversationsOfAssistant(assistantId).first()
                .sortedByDescending { it.updateAt }.take(20)
            if (conversations.none { it.id == conversationId }) {
                val requested = currentConversationId?.let { repository.getConversationById(it) }
                    ?.takeIf { it.assistantId == assistantId }
                if (requested != null) conversations = (listOf(requested) + conversations).distinctBy { it.id }
                conversationId = requested?.id ?: conversations.firstOrNull()?.id
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message.orEmpty() }
        finally { loading = false }
    }
    LaunchedEffect(conversationId, settings.assistants) {
        conversation = null
        scopeContext = null
        scopeUnavailable = false
        val id = conversationId ?: return@LaunchedEffect
        loading = true
        try {
            val loaded = repository.getConversationById(id) ?: return@LaunchedEffect
            conversation = loaded
            val parents = mutableListOf<Conversation>()
            val seen = mutableSetOf(id)
            var parentId = loaded.parentConversationId
            while (parentId != null && seen.add(parentId) && parents.size < 32) {
                val parent = repository.getConversationById(parentId)
                if (parent == null) { scopeUnavailable = true; break }
                parents += parent
                parentId = parent.parentConversationId
            }
            if (parentId != null && (parents.size >= 32 || parentId in seen)) scopeUnavailable = true
            suspend fun workspace(chat: Conversation): String? {
                val assistant = settings.assistants.find { it.id == chat.assistantId } ?: Assistant(id = chat.assistantId)
                return projects.effectiveAssistant(chat.id, assistant, settings).workspaceId?.toString()
            }
            scopeContext = ToolHookScopeContext(
                assistantId = loaded.assistantId, workspaceId = workspace(loaded), conversationId = id,
                isSubagent = loaded.parentConversationId != null || loaded.subAgentRunId != null,
                parentAssistantIds = parents.map { it.assistantId }.toSet(),
                parentWorkspaceIds = parents.mapNotNull { workspace(it) }.toSet(),
                ancestorConversationIds = parents.map { it.id }.toSet(),
            )
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message.orEmpty() }
        finally { loading = false }
    }
    val calls = remember(conversation) {
        toolHookPastCalls(conversation?.currentMessages.orEmpty().flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>())
            .takeLast(200).reversed()
    }
    val selectedCall = calls.find { it.key == selectedCallKey } ?: calls.firstOrNull()
    val evaluation = remember(rule, selectedCall, scopeContext) {
        val scope = scopeContext
        if (selectedCall != null && scope != null) ToolHookMatcher.evaluate(rule.copy(enabled = true), selectedCall.event, scope) else null
    }
    LaunchedEffect(rule, selectedCall?.key) {
        resolution = withContext(Dispatchers.IO) { ToolHookContentResolver(skills).resolve(rule.action) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(.88f).testTag("tool-hook-dry-run"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.hook_dry_run_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.hook_dry_run_rule, rule.name), style = MaterialTheme.typography.titleSmall)
            }
            if (!rule.enabled) item { Text(stringResource(R.string.hook_dry_run_disabled), color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall) }
            item { Text(stringResource(R.string.hook_dry_run_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                HookHistoryChoice(
                    label = stringResource(R.string.hook_dry_run_assistant),
                    selected = settings.assistants.find { it.id == assistantId }?.name.orEmpty(),
                    entries = settings.assistants.map { it.id.toString() to it.name }, tag = "hook-dry-run-assistant",
                    onSelect = { assistantId = Uuid.parse(it); conversationId = null },
                )
            }
            item {
                val untitled = stringResource(R.string.hook_dry_run_untitled)
                HookHistoryChoice(
                    label = stringResource(R.string.hook_dry_run_conversation),
                    selected = conversations.find { it.id == conversationId }?.title?.ifBlank { untitled }.orEmpty(),
                    entries = conversations.map { it.id.toString() to it.title.ifBlank { untitled } },
                    tag = "hook-dry-run-conversation", onSelect = { conversationId = Uuid.parse(it) },
                )
            }
            if (calls.isNotEmpty()) item {
                fun ToolHookPastCall.label() = "${tool.toolName} · ${event.jobId ?: tool.toolCallId.takeLast(8)}" +
                    event.command.takeIf { it.isNotBlank() }?.let { " · ${it.take(120)}" }.orEmpty()
                HookHistoryChoice(
                    label = stringResource(R.string.hook_dry_run_call), selected = selectedCall?.label().orEmpty(),
                    entries = calls.map { it.key to it.label() }, tag = "hook-dry-run-call", onSelect = { selectedCallKey = it },
                )
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { detail -> item { Text(stringResource(R.string.hook_dry_run_error, detail), color = MaterialTheme.colorScheme.error) } }
            if (!loading && conversations.isEmpty()) item { Text(stringResource(R.string.hook_dry_run_no_conversations)) }
            else if (!loading && calls.isEmpty()) item { Text(stringResource(R.string.hook_dry_run_no_calls)) }
            if (scopeUnavailable) item { Text(stringResource(R.string.hook_dry_run_scope_missing), color = MaterialTheme.colorScheme.error) }
            if (selectedCall != null && evaluation != null) item {
                ToolHookDryRunResult(selectedCall.event, evaluation, resolution, scopeUnavailable)
            }
        }
    }
}

@Composable
private fun HookHistoryChoice(label: String, selected: String, entries: List<Pair<String, String>>, tag: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = entries.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag(tag)) {
                Text(selected.ifBlank { stringResource(R.string.hook_dry_run_choose) }, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                entries.forEach { (key, title) ->
                    DropdownMenuItem(text = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.testTag("$tag-$key"), onClick = { expanded = false; onSelect(key) })
                }
            }
        }
    }
}

@Composable
internal fun ToolHookDryRunResult(event: ToolHookEvent, evaluation: ToolHookEvaluation, resolution: HookContentResolution?, scopeUnavailable: Boolean = false) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("tool-hook-dry-run-result")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(if (evaluation.matched && !scopeUnavailable) R.string.hook_dry_run_match else R.string.hook_dry_run_no_match),
                style = MaterialTheme.typography.titleMedium,
                color = if (evaluation.matched && !scopeUnavailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            evaluation.reasons.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            Text(stringResource(R.string.hook_dry_run_normalized), style = MaterialTheme.typography.labelLarge)
            Text(event.toolName, fontFamily = FontFamily.Monospace)
            event.originToolName?.let { Text(stringResource(R.string.hook_dry_run_origin, it), style = MaterialTheme.typography.bodySmall) }
            if (event.command.isNotBlank()) SelectionContainer {
                Text(event.command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.hook_dry_run_exit, event.exitCode?.toString() ?: stringResource(R.string.hook_dry_run_unknown)),
                style = MaterialTheme.typography.bodySmall)
            if (evaluation.matched && !scopeUnavailable) when (resolution) {
                is HookContentResolution.Resolved -> {
                    resolution.source?.let { Text(stringResource(R.string.hook_event_source, it), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall) }
                    ToolHookPromptPreview(resolution.text)
                }
                is HookContentResolution.Error -> {
                    Text(stringResource(R.string.hook_dry_run_unavailable), color = MaterialTheme.colorScheme.error)
                    Text(resolution.reason, style = MaterialTheme.typography.bodySmall)
                }
                null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}
