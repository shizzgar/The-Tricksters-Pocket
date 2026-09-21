package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.ai.provider.generationTokensPerSecond
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.*
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.utils.toFixed
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ConversationTrajectoryScreen(conversation: Conversation, active: Boolean, onResume: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val journal = remember(context) { SessionJournal.at(context.filesDir) }
    val id = conversation.id.toString()
    val scope = rememberCoroutineScope()
    var page by remember(id) { mutableStateOf(TracePage(emptyList(), null, 0)) }
    var task by remember(id) { mutableStateOf<AgentTaskRecord?>(null) }
    var source by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<TraceRecord?>(null) }
    var payload by remember { mutableStateOf<JsonElement?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var checkedAt by remember { mutableLongStateOf(0) }
    fun read(before: Long? = null) {
        if (busy) return
        busy = true
        scope.launch {
            try { page = journal.page(id, before, source, search); checkedAt = System.currentTimeMillis(); error = page.error }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message }
            finally { busy = false }
        }
    }
    LaunchedEffect(id) { read(); while (true) { task = journal.task(id); delay(1500) } }
    LaunchedEffect(selected) {
        payload = null
        selected?.let { record ->
            try { payload = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Json.parseToJsonElement(journal.payload(id, record)) } }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message }
        }
    }
    val requests = remember(conversation) { conversation.currentMessages.flatMap { it.generationMetrics }.distinctBy { it.requestId } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { if (selected != null) selected = null else onDismiss() }) { Text(stringResource(if (selected == null) R.string.jobs_close else R.string.jobs_back)) }
                    TextButton(onClick = { read() }, enabled = !busy) { Text(stringResource(R.string.jobs_refresh)) }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text(stringResource(R.string.trajectory_title), style = MaterialTheme.typography.headlineSmall) }
                    error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                    if (selected != null) {
                        item {
                            Text("#${selected!!.sequence} · ${selected!!.source}", style = MaterialTheme.typography.titleMedium)
                            Text(DateFormat.getDateTimeInstance().format(Date(selected!!.timestamp)), style = MaterialTheme.typography.labelSmall)
                        }
                        payload?.let { body -> item { TraceValue(stringResource(R.string.trajectory_content), body, expandedInitially = true) } }
                    } else {
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.runtime_request_count, requests.size), style = MaterialTheme.typography.titleMedium)
                                    Text("↑ ${requests.sumOf { it.usage?.promptTokens?.toLong() ?: 0 }} · ↓ ${requests.sumOf { it.usage?.completionTokens?.toLong() ?: 0 }} tokens")
                                    Text("${requests.generationTokensPerSecond()?.toFixed(1) ?: "—"} tok/s · ${progressDuration(requests.sumOf { it.receivingMs ?: 0 })} ${stringResource(R.string.runtime_receiving)}")
                                    Text(stringResource(R.string.runtime_speed_explanation), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        task?.let { saved -> item {
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(stringResource(R.string.trajectory_checkpoint), style = MaterialTheme.typography.titleMedium)
                                    Text("${saved.status} · ${saved.reason?.name.orEmpty()}")
                                    Text("${saved.cycles} cycles · ${saved.steps} steps", style = MaterialTheme.typography.bodySmall)
                                    saved.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    if (!active && saved.status != "completed" && saved.reason != GenerationStopReason.WAITING_APPROVAL) {
                                        FilledTonalButton(onClick = { onResume(); onDismiss() }) { Text(stringResource(R.string.trajectory_resume)) }
                                    }
                                }
                            }
                        } }
                        item {
                            Text(stringResource(R.string.trajectory_scope), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.trajectory_private), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item {
                            OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.trajectory_search)) }, singleLine = true)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(null, "model.request", "model.stream", "model.response", "tool", "task", "compaction", "subagent", "conversation").forEach { value ->
                                    FilterChip(selected = source == value, onClick = { source = value }, label = { Text(value ?: stringResource(R.string.trajectory_all)) })
                                }
                            }
                            TextButton(onClick = { read() }, enabled = !busy) { Text(stringResource(R.string.trajectory_search)) }
                            Text(stringResource(R.string.trajectory_events, page.total), style = MaterialTheme.typography.labelMedium)
                            if (checkedAt > 0) Text(DateFormat.getDateTimeInstance().format(Date(checkedAt)), style = MaterialTheme.typography.labelSmall)
                        }
                        if (page.records.isEmpty()) item { Text(stringResource(R.string.trajectory_empty)) }
                        items(page.records, key = { it.sequence }) { record ->
                            OutlinedCard(onClick = { selected = record }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("#${record.sequence} · ${record.source}", style = MaterialTheme.typography.titleSmall)
                                    Text(DateFormat.getTimeInstance().format(Date(record.timestamp)), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        if (page.before != null) item { TextButton(onClick = { read(page.before) }, enabled = !busy) { Text(stringResource(R.string.trajectory_more)) } }
                    }
                }
            }
        }
    }
}

/** Bounded, selectable tree. Long strings and arrays have pages rather than horizontal scrolling. */
@Composable
private fun TraceValue(label: String, value: JsonElement, expandedInitially: Boolean = false) {
    var expanded by remember(value) { mutableStateOf(expandedInitially) }
    var offset by remember(value) { mutableIntStateOf(0) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text("${if (expanded) "▾" else "▸"} $label", style = MaterialTheme.typography.labelLarge)
            }
            if (expanded) when (value) {
                is JsonObject -> value.forEach { (field, item) -> key(field) { TraceValue(field, item, item is JsonPrimitive) } }
                is JsonArray -> {
                    value.drop(offset).take(20).forEachIndexed { index, item -> key(offset + index) { TraceValue("${offset + index + 1} / ${value.size}", item) } }
                    TracePages(offset, 20, value.size) { offset = it }
                }
                else -> {
                    val text = (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
                    val part = text.drop(offset).take(6000)
                    SelectionContainer {
                        if (label in setOf("text", "reasoning", "content")) MarkdownBlock(part, style = MaterialTheme.typography.bodyMedium)
                        else Text(part, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), softWrap = true)
                    }
                    TracePages(offset, 6000, text.length) { offset = it }
                }
            }
        }
    }
}

@Composable
private fun TracePages(offset: Int, size: Int, total: Int, onOffset: (Int) -> Unit) {
    if (total <= size) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { onOffset((offset - size).coerceAtLeast(0)) }, enabled = offset > 0) { Text("←") }
        Text("${offset + 1}–${(offset + size).coerceAtMost(total)} / $total", style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = { onOffset(offset + size) }, enabled = offset + size < total) { Text("→") }
    }
}
