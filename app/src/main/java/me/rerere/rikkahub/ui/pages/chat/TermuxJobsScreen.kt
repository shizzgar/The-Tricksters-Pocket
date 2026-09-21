package me.rerere.rikkahub.ui.pages.chat

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.tools.presentTermux
import me.rerere.rikkahub.ui.components.message.tools.label
import java.text.DateFormat
import java.util.Date

@Composable
internal fun TermuxJobsScreen(controller: TermuxJobsController, onDismiss: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    var confirmation by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(controller) { controller.refresh() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { if (state.selected != null) controller.back() else onDismiss() }, enabled = !state.busy || state.selected == null) {
                        Text(stringResource(if (state.selected == null) R.string.jobs_close else R.string.jobs_back))
                    }
                    TextButton(onClick = { if (state.selected != null) controller.read() else controller.refresh() }, enabled = !state.busy) {
                        Text(stringResource(R.string.jobs_refresh))
                    }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(stringResource(R.string.jobs_title), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.jobs_scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.error?.let { error -> item {
                        Text(stringResource(R.string.jobs_observation_error), color = MaterialTheme.colorScheme.error)
                        JobTextBlock(error)
                    } }
                    val selected = state.selected
                    if (selected == null) {
                        state.lastCheckedAt?.let { time -> item { CheckedTime(time) } }
                        if (state.jobs.isEmpty() && !state.busy && state.error == null) item { Text(stringResource(R.string.jobs_empty)) }
                        items(state.jobs, key = { it.jobString("job_id") ?: it.toString() }) { job ->
                            OutlinedCard(onClick = { controller.select(job) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    JobStatus(job)
                                    Text(job.jobString("command").orEmpty(), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr))
                                    Text(job.jobString("job_id").orEmpty(), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        if (state.nextListCursor != null) item { OutlinedButton(onClick = { controller.refresh(more = true) }, enabled = !state.busy) { Text(stringResource(R.string.jobs_more)) } }
                    } else {
                        item {
                            JobStatus(selected)
                            state.selectedCheckedAt?.let { CheckedTime(it) }
                            Text(selected.jobString("job_id").orEmpty(), style = MaterialTheme.typography.labelSmall)
                        }
                        item { Text(stringResource(R.string.jobs_command), style = MaterialTheme.typography.titleSmall); JobTextBlock(selected.jobString("command").orEmpty()) }
                        selected.jobString("working_dir")?.let { dir -> item { Text(stringResource(R.string.jobs_directory), style = MaterialTheme.typography.titleSmall); JobTextBlock(dir) } }
                        selected.jobString("reason")?.let { reason -> item { JobTextBlock(reason) } }
                        item {
                            if (selected.jobString("state") in setOf("starting", "running", "cancelling", "unknown")) {
                                OutlinedButton(onClick = { confirmation = "cancel" to requireNotNull(selected.jobString("job_id")) }, enabled = !state.busy) { Text(stringResource(R.string.jobs_cancel)) }
                            }
                            if (selected.canForgetJob && !selected.jobBool("logs_removed")) {
                                TextButton(onClick = { confirmation = "forget" to requireNotNull(selected.jobString("job_id")) }, enabled = !state.busy) { Text(stringResource(R.string.jobs_forget)) }
                            }
                        }
                        state.mutation?.let { result -> item {
                            val view = presentTermux("termux_job_${state.mutationAction}", buildJsonObject {}, result, false, false)
                            Text(stringResource(view.status.label()), style = MaterialTheme.typography.titleSmall)
                        } }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("stdout", "stderr").forEach { stream ->
                                    FilterChip(selected = state.stream == stream, onClick = { controller.changeStream(stream) }, enabled = !state.busy, label = { Text(stream) })
                                }
                            }
                        }
                        if (selected.jobBool("logs_removed")) item { Text(stringResource(R.string.termux_preview_logs_removed)) }
                        else state.page?.let { page ->
                            item {
                                Text(stringResource(R.string.jobs_bytes, page.jobLong("cursor") ?: 0, page.jobLong("next_cursor") ?: 0, page.jobLong("stored_bytes") ?: 0), style = MaterialTheme.typography.labelMedium)
                                if ((page["logs_truncated"] as? JsonObject)?.jobBool(state.stream) == true) Text(stringResource(R.string.jobs_log_truncated), color = MaterialTheme.colorScheme.error)
                                Text(stringResource(R.string.jobs_encoding), style = MaterialTheme.typography.bodySmall)
                            }
                            item {
                                state.pageCheckedAt?.let { CheckedTime(it) }
                                JobTextBlock(page.jobString("text").orEmpty())
                            }
                            item {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    TextButton(onClick = { controller.read(-1) }, enabled = !state.busy && state.previousCursors.isNotEmpty()) { Text(stringResource(R.string.jobs_previous)) }
                                    TextButton(onClick = { controller.read(1) }, enabled = !state.busy && page.jobBool("has_more")) { Text(stringResource(R.string.jobs_next)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        confirmation?.let { (action, id) ->
            AlertDialog(onDismissRequest = { confirmation = null },
                title = { Text(stringResource(if (action == "cancel") R.string.jobs_cancel else R.string.jobs_forget)) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(id)
                    Text(stringResource(if (action == "cancel") R.string.jobs_cancel_note else R.string.jobs_forget_note))
                } },
                confirmButton = { TextButton(onClick = {
                    confirmation = null
                    if (controller.state.value.selected?.jobString("job_id") == id) controller.mutate(action)
                }, enabled = !state.busy) { Text(stringResource(R.string.jobs_confirm)) } },
                dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.jobs_back)) } })
        }
    }
}

@Composable
private fun JobStatus(job: JsonObject) {
    val view = presentTermux("termux_job_read", buildJsonObject {}, job, false, false)
    Text(stringResource(view.status.label()) + (view.exitCode?.let { " · " + stringResource(R.string.termux_preview_exit, it) } ?: ""), style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun CheckedTime(time: Long) {
    Text(stringResource(R.string.jobs_checked, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(time))), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun JobTextBlock(text: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Termux", text))) } }, enabled = text.isNotEmpty()) { Text(stringResource(R.string.jobs_copy)) }
            SelectionContainer {
                Text(text.ifEmpty { stringResource(R.string.jobs_empty_page) }, modifier = Modifier.fillMaxWidth(), softWrap = true,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr))
            }
        }
    }
}
