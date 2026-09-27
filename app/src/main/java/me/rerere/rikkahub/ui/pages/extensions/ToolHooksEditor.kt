package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ToolHook
import me.rerere.rikkahub.data.model.TOOL_HOOK_MAX_PROMPT_CHARS
import me.rerere.rikkahub.data.model.TOOL_HOOK_MAX_MATCH_CHARS
import me.rerere.rikkahub.data.model.TOOL_HOOK_MAX_FIRINGS_PER_TURN
import me.rerere.rikkahub.data.model.ToolHookActionType
import me.rerere.rikkahub.data.model.ToolHookOutcome
import me.rerere.rikkahub.data.model.ToolHookScope
import me.rerere.rikkahub.ui.components.ui.Select
import kotlin.uuid.Uuid

/** Pure editor validation; saving a disabled draft also requires a complete, usable rule. */
internal fun toolHookEditorErrors(rule: ToolHook): List<Int> = buildList {
    if (rule.name.isBlank()) add(R.string.hooks_name_required)
    if (rule.name.length > 128) add(R.string.hooks_name_too_long)
    if (listOf(rule.condition.commandContains, rule.condition.stdoutContains, rule.condition.stderrContains).any { it.length > TOOL_HOOK_MAX_MATCH_CHARS } || rule.condition.commandExecutable.length > 256) add(R.string.hooks_match_too_long)
    if (rule.condition.toolNames.size > 32 || rule.condition.toolNames.any { it.isBlank() || it.length > 128 }) add(R.string.hooks_tools_invalid)
    if (!rule.scope.global && rule.scope.assistantIds.isEmpty() && rule.scope.workspaceIds.isEmpty() && rule.scope.conversationIds.isEmpty()) {
        add(R.string.hooks_scope_required)
    }
    if (rule.maxFiringsPerTurn !in 1..TOOL_HOOK_MAX_FIRINGS_PER_TURN) add(R.string.hooks_cap_invalid)
    when (rule.action.type) {
        ToolHookActionType.INLINE_PROMPT -> {
            if (rule.action.prompt.isBlank()) add(R.string.hooks_prompt_required)
            if (rule.action.prompt.length > TOOL_HOOK_MAX_PROMPT_CHARS) add(R.string.hooks_prompt_too_long)
        }
        ToolHookActionType.SKILL -> {
            if (rule.action.skillName.isBlank()) add(R.string.hooks_skill_required)
            val path = rule.action.skillPath
            if (path.isBlank() || path != path.trim() || path.startsWith('/') || path.startsWith('~') || path.any { it == '\\' || it == ':' || it.isISOControl() } || path.split('/').any { it == ".." || it == "." || it.isBlank() } || path.substringAfterLast('.', "").lowercase() !in setOf("md", "markdown", "txt", "text")) {
                add(R.string.hooks_path_invalid)
            }
        }
    }
}

@Composable
fun ToolHookEditSheet(
    rule: ToolHook,
    onEdit: (ToolHook) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onPreview: (ToolHook) -> Unit,
    assistants: List<Assistant> = emptyList(),
    workspaces: List<WorkspaceEntity> = emptyList(),
    conversations: List<Conversation> = emptyList(),
    skills: List<SkillMetadata> = emptyList(),
    error: String? = null,
    saving: Boolean = false,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val errors = toolHookEditorErrors(rule)
    var showAdvanced by remember(rule.id) { mutableStateOf(false) }
    var cap by remember(rule.id) { mutableStateOf(rule.maxFiringsPerTurn.toString()) }
    var tools by remember(rule.id) { mutableStateOf(rule.condition.toolNames.joinToString(", ")) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, sheetGesturesEnabled = false) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding().padding(horizontal = 20.dp).testTag("hook-editor"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.hooks_edit), style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("hook-editor-fields"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(rule.name, { onEdit(rule.copy(name = it)) }, modifier = Modifier.fillMaxWidth().testTag("hook-name"), label = { Text(stringResource(R.string.hooks_name)) }, singleLine = true)
                HookSwitch(stringResource(R.string.hooks_enabled), rule.enabled, { onEdit(rule.copy(enabled = it)) })
                HookSection(R.string.hooks_when)
                Text(stringResource(R.string.hooks_event_completed), style = MaterialTheme.typography.bodyMedium)
                Select(ToolHookOutcome.entries, rule.condition.outcome, { onEdit(rule.copy(condition = rule.condition.copy(outcome = it))) }, optionToString = { hookOutcomeLabel(it) }, modifier = Modifier.testTag("hook-outcome"))
                OutlinedTextField(tools, {
                    tools = it
                    onEdit(rule.copy(condition = rule.condition.copy(toolNames = it.split(',', '\n').map(String::trim).filter(String::isNotEmpty).toSet())))
                }, label = { Text(stringResource(R.string.hooks_tools)) }, supportingText = { Text(stringResource(R.string.hooks_tools_hint)) }, modifier = Modifier.fillMaxWidth().testTag("hook-tools"))
                HookSection(R.string.hooks_conditions)
                OutlinedTextField(rule.condition.commandExecutable, { onEdit(rule.copy(condition = rule.condition.copy(commandExecutable = it))) }, label = { Text(stringResource(R.string.hooks_executable)) }, supportingText = { Text(stringResource(R.string.hooks_executable_hint)) }, modifier = Modifier.fillMaxWidth().testTag("hook-executable"), singleLine = true)
                TextButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.testTag("hook-advanced")) { Text(stringResource(if (showAdvanced) R.string.hooks_less_conditions else R.string.hooks_more_conditions)) }
                if (showAdvanced) {
                    OutlinedTextField(rule.condition.commandContains, { onEdit(rule.copy(condition = rule.condition.copy(commandContains = it))) }, label = { Text(stringResource(R.string.hooks_command_contains)) }, supportingText = { Text(stringResource(R.string.hooks_literal_hint)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(rule.condition.stderrContains, { onEdit(rule.copy(condition = rule.condition.copy(stderrContains = it))) }, label = { Text(stringResource(R.string.hooks_stderr)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(rule.condition.stdoutContains, { onEdit(rule.copy(condition = rule.condition.copy(stdoutContains = it))) }, label = { Text(stringResource(R.string.hooks_stdout)) }, modifier = Modifier.fillMaxWidth())
                    HookSwitch(stringResource(R.string.hooks_case_sensitive), rule.condition.caseSensitive, { onEdit(rule.copy(condition = rule.condition.copy(caseSensitive = it))) })
                }
                Text(stringResource(R.string.hooks_all_conditions), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HookSection(R.string.hooks_action)
                Select(ToolHookActionType.entries, rule.action.type, { onEdit(rule.copy(action = rule.action.copy(type = it))) }, optionToString = { stringResource(if (it == ToolHookActionType.INLINE_PROMPT) R.string.hooks_inline else R.string.hooks_skill) })
                if (rule.action.type == ToolHookActionType.INLINE_PROMPT) {
                    OutlinedTextField(rule.action.prompt, { onEdit(rule.copy(action = rule.action.copy(prompt = it))) }, label = { Text(stringResource(R.string.hooks_prompt)) }, minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth().testTag("hook-prompt"))
                } else {
                    val skillNames = (skills.map { it.name } + rule.action.skillName).filter(String::isNotBlank).distinct()
                    if (skillNames.isEmpty()) Text(stringResource(R.string.hooks_no_skills), style = MaterialTheme.typography.bodyMedium)
                    else Select(skillNames, rule.action.skillName, { onEdit(rule.copy(action = rule.action.copy(skillName = it))) }, optionToString = { it.ifBlank { stringResource(R.string.hooks_choose_skill) } }, modifier = Modifier.testTag("hook-skill"))
                    if (rule.action.skillName.isNotBlank() && skills.none { it.name == rule.action.skillName }) Text(stringResource(R.string.hooks_skill_missing), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(rule.action.skillPath, { onEdit(rule.copy(action = rule.action.copy(skillPath = it))) }, label = { Text(stringResource(R.string.hooks_skill_path)) }, supportingText = { Text(stringResource(R.string.hooks_skill_path_hint)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(rule.action.skillSection, { onEdit(rule.copy(action = rule.action.copy(skillSection = it))) }, label = { Text(stringResource(R.string.hooks_skill_section)) }, supportingText = { Text(stringResource(R.string.hooks_skill_section_hint)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                Text(stringResource(R.string.hooks_action_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HookSection(R.string.hooks_scope)
                ToolHookScopeEditor(rule.scope, { onEdit(rule.copy(scope = it)) }, assistants, workspaces, conversations)
                HookSwitch(stringResource(R.string.hooks_inherit), rule.scope.inheritToSubagents, { onEdit(rule.copy(scope = rule.scope.copy(inheritToSubagents = it))) }, stringResource(R.string.hooks_inherit_hint))
                HookSection(R.string.hooks_limits)
                OutlinedTextField(cap, {
                    cap = it
                    onEdit(rule.copy(maxFiringsPerTurn = it.toIntOrNull() ?: 0))
                }, modifier = Modifier.fillMaxWidth().testTag("hook-cap"), label = { Text(stringResource(R.string.hooks_cap)) }, supportingText = { Text(stringResource(R.string.hooks_cap_hint)) }, isError = rule.maxFiringsPerTurn !in 1..TOOL_HOOK_MAX_FIRINGS_PER_TURN, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedButton(onClick = { onPreview(rule) }, enabled = errors.isEmpty(), modifier = Modifier.fillMaxWidth().testTag("hook-preview")) { Text(stringResource(R.string.hooks_preview)) }
                Text(stringResource(R.string.hooks_preview_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                errors.forEach { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.prompt_page_cancel)) }
                Button(onClick = onSave, enabled = errors.isEmpty() && !saving, modifier = Modifier.testTag("hook-save")) { Text(stringResource(R.string.hooks_save)) }
            }
        }
    }
}

@Composable
internal fun hookOutcomeLabel(outcome: ToolHookOutcome): String = stringResource(when (outcome) {
    ToolHookOutcome.COMPLETED -> R.string.hooks_outcome_completed
    ToolHookOutcome.NONZERO_EXIT -> R.string.hooks_outcome_nonzero
    ToolHookOutcome.SUCCESS -> R.string.hooks_outcome_success
    ToolHookOutcome.TOOL_ERROR -> R.string.hooks_outcome_error
    ToolHookOutcome.TIMEOUT -> R.string.hooks_outcome_timeout
})

@Composable
private fun HookSection(label: Int) {
    Text(stringResource(label), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun HookSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, description: String? = null) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, null)
    }
}

private enum class HookScopeKind { GLOBAL, ASSISTANT, WORKSPACE, CHAT }

@Composable
private fun ToolHookScopeEditor(scope: ToolHookScope, onChange: (ToolHookScope) -> Unit, assistants: List<Assistant>, workspaces: List<WorkspaceEntity>, conversations: List<Conversation>) {
    var chooser by remember { mutableStateOf<HookScopeKind?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HookScopeKind.entries.forEach { kind ->
            val count = when (kind) { HookScopeKind.GLOBAL -> if (scope.global) 1 else 0; HookScopeKind.ASSISTANT -> scope.assistantIds.size; HookScopeKind.WORKSPACE -> scope.workspaceIds.size; HookScopeKind.CHAT -> scope.conversationIds.size }
            val title = scopeKindLabel(kind)
            FilterChip(selected = count > 0, onClick = {
                if (kind == HookScopeKind.GLOBAL) onChange(scope.copy(
                    global = !scope.global, assistantIds = emptySet(), workspaceIds = emptySet(), conversationIds = emptySet(),
                )) else chooser = kind
            }, label = { Text(if (kind != HookScopeKind.GLOBAL && count > 0) "$title · $count" else title) }, modifier = Modifier.testTag("hook-scope-${kind.name.lowercase()}"))
        }
    }
    Text(stringResource(if (scope.global) R.string.hooks_scope_global_hint else R.string.hooks_scope_union_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    chooser?.let { kind ->
        val selected = when (kind) {
            HookScopeKind.ASSISTANT -> scope.assistantIds.map(Uuid::toString).toSet()
            HookScopeKind.WORKSPACE -> scope.workspaceIds
            HookScopeKind.CHAT -> scope.conversationIds.map(Uuid::toString).toSet()
            HookScopeKind.GLOBAL -> emptySet()
        }
        val available = when (kind) {
            HookScopeKind.ASSISTANT -> assistants.map { it.id.toString() to it.name.ifBlank { it.id.toString().take(8) } }
            HookScopeKind.WORKSPACE -> workspaces.map { it.id to it.name }
            HookScopeKind.CHAT -> conversations.map { it.id.toString() to it.title.ifBlank { it.id.toString().take(8) } }
            HookScopeKind.GLOBAL -> emptyList()
        }
        val unavailable = stringResource(R.string.hooks_unavailable)
        val options = (available + (selected - available.map { it.first }.toSet()).map { it to "$unavailable · ${it.take(8)}" }).distinctBy { it.first }
        HookScopeChooser(scopeKindLabel(kind), options, selected, { ids ->
            onChange(when (kind) {
                HookScopeKind.ASSISTANT -> scope.copy(global = false, assistantIds = ids.map(Uuid::parse).toSet())
                HookScopeKind.WORKSPACE -> scope.copy(global = false, workspaceIds = ids)
                HookScopeKind.CHAT -> scope.copy(global = false, conversationIds = ids.map(Uuid::parse).toSet())
                HookScopeKind.GLOBAL -> scope
            })
        }, { chooser = null })
    }
}

@Composable
private fun scopeKindLabel(kind: HookScopeKind): String = stringResource(when (kind) {
    HookScopeKind.GLOBAL -> R.string.hooks_global
    HookScopeKind.ASSISTANT -> R.string.hooks_assistants
    HookScopeKind.WORKSPACE -> R.string.hooks_workspaces
    HookScopeKind.CHAT -> R.string.hooks_chats
})

@Composable
private fun HookScopeChooser(title: String, options: List<Pair<String, String>>, selected: Set<String>, onChange: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.hooks_filter)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (options.isEmpty()) Text(stringResource(R.string.hooks_no_targets))
            LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.55f)) {
                items(options.filter { it.second.contains(query, true) }, key = { it.first }) { (id, name) ->
                    Row(Modifier.fillMaxWidth().clickable { onChange(if (id in selected) selected - id else selected + id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(id in selected, null)
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.hooks_done)) } })
}
