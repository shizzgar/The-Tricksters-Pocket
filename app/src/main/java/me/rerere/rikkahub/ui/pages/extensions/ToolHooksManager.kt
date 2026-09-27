package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ToolHook
import me.rerere.rikkahub.data.model.ToolHookAction
import me.rerere.rikkahub.data.model.ToolHookCondition
import me.rerere.rikkahub.data.model.ToolHookScope
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.message.tools.ToolHookDryRunSheet
import org.koin.compose.koinInject
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/** Standalone content used both by the prompt library and the chat's '+' sheet. */
@Composable
fun ToolHooksManager(conversationId: Uuid? = null, modifier: Modifier = Modifier) {
    val settingsStore = koinInject<SettingsStore>()
    val skillManager = koinInject<SkillManager>()
    val workspaceRepository = koinInject<WorkspaceRepository>()
    val conversationRepository = koinInject<ConversationRepository>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val workspaces by remember(workspaceRepository) { workspaceRepository.listFlow() }.collectAsStateWithLifecycle(emptyList())
    val coroutineScope = rememberCoroutineScope()
    var editing by rememberSaveable(stateSaver = Saver<ToolHook?, String>(
        save = { it?.let { draft -> JsonInstant.encodeToString(draft) }.orEmpty() },
        restore = { raw -> raw.takeIf { it.isNotBlank() }?.let { runCatching { JsonInstant.decodeFromString<ToolHook>(it) }.getOrNull() } },
    )) { mutableStateOf<ToolHook?>(null) }
    var deleting by remember { mutableStateOf<ToolHook?>(null) }
    var preview by remember { mutableStateOf<ToolHook?>(null) }
    var skills by remember { mutableStateOf<List<SkillMetadata>>(emptyList()) }
    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var error by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(settings.assistants.map { it.id }, conversationId) {
        try {
            withContext(Dispatchers.IO) {
                val loadedSkills = skillManager.listSkills()
                val loadedChats = settings.assistants.flatMap { conversationRepository.getConversationsOfAssistant(it.id).first() }.toMutableList()
                if (conversationId != null && loadedChats.none { it.id == conversationId }) {
                    conversationRepository.getConversationById(conversationId)?.let(loadedChats::add)
                }
                loadedSkills to loadedChats.distinctBy { it.id }
            }.let { (loadedSkills, loadedChats) -> skills = loadedSkills; conversations = loadedChats }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { error = R.string.hooks_load_failed }
    }

    fun mutate(transform: (List<ToolHook>) -> List<ToolHook>, onSaved: () -> Unit = {}) {
        coroutineScope.launch {
            saving = true
            try {
                settingsStore.update { latest -> latest.copy(toolHooks = transform(latest.toolHooks)) }
                error = null
                onSaved()
            } catch (e: CancellationException) { throw e } catch (_: Exception) { error = R.string.hooks_update_failed }
            finally { saving = false }
        }
    }
    val defaultScope = if (conversationId != null) ToolHookScope(conversationIds = setOf(conversationId)) else ToolHookScope(assistantIds = setOf(settings.assistantId))
    val starterName = stringResource(R.string.hooks_jadx_name)
    val starterPrompt = stringResource(R.string.hooks_jadx_prompt)
    ToolHooksList(
        hooks = settings.toolHooks,
        modifier = modifier,
        description = stringResource(if (conversationId == null) R.string.hooks_description else R.string.hooks_chat_description),
        error = error?.let { stringResource(it) },
        onAdd = { editing = ToolHook(scope = defaultScope) },
        onStarter = {
            editing = ToolHook(name = starterName, enabled = false, scope = defaultScope,
                condition = ToolHookCondition(toolNames = setOf("termux_run_command"), commandExecutable = "jadx"),
                action = ToolHookAction(prompt = starterPrompt))
        },
        onEdit = { editing = it },
        onToggle = { hook, enabled -> mutate({ rows -> rows.map { if (it.id == hook.id) it.copy(enabled = enabled) else it } }) },
        onDelete = { deleting = it },
    )
    editing?.let { draft ->
        ToolHookEditSheet(draft, { editing = it }, onSave = {
            if (toolHookEditorErrors(draft).isEmpty()) mutate({ hooks ->
                if (hooks.any { it.id == draft.id }) hooks.map { if (it.id == draft.id) draft else it } else hooks + draft
            }, onSaved = { if (editing?.id == draft.id) editing = null })
        }, onDismiss = { editing = null }, onPreview = { preview = it }, assistants = settings.assistants,
            workspaces = workspaces, conversations = conversations, skills = skills, error = error?.let { stringResource(it) }, saving = saving)
    }
    preview?.let { ToolHookDryRunSheet(it, onDismiss = { preview = null }, currentConversationId = conversationId) }
    deleting?.let { hook ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.hooks_delete_title)) }, text = { Text(stringResource(R.string.hooks_delete_message, hook.name)) },
            confirmButton = { TextButton(onClick = { mutate({ rows -> rows.filterNot { it.id == hook.id } }, { deleting = null }) }) { Text(stringResource(R.string.prompt_page_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.prompt_page_cancel)) } })
    }
}

@Composable
internal fun ToolHooksList(
    hooks: List<ToolHook>,
    onAdd: () -> Unit,
    onStarter: () -> Unit,
    onEdit: (ToolHook) -> Unit,
    onToggle: (ToolHook, Boolean) -> Unit,
    onDelete: (ToolHook) -> Unit,
    modifier: Modifier = Modifier,
    description: String = stringResource(R.string.hooks_description),
    error: String? = null,
) {
    LazyColumn(modifier.fillMaxSize().testTag("hooks-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.hooks_title), style = MaterialTheme.typography.headlineSmall)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onAdd, modifier = Modifier.weight(1f).testTag("hook-add")) { Icon(HugeIcons.Add01, null); Text(stringResource(R.string.hooks_add)) }
                    OutlinedButton(onClick = onStarter, modifier = Modifier.testTag("hook-starter")) { Text(stringResource(R.string.hooks_jadx_starter)) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        if (hooks.isEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.hooks_empty), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.hooks_empty_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(hooks, key = { it.id.toString() }) { hook ->
            Card(onClick = { onEdit(hook) }, modifier = Modifier.fillMaxWidth().testTag("hook-card-${hook.id}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(hook.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val toggleLabel = stringResource(R.string.hooks_enable_named, hook.name)
                        Switch(hook.enabled, { onToggle(hook, it) }, modifier = Modifier.semantics { contentDescription = toggleLabel })
                    }
                    Text(hookOutcomeLabel(hook.condition.outcome), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(hook.condition.toolNames.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: stringResource(R.string.hooks_any_tool), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (hook.condition.commandExecutable.isNotBlank()) Text(hook.condition.commandExecutable, style = MaterialTheme.typography.bodyMedium)
                    Text(hookScopeSummary(hook.scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = { onEdit(hook) }) { Icon(HugeIcons.Tools, stringResource(R.string.prompt_page_edit)) }
                        IconButton(onClick = { onDelete(hook) }) { Icon(HugeIcons.Delete01, stringResource(R.string.prompt_page_delete)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun hookScopeSummary(scope: ToolHookScope): String {
    val base = if (scope.global) stringResource(R.string.hooks_global) else buildList {
        if (scope.assistantIds.isNotEmpty()) add("${stringResource(R.string.hooks_assistants)}: ${scope.assistantIds.size}")
        if (scope.workspaceIds.isNotEmpty()) add("${stringResource(R.string.hooks_workspaces)}: ${scope.workspaceIds.size}")
        if (scope.conversationIds.isNotEmpty()) add("${stringResource(R.string.hooks_chats)}: ${scope.conversationIds.size}")
    }.joinToString(" · ").ifBlank { stringResource(R.string.hooks_scope_required) }
    return if (scope.inheritToSubagents) "$base · ${stringResource(R.string.hooks_subagents)}" else base
}
