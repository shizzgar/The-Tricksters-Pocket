package me.rerere.rikkahub.ui.pages.backup.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.SessionJournal
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.koin.compose.koinInject

@Composable
fun TraceStorageCard(repository: ConversationRepository = koinInject()) {
    val context = LocalContext.current
    val journal = remember(context) { SessionJournal.at(context.filesDir) }
    val scope = rememberCoroutineScope()
    var bytes by remember { mutableLongStateOf(0L) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { bytes = journal.storageBytes() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.pocket_trace_storage,
            android.text.format.Formatter.formatShortFileSize(context, bytes)),
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = !busy, onClick = { confirm = true }) { Text(stringResource(R.string.pocket_trace_orphans)) }
    }
    error?.let { Text(it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.pocket_trace_orphans)) },
        text = { Text(stringResource(R.string.pocket_trace_orphans_notice)) },
        confirmButton = { TextButton(onClick = {
            confirm = false; busy = true
            scope.launch {
                try { repository.cleanupOrphanedTraces(); bytes = journal.storageBytes(); error = null }
                catch (e: Exception) { if (e is CancellationException) throw e; error = e.message }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.pocket_trace_clear)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}
