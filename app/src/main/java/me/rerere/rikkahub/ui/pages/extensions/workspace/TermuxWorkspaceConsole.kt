package me.rerere.rikkahub.ui.pages.extensions.workspace

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.pages.chat.TermuxJobsScreen

@Composable
internal fun TermuxWorkspaceConsole(vm: WorkspaceDetailVM) {
    val state by vm.state.collectAsStateWithLifecycle()
    val terminal by vm.terminalState.collectAsStateWithLifecycle()
    var showJobs by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(state.workspace?.name ?: "Termux") }, navigationIcon = { BackButton() },
            actions = { TextButton(onClick = { showJobs = true }) { Text(stringResource(R.string.jobs_title)) } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.workspace?.termuxPath.orEmpty(), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.workspace_termux_console_hint), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(terminal.history) { item ->
                    val text = when (item) {
                        is WorkspaceTerminalEntry.Command -> "$ " + item.command
                        is WorkspaceTerminalEntry.Error -> item.message
                        is WorkspaceTerminalEntry.Result -> buildString {
                            append(item.result.stdout)
                            if (item.result.stderr.isNotBlank()) append("\n" + item.result.stderr)
                            append("\nExit: ${item.result.exitCode}")
                            item.result.jobId?.let { append(" · Job: $it") }
                            if (item.result.truncated) append("\n" + stringResource(R.string.workspace_output_more))
                        }
                    }
                    SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (terminal.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(value = terminal.input, onValueChange = vm::updateTerminalInput,
                modifier = Modifier.fillMaxWidth(), enabled = !terminal.running,
                label = { Text(stringResource(R.string.workspace_command)) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.executeTerminalCommand(terminal.input) }, enabled = !terminal.running && terminal.input.isNotBlank()) {
                    Text(stringResource(R.string.workspace_run_command))
                }
                OutlinedButton(onClick = { vm.startTerminalBackground(terminal.input); showJobs = true },
                    enabled = !terminal.running && terminal.input.isNotBlank()) { Text(stringResource(R.string.workspace_run_background)) }
            }
        }
    }
    if (showJobs) TermuxJobsScreen(vm.termuxJobs) { showJobs = false }
}
