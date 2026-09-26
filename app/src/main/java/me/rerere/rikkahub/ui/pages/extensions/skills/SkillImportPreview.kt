package me.rerere.rikkahub.ui.pages.extensions.skills

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.rerere.rikkahub.R
import me.rerere.rikkahub.skills.*

@Composable
internal fun SkillImportPreview(proposal: SkillImportProposal, busy: Boolean, onDismiss: () -> Unit,
    onInstall: (SkillInstallChoice, String?) -> Unit) {
    var copy by remember(proposal) { mutableStateOf(false) }
    var copyName by remember(proposal) { mutableStateOf((proposal.name.take(35) + "-copy").take(40)) }
    var expanded by remember(proposal) { mutableStateOf<String?>("SKILL.md") }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp).widthIn(max = 700.dp).fillMaxHeight(.9f), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.pocket_skill_import_preview), style = MaterialTheme.typography.titleLarge)
                Text(proposal.name, style = MaterialTheme.typography.titleMedium)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        SelectionContainer { Text(stringResource(R.string.pocket_skill_source, proposal.source), style = MaterialTheme.typography.bodySmall) }
                        proposal.version?.let { Text(stringResource(R.string.pocket_skill_version, it), style = MaterialTheme.typography.bodySmall) }
                        Text(stringResource(R.string.skill_workbench_inventory, proposal.files.size, skillSize(proposal.bytes)), style = MaterialTheme.typography.bodySmall)
                        proposal.expectedRevision?.let { Text(stringResource(R.string.pocket_skill_revision, it.take(16)), style = MaterialTheme.typography.bodySmall) }
                        val requirements = proposal.requirements
                        if (requirements.tools.isNotEmpty() || requirements.anyTools.isNotEmpty()) Text(stringResource(R.string.pocket_skill_required_tools, (requirements.tools + requirements.anyTools.joinToString(" / ").takeIf { it.isNotBlank() }.orEmpty()).filter(String::isNotBlank).joinToString(", ")), style = MaterialTheme.typography.bodySmall)
                        if (requirements.environment.isNotEmpty()) Text(stringResource(R.string.pocket_skill_environment, requirements.environment.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
                    }
                    item { Text(stringResource(if (proposal.expectedRevision == null) R.string.pocket_skill_new_hint else R.string.pocket_skill_update_hint), style = MaterialTheme.typography.bodyMedium) }
                    if (proposal.changes.isEmpty()) item { Text(stringResource(R.string.pocket_skill_no_changes)) }
                    items(proposal.changes, key = { it.path }) { change ->
                        OutlinedCard(onClick = { expanded = if (expanded == change.path) null else change.path }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${change.kind} ${change.path}", style = MaterialTheme.typography.labelLarge)
                                Text("${change.before?.size ?: 0} B → ${change.after?.size ?: 0} B", style = MaterialTheme.typography.labelSmall)
                                if (expanded == change.path) SelectionContainer { Text(change.preview(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
                if (proposal.expectedRevision != null) {
                    Row { Checkbox(copy, { copy = it }, enabled = !busy); TextButton(onClick = { copy = !copy }, enabled = !busy) { Text(stringResource(R.string.pocket_skill_install_copy)) } }
                    if (copy) OutlinedTextField(copyName, { copyName = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                        label = { Text(stringResource(R.string.pocket_skill_copy_name)) }, isError = !SkillLifecycle.validName(copyName) || copyName == proposal.name)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = { onInstall(if (copy) SkillInstallChoice.COPY else if (proposal.expectedRevision == null) SkillInstallChoice.NEW else SkillInstallChoice.UPDATE, copyName) },
                        enabled = !busy && if (copy) SkillLifecycle.validName(copyName) && copyName != proposal.name else proposal.changes.isNotEmpty()) {
                        Text(stringResource(if (copy) R.string.pocket_skill_install_copy else if (proposal.expectedRevision == null) R.string.pocket_skill_install else R.string.pocket_skill_update))
                    }
                }
            }
        }
    }
}
