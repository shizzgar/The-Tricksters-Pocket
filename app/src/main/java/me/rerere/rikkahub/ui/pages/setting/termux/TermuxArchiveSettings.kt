package me.rerere.rikkahub.ui.pages.setting.termux

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.TermuxArchiveEntry
import me.rerere.rikkahub.data.ai.tools.local.TermuxOutputArchive
import me.rerere.rikkahub.ui.components.ui.CardGroup
import java.util.Date

@Composable
internal fun TermuxArchiveSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<TermuxArchiveEntry>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var visible by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        busy = true
        try {
            entries = withContext(Dispatchers.IO) { TermuxOutputArchive.list(context) }
            selected = selected.intersect(entries.map { it.key }.toSet())
            error = null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = e.message
        } finally { busy = false }
    }
    LaunchedEffect(Unit) { reload() }

    CardGroup(title = { Text(stringResource(R.string.termux_archive_title)) }) {
        item(
            onClick = { visible = true; scope.launch { reload() } },
            headlineContent = { Text(stringResource(R.string.termux_archive_manage)) },
            supportingContent = { Text(stringResource(R.string.termux_archive_summary,
                entries.size, Formatter.formatShortFileSize(context, entries.sumOf { it.bytes }),
                Formatter.formatShortFileSize(context, TermuxOutputArchive.STORE_LIMIT))) },
        )
    }
    if (visible) AlertDialog(
        onDismissRequest = { if (!busy) visible = false },
        title = { Text(stringResource(R.string.termux_archive_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.termux_archive_description))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(enabled = !busy, onClick = {
                    val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                    selected = entries.filter { it.createdAt < cutoff }.map { it.key }.toSet()
                }) { Text(stringResource(R.string.termux_archive_select_old)) }
                if (entries.isEmpty()) Text(stringResource(R.string.termux_archive_empty))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(entries, key = { it.key }) { entry ->
                        Row(Modifier.fillMaxWidth()) {
                            Checkbox(modifier = Modifier.testTag("termux-archive-select-${entry.ref}"), checked = entry.key in selected, enabled = !busy, onCheckedChange = { checked ->
                                selected = if (checked) selected + entry.key else selected - entry.key
                            })
                            Column {
                                Text(entry.ref.take(8), style = MaterialTheme.typography.titleSmall)
                                Text("${DateFormat.getDateFormat(context).format(Date(entry.createdAt))} · ${Formatter.formatShortFileSize(context, entry.bytes)}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy && selected.isNotEmpty(), onClick = { confirm = true }) {
            Text(stringResource(R.string.termux_archive_delete_selected, selected.size))
        } },
        dismissButton = { TextButton(enabled = !busy, onClick = { visible = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
    if (confirm) AlertDialog(
        onDismissRequest = { if (!busy) confirm = false },
        title = { Text(stringResource(R.string.termux_archive_delete_selected, selected.size)) },
        text = { Text(stringResource(R.string.termux_archive_delete_warning)) },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val targets = entries.filter { it.key in selected }
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { TermuxOutputArchive.delete(context, targets) }
                    confirm = false
                    reload()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    error = e.message
                    confirm = false
                } finally { busy = false }
            }
        }) { Text(stringResource(R.string.common_delete)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirm = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
}
