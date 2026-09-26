package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.AgentToolPolicy
import me.rerere.rikkahub.data.ai.tools.termuxContext
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.ai.tools.filterLocalTools
import me.rerere.rikkahub.data.model.Assistant
import org.koin.compose.koinInject

@Composable
internal fun AssistantToolAccess(assistant: Assistant, onUpdate: ((Assistant) -> Assistant) -> Unit) {
    val factory = koinInject<LocalTools>()
    val workspaces = koinInject<WorkspaceRepository>()
    var open by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf<List<Tool>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(open, assistant.id, assistant.localTools, assistant.enabledSkills, assistant.workspaceId) {
        if (open) {
            loading = true
            failed = false
            try {
                catalog = withContext(Dispatchers.IO) {
                    factory.getTools(assistant.localTools, ToolInvocationContext(callerAssistantId = assistant.id.toString(),
                        termuxWorkspace = assistant.workspaceId?.let { workspaces.getById(it.toString()) }?.termuxContext()), includeDisabled = true)
                        .sortedBy { it.name }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) { failed = true
            } finally { loading = false }
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.tool_access_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.tool_access_description), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { open = true }) { Text(stringResource(R.string.tool_access_configure, assistant.disabledLocalTools.size)) }
        }
    }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            ToolAccessList(catalog, assistant.disabledLocalTools, loading, failed, readOnly = assistant.readOnlyTools, onToggle = { name, enabled ->
                onUpdate { current -> current.copy(disabledLocalTools = if (enabled) current.disabledLocalTools - name else current.disabledLocalTools + name) }
            })
        }
    }
}

@Composable
internal fun ToolAccessList(
    tools: List<Tool>, disabled: Set<String>, loading: Boolean = false, failed: Boolean = false,
    readOnly: Boolean = false,
    onToggle: (String, Boolean) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val active = remember(tools, disabled, readOnly) {
        filterLocalTools(tools, disabled).filter { !readOnly || AgentToolPolicy.isReadOnlyTool(it.name) }.map { it.name }.toSet()
    }
    val visible = remember(tools, query) { tools.filter { it.name.contains(query, true) || it.description.contains(query, true) } }
    Column(Modifier.testTag("tool-access-list").fillMaxWidth().fillMaxHeight(.85f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.tool_access_title), style = MaterialTheme.typography.titleLarge)
        if (readOnly) Text(stringResource(R.string.crew_read_only_description), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.tool_access_count, active.size, tools.size), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().testTag("tool-access-search"), singleLine = true,
            label = { Text(stringResource(R.string.tool_access_search)) })
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed) Text(stringResource(R.string.tool_access_load_failed), color = MaterialTheme.colorScheme.error)
        if (!loading && !failed && tools.isEmpty()) Text(stringResource(R.string.tool_access_empty))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 32.dp)) {
            items(visible, key = { it.name }) { tool ->
                ListItem(
                    headlineContent = { Text(tool.name, fontFamily = FontFamily.Monospace) },
                    supportingContent = { Text(tool.description, maxLines = 3) },
                    trailingContent = { Switch(modifier = Modifier.testTag("tool-toggle-${tool.name}"), checked = tool.name in active,
                        enabled = (!readOnly || AgentToolPolicy.isReadOnlyTool(tool.name)) && (tool.name == "use_skill" || "use_skill" !in disabled ||
                            !me.rerere.rikkahub.data.ai.tools.isSkillTool(tool.name)), onCheckedChange = { onToggle(tool.name, it) }) },
                )
                HorizontalDivider()
            }
        }
    }
}
