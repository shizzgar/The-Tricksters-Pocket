package me.rerere.rikkahub.ui.pages.chat

import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.*
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import java.text.DateFormat
import java.util.Date
import kotlin.math.max

@Composable
internal fun ConversationTrajectoryScreen(conversation: Conversation, active: Boolean, onResume: () -> Unit, onDismiss: () -> Unit, journalOverride: SessionJournal? = null) {
    val context = LocalContext.current
    val journal = journalOverride ?: remember(context) { SessionJournal.at(context.filesDir) }
    var session by remember(conversation.id) { mutableStateOf(conversation.id.toString()) }
    val parents = remember { mutableStateListOf<String>() }
    var page by remember(session) { mutableStateOf(TrajectoryPage(emptyList(), 0, false, null)) }
    var spans by remember(session) { mutableStateOf(emptyList<TraceSpan>()) }
    var task by remember(session) { mutableStateOf<AgentTaskRecord?>(null) }
    var before by remember(session) { mutableStateOf<Long?>(null) }
    var limit by remember(session) { mutableIntStateOf(600) }
    var live by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember(session) { mutableStateOf<String?>(null) }
    var query by remember(session) { mutableStateOf("") }
    var kind by remember(session) { mutableStateOf<String?>(null) }
    var run by remember(session) { mutableStateOf<String?>(null) }
    var errorsOnly by remember(session) { mutableStateOf(false) }
    var waterfall by remember { mutableStateOf(true) }
    var selectedId by remember(session) { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var payloadSearch by remember(session) { mutableStateOf(false) }
    var matches by remember(session) { mutableStateOf(emptySet<Long>()) }
    var searching by remember { mutableStateOf(false) }
    val isRoot = session == conversation.id.toString()
    val selected = spans.find { it.id == selectedId }

    LaunchedEffect(session, limit, before, live, refresh, active) {
        var revision = -1L
        do {
            try {
                if (revision != journal.revision.value) {
                    busy = true
                    val readRevision = journal.revision.value
                    task = journal.task(session)
                    val next = journal.trajectory(session, limit, before)
                    val nextSpans = withContext(Dispatchers.Default) { buildTraceSpans(next.entries, if (isRoot) active else task?.status == "running") }
                    page = next
                    spans = nextSpans
                    error = next.error
                    revision = readRevision
                }
                now = System.currentTimeMillis()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            finally { busy = false }
            if (live) delay(1000)
        } while (live)
    }
    LaunchedEffect(session, query, payloadSearch, page.total, before) {
        matches = emptySet()
        if (payloadSearch && query.isNotBlank()) {
            delay(350)
            searching = true
            try { matches = journal.page(session, before = before, query = query, limit = 20_000).records.map { it.sequence }.toSet() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
            finally { searching = false }
        }
    }
    val filtered = remember(spans, query, kind, run, errorsOnly, matches) {
        spans.filter { span ->
            (kind == null || span.kind == kind) && (run == null || span.run == run || span.id == run) &&
                (!errorsOnly || span.state in setOf("error", "cancelled", "incomplete", "paused")) &&
                (span.matches(query) || span.entries.any { it.record.sequence in matches })
        }
    }
    val visible = filtered.filter { (it.kind != "task" || kind == "task" || !waterfall) && (it.kind != "event" || kind == "event") }
    val rangeSpans = spans.filter { run == null || it.run == run || it.id == run }
    val start = rangeSpans.minOfOrNull { it.start } ?: now
    val end = max(start + 1, rangeSpans.maxOfOrNull { it.end ?: if (it.state == "running") now else it.entries.last().record.timestamp } ?: now)
    val range = (end - start).coerceAtLeast(1)
    val axisScroll = rememberScrollState()

    Dialog(onDismissRequest = { if (selectedId != null) selectedId = null else if (parents.isNotEmpty()) session = parents.removeAt(parents.lastIndex) else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().testTag("trajectory"), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (selectedId != null) selectedId = null else if (parents.isNotEmpty()) session = parents.removeAt(parents.lastIndex) else onDismiss() }) {
                        Icon(if (selectedId != null || parents.isNotEmpty()) HugeIcons.ArrowLeft01 else HugeIcons.Cancel01,
                            contentDescription = stringResource(if (selectedId != null || parents.isNotEmpty()) R.string.jobs_back else R.string.jobs_close))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.trajectory_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.trace_event_count, visible.size, page.total), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { live = !live }) { Text(stringResource(if (live) R.string.trace_live else R.string.trace_paused)) }
                    IconButton(onClick = { refresh++ }, enabled = !busy) { Icon(HugeIcons.Refresh01, contentDescription = stringResource(R.string.jobs_refresh)) }
                }
                if (busy || searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                BoxWithConstraints(Modifier.weight(1f)) {
                    val wide = maxWidth >= 840.dp
                    Row(Modifier.fillMaxSize()) {
                        if (selected == null || wide) Column(Modifier.weight(1f).fillMaxHeight()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true,
                                    label = { Text(stringResource(R.string.trace_search_hint)) })
                                TextButton(onClick = { waterfall = !waterfall }) { Text(stringResource(if (waterfall) R.string.trace_waterfall else R.string.trace_flow)) }
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(null, "model", "tool", "subagent", "compaction", "task", "conversation", "event").forEach { value ->
                                    FilterChip(kind == value, { kind = value }, label = { Text(traceKind(value)) })
                                }
                                FilterChip(errorsOnly, { errorsOnly = !errorsOnly }, label = { Text(stringResource(R.string.trace_problems)) })
                                FilterChip(payloadSearch, { payloadSearch = !payloadSearch }, label = { Text(stringResource(R.string.trace_full_search)) })
                            }
                            val runs = spans.filter { it.kind == "task" }
                            if (runs.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(run == null, { run = null }, label = { Text(stringResource(R.string.trace_all_runs)) })
                                runs.forEachIndexed { index, item ->
                                    FilterChip(run == item.id, { run = item.id }, label = { Text("${stringResource(R.string.trace_run)} ${index + 1} · ${timeLabel(item.start)}") })
                                }
                            }
                            TraceOverview(rangeSpans, start, range, now)
                            val modelSpans = rangeSpans.filter { it.kind == "model" }
                            val measured = modelSpans.filter { (it.receivingMs ?: 0) > 0 && it.outputTokens != null }
                            val input = modelSpans.mapNotNull { it.inputTokens }.takeIf { it.isNotEmpty() }?.sum()?.toString() ?: "—"
                            val output = modelSpans.mapNotNull { it.outputTokens }.takeIf { it.isNotEmpty() }?.sum()?.toString() ?: "—"
                            val rate = measured.takeIf { it.isNotEmpty() }?.let { "%.1f".format(it.sumOf { it.outputTokens ?: 0 } * 1000.0 / it.sumOf { it.receivingMs ?: 0 }) } ?: "—"
                            Text(stringResource(R.string.trace_totals, input, output, rate), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                val modelCount = rangeSpans.count { it.kind == "model" }
                                val toolCount = rangeSpans.count { it.kind == "tool" }
                                Text("$modelCount ${traceKind("model")} · $toolCount ${traceKind("tool")} · ${traceDuration(range)}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                                if (waterfall) {
                                    TextButton(onClick = { zoom = (zoom / 2).coerceAtLeast(1f) }, enabled = zoom > 1) { Text("−") }
                                    Text("${zoom.toInt()}×", style = MaterialTheme.typography.labelSmall)
                                    TextButton(onClick = { zoom = (zoom * 2).coerceAtMost(16f) }, enabled = zoom < 16) { Text("+") }
                                }
                            }
                            if (waterfall) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                Text(stringResource(R.string.trace_operation), Modifier.width(132.dp), style = MaterialTheme.typography.labelSmall)
                                BoxWithConstraints(Modifier.weight(1f)) {
                                    val width = maxWidth * zoom
                                    Row(Modifier.horizontalScroll(axisScroll).width(width), horizontalArrangement = Arrangement.SpaceBetween) {
                                        (0..4).forEach { Text(traceDuration(range * it / 4), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                }
                            }
                            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (before != null) item {
                                    TextButton(onClick = { before = null; limit = 600; run = null; selectedId = null }, modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.trace_latest))
                                    }
                                }
                                if (page.hasEarlier) item {
                                    OutlinedButton(onClick = {
                                        if (limit < 20_000) limit = (limit + 2000).coerceAtMost(20_000)
                                        else { before = page.entries.minOfOrNull { it.record.sequence }; run = null; selectedId = null }
                                    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(if (limit < 20_000) R.string.trace_load_earlier else R.string.trace_window_limit))
                                    }
                                }
                                if (visible.isEmpty()) item {
                                    Text(stringResource(if (page.total == 0L) R.string.trajectory_empty else R.string.trace_no_matches), Modifier.padding(vertical = 24.dp))
                                }
                                items(visible, key = { it.id }) { span ->
                                    if (waterfall) TraceWaterfallRow(span, start, range, now, zoom, axisScroll, span.id == selectedId) { selectedId = span.id }
                                    else TraceFlowRow(span, spans, span.id == selectedId, { selectedId = span.id }, { selectedId = it })
                                }
                                if (task != null) item {
                                    val saved = task!!
                                    OutlinedCard(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(stringResource(R.string.trajectory_checkpoint), style = MaterialTheme.typography.titleSmall)
                                            Text("${saved.status} · ${saved.reason?.name.orEmpty()} · ${saved.steps} ${stringResource(R.string.trace_steps)}", style = MaterialTheme.typography.bodySmall)
                                            saved.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                            if (isRoot && !active && saved.status != "completed" && saved.reason != GenerationStopReason.WAITING_APPROVAL) {
                                                FilledTonalButton(onClick = { onResume(); onDismiss() }) { Text(stringResource(R.string.trajectory_resume)) }
                                            }
                                        }
                                    }
                                }
                                item { Text(stringResource(R.string.trace_scope), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                        if (selected != null) {
                            if (wide) VerticalDivider()
                            TraceInspector(journal, session, selected, Modifier.then(if (wide) Modifier.width((maxWidth * .6f).coerceAtLeast(400.dp)) else Modifier.fillMaxSize()),
                                related = spans, onSelect = { selectedId = it }, onChild = { child -> parents.add(session); session = child })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun traceKind(kind: String?): String = stringResource(when (kind) {
    "model" -> R.string.trace_models
    "tool" -> R.string.trace_tools
    "subagent" -> R.string.trace_subagents
    "compaction" -> R.string.trace_compaction
    "task" -> R.string.trace_runs
    "conversation" -> R.string.trace_conversation
    "event" -> R.string.trace_events
    else -> R.string.trajectory_all
})

@Composable
private fun traceState(state: String): String = stringResource(when (state) {
    "completed" -> R.string.trace_completed
    "running" -> R.string.trace_running
    "error" -> R.string.trace_failed
    "cancelled" -> R.string.trace_cancelled
    "paused" -> R.string.trace_paused
    "incomplete" -> R.string.trace_incomplete
    else -> R.string.trace_recorded
})

@Composable
private fun traceColor(kind: String, state: String = ""): Color = when {
    state in setOf("error", "cancelled") -> MaterialTheme.colorScheme.error
    kind == "model" -> MaterialTheme.colorScheme.primary
    kind == "tool" -> MaterialTheme.colorScheme.tertiary
    kind == "subagent" -> MaterialTheme.colorScheme.secondary
    kind == "compaction" -> MaterialTheme.colorScheme.error.copy(alpha = .65f)
    else -> MaterialTheme.colorScheme.outline
}
private fun traceDuration(ms: Long): String = when {
    ms < 1000 -> "${ms.coerceAtLeast(0)} ms"
    ms < 60_000 -> "%.1f s".format(ms / 1000.0)
    else -> progressDuration(ms)
}
private fun timeLabel(at: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))

@Composable
private fun TraceOverview(spans: List<TraceSpan>, start: Long, range: Long, now: Long) {
    val lanes = listOf("model", "tool", "subagent", "compaction")
    val colors = lanes.associateWith { traceColor(it) }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val description = stringResource(R.string.trace_overview)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            lanes.forEach { kind -> Text(traceKind(kind), color = colors.getValue(kind), style = MaterialTheme.typography.labelSmall) }
        }
        Canvas(Modifier.fillMaxWidth().height(52.dp).padding(top = 8.dp).semantics { contentDescription = description }) {
            (0..4).forEach { val x = size.width * it / 4; drawLine(grid, Offset(x, 0f), Offset(x, size.height)) }
            spans.forEach { span ->
                val lane = lanes.indexOf(span.kind)
                if (lane >= 0) {
                    val x = ((span.start - start).toDouble() / range * size.width).toFloat().coerceIn(0f, size.width)
                    val until = span.end ?: if (span.state == "running") now else span.entries.last().record.timestamp
                    val width = ((until - span.start).coerceAtLeast(0).toDouble() / range * size.width).toFloat().coerceAtLeast(3f).coerceAtMost((size.width - x).coerceAtLeast(0f))
                    drawRoundRect(colors.getValue(span.kind), Offset(x, lane * size.height / 4), Size(width, size.height / 4 - 3), CornerRadius(2f))
                }
            }
        }
    }
}

@Composable
private fun TraceWaterfallRow(span: TraceSpan, start: Long, range: Long, now: Long, zoom: Float, scroll: androidx.compose.foundation.ScrollState, selected: Boolean, onClick: () -> Unit) {
    val color = traceColor(span.kind, span.state)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val waitingColor = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surfaceContainerLow, color, .28f)
    val state = traceState(span.state)
    val title = span.title.ifBlank { traceKind(span.kind) }
    val duration = span.durationMs ?: if (span.state == "running") (now - span.start).coerceAtLeast(0) else null
    Row(Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp))
        .clickable(onClick = onClick).padding(vertical = 10.dp).semantics { contentDescription = "$title, $state" }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(132.dp).padding(horizontal = 8.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("#${span.entries.first().record.sequence} · $state", color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (span.preview.isNotBlank()) Text(span.preview, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val width = maxWidth * zoom
            Box(Modifier.horizontalScroll(scroll).width(width).height(42.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    (0..4).forEach { val x = size.width * it / 4; drawLine(grid, Offset(x, 0f), Offset(x, size.height)) }
                    val left = ((span.start - start).toDouble() / range * size.width).toFloat().coerceIn(0f, size.width)
                    val until = span.end ?: if (span.state == "running") now else span.entries.last().record.timestamp
                    val barWidth = ((until - span.start).coerceAtLeast(0).toDouble() / range * size.width).toFloat().coerceAtLeast(4f).coerceAtMost((size.width - left).coerceAtLeast(0f))
                    drawRoundRect(color.copy(alpha = if (span.state == "incomplete") .3f else .8f), Offset(left, 4.dp.toPx()), Size(barWidth, 16.dp.toPx()), CornerRadius(4.dp.toPx()))
                    span.firstContentMs?.let { wait ->
                        val waitWidth = (wait.toDouble() / range * size.width).toFloat().coerceIn(0f, barWidth)
                        drawRect(waitingColor, Offset(left, 4.dp.toPx()), Size(waitWidth, 16.dp.toPx()))
                    }
                }
                Text(duration?.let(::traceDuration) ?: "—", Modifier.align(Alignment.BottomStart).padding(start = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TraceFlowRow(span: TraceSpan, spans: List<TraceSpan>, selected: Boolean, onClick: () -> Unit, onParent: (String) -> Unit) {
    val color = traceColor(span.kind, span.state)
    val indent = if (span.parent != null) 20.dp else 0.dp
    Row(Modifier.fillMaxWidth().padding(start = indent), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(20.dp).height(76.dp)) {
            drawLine(color.copy(alpha = .3f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 2.dp.toPx())
            drawCircle(color, 4.dp.toPx(), Offset(size.width / 2, size.height / 2))
        }
        OutlinedCard(onClick = onClick, modifier = Modifier.weight(1f), colors = CardDefaults.outlinedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(span.title.ifBlank { traceKind(span.kind) }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(span.durationMs?.let(::traceDuration) ?: "—", style = MaterialTheme.typography.labelSmall)
                }
                Text("${timeLabel(span.start)} · ${traceState(span.state)} · #${span.entries.first().record.sequence}", color = color, style = MaterialTheme.typography.labelSmall)
                if (span.preview.isNotBlank()) Text(span.preview, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val parent = spans.find { it.id == span.parent }
                if (parent != null) TextButton(onClick = { onParent(parent.id) }, contentPadding = PaddingValues(0.dp)) {
                    Text("${stringResource(R.string.trace_parent)} #${parent.entries.first().record.sequence}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun TraceInspector(journal: SessionJournal, session: String, span: TraceSpan, modifier: Modifier, related: List<TraceSpan>, onSelect: (String) -> Unit, onChild: (String) -> Unit) {
    var tab by remember(span.id) { mutableIntStateOf(0) }
    var index by remember(span.id, tab) { mutableIntStateOf(0) }
    var body by remember(span.id, tab, index) { mutableStateOf<JsonElement?>(null) }
    var error by remember(span.id, tab, index) { mutableStateOf<String?>(null) }
    var actionMessage by remember(span.id) { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val records = remember(span, tab) {
        when (tab) {
            1 -> span.entries.filter { it.summary.phase == "start" || it.record.source == "compaction.event" }.take(1)
            2 -> span.entries.filter { it.record.source == "model.stream" || it.summary.phase == "end" }
            else -> span.entries
        }
    }
    val record = records.getOrNull(index.coerceAtMost((records.size - 1).coerceAtLeast(0)))?.record
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            exporting = true
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { writer ->
                        writer.write("[\n")
                        span.entries.forEachIndexed { i, entry ->
                            if (i > 0) writer.write(",\n")
                            val payload = Json.parseToJsonElement(journal.payload(session, entry.record))
                            writer.write(buildJsonObject {
                                put("sequence", entry.record.sequence); put("timestamp", entry.record.timestamp)
                                put("source", entry.record.source); put("payload", payload)
                            }.toString())
                        }
                        writer.write("\n]")
                    }
                }
                actionMessage = context.getString(R.string.trace_export_done)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { actionMessage = e.message }
            finally { exporting = false }
        }
    }
    LaunchedEffect(session, record, tab) {
        body = null
        error = null
        if (tab > 0 && record != null) {
            try { body = Json.parseToJsonElement(journal.payload(session, record)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message }
        }
    }
    val peers = related.filter { it.kind != "event" }
    val position = peers.indexOfFirst { it.id == span.id }
    @Composable fun ContextPanel() {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.trace_operation_context), style = MaterialTheme.typography.titleSmall)
                TraceFact(stringResource(R.string.trace_started), DateFormat.getDateTimeInstance().format(Date(span.start)))
                TraceFact(stringResource(R.string.trace_duration), span.durationMs?.let(::traceDuration) ?: "—")
                span.firstContentMs?.let { TraceFact(stringResource(R.string.trace_first_content), traceDuration(it)) }
                if (span.inputTokens != null || span.outputTokens != null) TraceFact(stringResource(R.string.trace_tokens), "↑ ${span.inputTokens ?: "—"} · ↓ ${span.outputTokens ?: "—"}")
                if ((span.receivingMs ?: 0) > 0 && span.outputTokens != null) {
                    TraceFact(stringResource(R.string.trace_rate), "%.1f tok/s".format(span.outputTokens * 1000.0 / requireNotNull(span.receivingMs)))
                }
                TraceFact(stringResource(R.string.trace_events), "${span.entries.size} · #${span.entries.first().record.sequence}–#${span.entries.last().record.sequence}")
                if (span.partial) Text(stringResource(R.string.trace_partial), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                HorizontalDivider()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Operation ID", span.id)))
                        actionMessage = context.getString(R.string.trace_copied)
                    } }) { Text(stringResource(R.string.trace_copy_id)) }
                    TextButton(onClick = { export.launch("operation-${span.entries.first().record.sequence}.json") }, enabled = !exporting) { Text(stringResource(R.string.trace_export_operation)) }
                    span.parent?.takeIf { id -> related.any { it.id == id } }?.let { parent ->
                        TextButton(onClick = { onSelect(parent) }) { Text(stringResource(R.string.trace_parent)) }
                    }
                    span.childConversation?.let { child -> TextButton(onClick = { onChild(child) }) { Text(stringResource(R.string.trace_open_child)) } }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { onSelect(peers[position - 1].id) }, enabled = position > 0) { Text(stringResource(R.string.trace_previous_operation)) }
                    TextButton(onClick = { onSelect(peers[position + 1].id) }, enabled = position >= 0 && position + 1 < peers.size) { Text(stringResource(R.string.trace_next_operation)) }
                }
                if (exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                actionMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    BoxWithConstraints(modifier.testTag("trace-inspector")) {
        val sidePanel = maxWidth >= 640.dp
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(span.title.ifBlank { traceKind(span.kind) }, style = MaterialTheme.typography.titleLarge)
                    Text("${traceKind(span.kind)} · ${traceState(span.state)}", color = traceColor(span.kind, span.state), style = MaterialTheme.typography.labelMedium)
                }
                if (!sidePanel) item { ContextPanel() }
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(R.string.trace_overview, R.string.trace_input, R.string.trace_output, R.string.trace_events).forEachIndexed { i, label ->
                            FilterChip(tab == i, { tab = i }, label = { Text(stringResource(label)) })
                        }
                    }
                }
                if (tab == 0) {
                    if (span.preview.isNotBlank()) item { SelectionContainer { Text(span.preview, style = MaterialTheme.typography.bodyMedium) } }
                    val children = related.filter { it.parent == span.id }
                    if (children.isNotEmpty()) item { Text(stringResource(R.string.trace_related_operations), style = MaterialTheme.typography.titleSmall) }
                    items(children, key = { "linked-${it.id}" }) { child ->
                        OutlinedCard(onClick = { onSelect(child.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(child.title, style = MaterialTheme.typography.titleSmall)
                                Text("${traceState(child.state)} · ${child.durationMs?.let(::traceDuration) ?: "—"}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item { Text(stringResource(R.string.trace_event_timeline), style = MaterialTheme.typography.titleSmall) }
                    items(span.entries, key = { "event-${it.record.sequence}" }) { entry ->
                        Row(Modifier.fillMaxWidth().clickable { tab = 3; index = span.entries.indexOf(entry) }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("+${traceDuration(entry.record.timestamp - span.start)}", Modifier.width(80.dp), style = MaterialTheme.typography.labelSmall, color = traceColor(span.kind))
                            Column { Text(entry.record.source, style = MaterialTheme.typography.bodySmall); Text("#${entry.record.sequence}", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    if (span.kind == "model") item { Text(stringResource(R.string.trace_rate_help), style = MaterialTheme.typography.bodySmall) }
                } else {
                    if (records.isEmpty()) item { Text(stringResource(R.string.trace_no_payload)) }
                    if (records.size > 1) item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { index = (index - 1).coerceAtLeast(0) }, enabled = index > 0) { Text("←") }
                            Text("${index + 1} / ${records.size}", style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { index++ }, enabled = index + 1 < records.size) { Text("→") }
                        }
                    }
                    record?.let { item { Text("#${it.sequence} · ${it.source} · ${timeLabel(it.timestamp)}", style = MaterialTheme.typography.labelSmall) } }
                    error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                    if (record != null && body == null && error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    body?.let { payload ->
                        item { TextButton(onClick = { scope.launch {
                            val data = payload as? JsonObject
                            val text = if (tab == 1 && record?.source == "tool.started") (data?.get("input") as? JsonPrimitive)?.contentOrNull ?: payload.toString() else payload.toString()
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Trace payload", text)))
                            actionMessage = context.getString(R.string.trace_copied)
                        } }) { Text(stringResource(R.string.trace_copy_payload)) } }
                        item {
                            if (tab == 3) TraceValue(stringResource(R.string.trajectory_content), payload, expandedInitially = true)
                            else TraceReadablePayload(record?.source.orEmpty(), payload)
                        }
                    }
                }
            }
            if (sidePanel) LazyColumn(Modifier.width(264.dp), contentPadding = PaddingValues(top = 16.dp, end = 16.dp, bottom = 16.dp)) { item { ContextPanel() } }
        }
    }
}

@Composable
private fun TraceFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}
/** Bounded, selectable tree. Long strings and arrays have pages rather than horizontal scrolling. */
@Composable
private fun TraceValue(label: String, value: JsonElement, expandedInitially: Boolean = false) {
    if (value is JsonPrimitive && (value.contentOrNull?.length ?: 0) < 500 && '\n' !in value.content) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, Modifier.weight(.4f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer(Modifier.weight(.6f)) { Text(value.contentOrNull ?: "null", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
        }
        return
    }
    var expanded by remember(value) { mutableStateOf(expandedInitially) }
    var offset by remember(value) { mutableIntStateOf(0) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text("${if (expanded) "▾" else "▸"} $label", style = MaterialTheme.typography.labelLarge)
            }
            if (expanded) when (value) {
                is JsonObject -> {
                    value.entries.drop(offset).take(20).forEach { (field, item) -> key(field) { TraceValue(field, item, item is JsonPrimitive) } }
                    TracePages(offset, 20, value.size) { offset = it }
                }
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

/** Human-readable input/output; the Events tab always retains the exact structured record. */
@Composable
private fun TraceReadablePayload(source: String, value: JsonElement) {
    val data = value as? JsonObject ?: return TraceValue(stringResource(R.string.trajectory_content), value, true)
    fun text(item: JsonObject, key: String) = (item[key] as? JsonPrimitive)?.contentOrNull
    fun parsed(raw: String?): JsonElement? = raw?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (source) {
            "model.request" -> {
                val messages = data["messages"] as? JsonArray ?: JsonArray(emptyList())
                var offset by remember(value) { mutableIntStateOf(0) }
                messages.drop(offset).take(10).forEachIndexed { index, item ->
                    val message = item as? JsonObject ?: return@forEachIndexed
                    Text("${offset + index + 1} · ${text(message, "role").orEmpty()}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    TraceMessageParts(message["parts"] as? JsonArray ?: JsonArray(emptyList()))
                }
                TracePages(offset, 10, messages.size) { offset = it }
                data["tools"]?.let { TraceValue(stringResource(R.string.trace_tools), it) }
                TraceValue(stringResource(R.string.trace_metadata), JsonObject(data.filterKeys { it !in setOf("messages", "tools") }))
            }
            "model.stream" -> {
                val chunks = (data["chunks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                // Adjacent chunks of the same kind are joined for readability without
                // merging reasoning, answer text and tool arguments into one stream.
                val groups = mutableListOf<Pair<String, StringBuilder>>()
                chunks.forEach { chunk ->
                    val type = text(chunk, "type").orEmpty()
                    val content = text(chunk, "text") ?: text(chunk, "inputDelta")
                    if (content != null) {
                        if (groups.lastOrNull()?.first == type) groups.last().second.append(content)
                        else groups.add(type to StringBuilder(content))
                    } else if (type !in setOf("text_start", "text_end", "reasoning_start", "reasoning_end")) {
                        groups.add(type to StringBuilder(chunk.toString()))
                    }
                }
                groups.forEach { (type, content) ->
                    TraceValue(when (type) {
                        "text_delta" -> "text"
                        "reasoning_delta" -> "reasoning"
                        else -> type
                    }, JsonPrimitive(content.toString()), true)
                }
            }
            "tool.started" -> TraceValue(stringResource(R.string.trace_input), parsed(text(data, "input")) ?: data["input"] ?: value, true)
            "tool.result" -> {
                data["error"]?.let { TraceValue(stringResource(R.string.trace_failed), it, true) }
                TraceMessageParts(data["output"] as? JsonArray ?: JsonArray(emptyList()))
            }
            "model.response" -> {
                val response = data["response"] as? JsonObject
                if (response != null) TraceValue(stringResource(R.string.trace_output), response, true)
                else TraceValue(stringResource(R.string.trace_metadata), value, true)
            }
            "compaction.event" -> {
                val event = data["event"] as? JsonObject
                if (event != null) {
                    TraceValue(stringResource(R.string.trace_metadata), parsed(text(event, "input")) ?: JsonObject(emptyMap()), true)
                    TraceMessageParts(event["output"] as? JsonArray ?: JsonArray(emptyList()))
                }
            }
            else -> TraceValue(stringResource(R.string.trajectory_content), value, true)
        }
    }
}

@Composable
private fun TraceMessageParts(parts: JsonArray) {
    var offset by remember(parts) { mutableIntStateOf(0) }
    parts.drop(offset).take(10).forEach { item ->
        val part = item as? JsonObject ?: return@forEach
        val raw = (part["text"] as? JsonPrimitive)?.contentOrNull
        val objectValue = raw?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        if (objectValue != null && ("stdout" in objectValue || "stderr" in objectValue)) {
            listOf("stdout", "stderr").forEach { stream ->
                objectValue[stream]?.let { TraceValue(stream, it, true) }
            }
            TraceValue(stringResource(R.string.trace_metadata), JsonObject(objectValue.filterKeys { it !in setOf("stdout", "stderr") }))
        } else if (raw != null) TraceValue("text", JsonPrimitive(raw), true)
        else if (part["reasoning"] != null) TraceValue("reasoning", part.getValue("reasoning"), true)
        else TraceValue((part["type"] as? JsonPrimitive)?.contentOrNull.orEmpty(), item)
    }
    TracePages(offset, 10, parts.size) { offset = it }
}
