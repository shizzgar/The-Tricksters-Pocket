package me.rerere.rikkahub.ui.pages.setting.termux

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.skills.TermuxSkillConfig
import me.rerere.rikkahub.ui.components.ui.CardGroup

@Composable
internal fun TermuxSkillsSettings(vm: SettingTermuxViewModel) {
    val config by vm.skills.collectAsStateWithLifecycle()
    val status by vm.skillStatus.collectAsStateWithLifecycle()
    var path by remember(config.directory) { mutableStateOf(config.directory) }
    var confirmClear by remember { mutableStateOf(false) }
    val valid = TermuxSkillConfig.validDirectory(path)
    CardGroup(title = { Text(stringResource(R.string.termux_skills_title)) }) {
        item(
            headlineContent = { Text(stringResource(R.string.termux_skills_enabled)) },
            supportingContent = { Text(stringResource(R.string.termux_skills_description)) },
            trailingContent = { Switch(config.enabled, { vm.setSkills(config.copy(enabled = it)) }, enabled = !status.busy) },
        )
        item(
            headlineContent = { Text(stringResource(R.string.termux_skills_auto)) },
            supportingContent = { Text(stringResource(R.string.termux_skills_auto_description)) },
            trailingContent = { Switch(config.syncOnUse, { vm.setSkills(config.copy(syncOnUse = it)) }, enabled = config.enabled && !status.busy) },
        )
        item(headlineContent = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(path, { path = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.termux_skills_directory)) },
                    enabled = !status.busy, isError = !valid, supportingText = { Text(stringResource(R.string.termux_skills_directory_help)) })
                if (path != config.directory) TextButton(onClick = { vm.setSkills(config.copy(directory = path)) }, enabled = valid && !status.busy) {
                    Text(stringResource(R.string.termux_skills_save))
                }
                Text(stringResource(R.string.termux_skills_dependencies), style = MaterialTheme.typography.bodySmall)
                status.packages?.let { Text(stringResource(R.string.termux_skills_count, it, status.bytes / 1024), style = MaterialTheme.typography.labelMedium) }
                status.progress?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (status.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = vm::syncSkills, enabled = config.enabled && !status.busy && path == config.directory) { Text(stringResource(R.string.termux_skills_sync)) }
                    TextButton(onClick = vm::refreshSkills, enabled = !status.busy && path == config.directory) { Text(stringResource(R.string.jobs_refresh)) }
                    TextButton(onClick = { confirmClear = true }, enabled = !status.busy && path == config.directory) { Text(stringResource(R.string.termux_skills_clear)) }
                }
            }
        })
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.termux_skills_clear)) },
        text = { Text(stringResource(R.string.termux_skills_clear_warning)) },
        confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearSkills() }) { Text(stringResource(R.string.termux_skills_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.jobs_back)) } },
    )
}
