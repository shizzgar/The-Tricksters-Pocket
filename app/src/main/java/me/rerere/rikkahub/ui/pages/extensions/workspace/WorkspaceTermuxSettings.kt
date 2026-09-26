package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.termux.SettingTermuxViewModel
import me.rerere.rikkahub.ui.pages.setting.termux.TermuxRuntimeSettings
import me.rerere.rikkahub.ui.pages.setting.termux.TermuxSkillsSettings
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun WorkspaceTermuxSettings(
    directory: String,
    vm: SettingTermuxViewModel = koinViewModel(),
) {
    val navigator = LocalNavController.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardGroup(title = { Text(stringResource(R.string.termux_workspace_connection_title)) }) {
            item(
                headlineContent = { Text(stringResource(R.string.workspace_termux_directory)) },
                supportingContent = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(directory)
                    Text(stringResource(R.string.termux_workspace_directory_description))
                } },
            )
            item(
                onClick = { navigator.navigate(Screen.SettingTermux) },
                headlineContent = { Text(stringResource(R.string.termux_workspace_connection_action)) },
                supportingContent = { Text(stringResource(R.string.termux_workspace_connection_description)) },
            )
        }
        TermuxRuntimeSettings(vm, workspaceDirectory = directory)
        TermuxSkillsSettings(vm)
    }
}
