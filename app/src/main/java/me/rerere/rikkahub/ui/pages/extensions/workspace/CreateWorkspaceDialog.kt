package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.TermuxIntegration
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceFileEntry
import org.koin.compose.koinInject

@Composable
internal fun CreateWorkspaceDialog(existingNames: Set<String>, onDismiss: () -> Unit) {
    val repository = koinInject<WorkspaceRepository>()
    val scope = rememberCoroutineScope()
    val ready = TermuxIntegration.state(LocalContext.current) == TermuxIntegration.State.READY
    var name by rememberSaveable { mutableStateOf("") }
    var termux by rememberSaveable { mutableStateOf(ready) }
    var path by rememberSaveable { mutableStateOf("/data/data/com.termux/files/home") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var folders by remember { mutableStateOf<List<WorkspaceFileEntry>?>(null) }
    val duplicate = name.trim() in existingNames
    fun browse() {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { folders = repository.browseTermux(path.trim()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            finally { busy = false }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.workspace_page_create)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, enabled = !busy,
                    label = { Text(stringResource(R.string.workspace_page_name)) }, singleLine = true,
                    isError = duplicate, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !termux, onClick = { termux = false }, enabled = !busy,
                        label = { Text(stringResource(R.string.workspace_local_linux)) })
                    FilterChip(selected = termux, onClick = { termux = true }, enabled = !busy,
                        label = { Text("Termux") })
                }
                if (termux) {
                    Text(stringResource(R.string.workspace_termux_link_hint))
                    if (!ready) Text(stringResource(R.string.workspace_termux_connect), color = MaterialTheme.colorScheme.error)
                    OutlinedTextField(value = path, onValueChange = { path = it; folders = null }, enabled = !busy,
                        label = { Text(stringResource(R.string.workspace_termux_directory)) }, modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton(onClick = { browse() }, enabled = ready && !busy) { Text(stringResource(R.string.workspace_browse)) }
                        TextButton(onClick = {
                            path = path.trimEnd('/').substringBeforeLast('/', "").ifBlank { "/" }
                            browse()
                        }, enabled = ready && !busy && path != "/") { Text(stringResource(R.string.workspace_parent_folder)) }
                    }
                    folders?.forEach { folder ->
                        TextButton(onClick = {
                            path = path.trimEnd('/') + "/" + folder.name
                            browse()
                        }, enabled = !busy) { Text(folder.name) }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank() && !duplicate && (!termux || ready && path.startsWith('/')),
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            if (termux) repository.createTermux(name, path) else repository.create(name)
                            onDismiss()
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message }
                        finally { busy = false }
                    }
                }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
internal fun WorkspacePathDialog(title: String, moving: Boolean, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var source by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = source, onValueChange = { source = it }, singleLine = true,
                label = { Text(stringResource(if (moving) R.string.workspace_source_path else R.string.workspace_folder_name)) })
            if (moving) OutlinedTextField(value = target, onValueChange = { target = it }, singleLine = true,
                label = { Text(stringResource(R.string.workspace_target_path)) })
        }
    }, confirmButton = {
        TextButton(onClick = { onConfirm(source, target) }, enabled = source.isNotBlank() && (!moving || target.isNotBlank())) {
            Text(stringResource(R.string.common_save))
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } })
}
