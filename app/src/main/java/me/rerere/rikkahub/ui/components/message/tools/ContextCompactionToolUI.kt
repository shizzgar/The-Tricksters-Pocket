package me.rerere.rikkahub.ui.components.message.tools

import android.content.ClipData
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.ContextCompactionPresentation
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/** Shared renderer for live/manual/automatic and legacy compression events. */
internal object ContextCompactionToolUI : ToolUIRenderer {
    override val toolName = ContextCompactionPresentation.TOOL_NAME
    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.MagicWand01
    override fun hasSummary(context: ToolUIContext) = true

    @Composable
    override fun title(context: ToolUIContext): String {
        val details = remember(context.tool) { compressionDetails(context.tool, ContextCompactionPresentation.runtimeId) }
        val elapsed = rememberElapsed(details)
        val title = stringResource(R.string.setting_model_page_prompt_compress)
        return if (elapsed != null) "$title · ${formatCompressionDuration(elapsed)}" else title
    }

    @Composable
    override fun Summary(context: ToolUIContext) {
        val details = remember(context.tool) { compressionDetails(context.tool, ContextCompactionPresentation.runtimeId) }
        Text(
            stringResource(if (details.isManual) R.string.compression_mode_manual else R.string.compression_mode_auto) +
                " · " + stringResource(details.state.label()),
            style = MaterialTheme.typography.labelMedium,
            color = if (details.state == CompressionState.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (details.state == CompressionState.RUNNING) {
            Text(phase(details), style = MaterialTheme.typography.bodySmall)
        } else if (details.state == CompressionState.COMPLETED) {
            val before = details.number("source_token_estimate")
            val after = details.number("summary_token_estimate")
            if (before != null && after != null) Text(
                stringResource(R.string.compression_inline_tokens, tokens(before), tokens(after)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val details = remember(context.tool) { compressionDetails(context.tool, ContextCompactionPresentation.runtimeId) }
        val sections = remember(details.summary) { compressionSummarySections(details.summary) }
        val elapsed = rememberElapsed(details)
        var raw by remember(context.tool.toolCallId) { mutableStateOf(false) }
        var cancelling by remember(context.tool.toolCallId) { mutableStateOf(false) }
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        val surfaceColor = when (details.state) {
            CompressionState.COMPLETED -> MaterialTheme.colorScheme.primaryContainer
            CompressionState.FAILED -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.secondaryContainer
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(HugeIcons.MagicWand01, contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.setting_model_page_prompt_compress), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(if (details.isManual) R.string.compression_mode_manual else R.string.compression_mode_auto),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.compression_close)) }
                }
            }
            item {
                Surface(color = surfaceColor, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(details.state.label()), style = MaterialTheme.typography.titleMedium)
                        elapsed?.let {
                            Text(formatCompressionDuration(it), style = MaterialTheme.typography.headlineMedium,
                                fontFamily = FontFamily.Monospace)
                            Text(stringResource(R.string.compression_duration), style = MaterialTheme.typography.labelSmall)
                        }
                        if (details.state == CompressionState.RUNNING) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(phase(details), style = MaterialTheme.typography.bodyMedium)
                            if (ContextCompactionPresentation.canCancel(context.tool.toolCallId)) {
                                if (!details.isManual) Text(stringResource(R.string.compression_cancel_auto_note), style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(enabled = !cancelling, onClick = {
                                    cancelling = true
                                    ContextCompactionPresentation.cancel(context.tool.toolCallId)
                                }) { Text(stringResource(if (cancelling) R.string.compression_cancelling else R.string.cancel)) }
                            }
                        }
                        if (details.state == CompressionState.INTERRUPTED) Text(stringResource(R.string.compression_interrupted_note))
                        if (details.state in listOf(CompressionState.FAILED, CompressionState.CANCELLED)) {
                            Text(stringResource(R.string.compression_failed_note), style = MaterialTheme.typography.bodySmall)
                        }
                        details.text("error")?.takeIf { it.isNotBlank() }?.let {
                            SelectionContainer { Text(it, modifier = Modifier.fillMaxWidth(), softWrap = true) }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Metric(stringResource(R.string.compression_before), details.number("source_token_estimate"), Modifier.weight(1f))
                    Metric(stringResource(R.string.compression_after), details.number("summary_token_estimate"), Modifier.weight(1f))
                }
                Text(stringResource(R.string.compression_estimate_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        (details.text("model") ?: details.text("summary_model_id"))?.let { Field(stringResource(R.string.compression_model), it) }
                        details.number("started_at_ms")?.let {
                            Field(stringResource(R.string.compression_started), DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)))
                        }
                        if (elapsed == null) Field(stringResource(R.string.compression_duration), stringResource(R.string.compression_not_recorded))
                        details.number("target_tokens")?.let { Field(stringResource(R.string.compression_target), tokens(it)) }
                        details.number("retained_raw_tool_calls")?.let { Field(stringResource(R.string.compression_retained), tokens(it)) }
                        details.number("operation_timeout_ms")?.let { Field(stringResource(R.string.compression_budget), formatCompressionDuration(it)) }
                        if (details.text("original_history_available") == "true") {
                            Text(stringResource(R.string.compression_history_kept), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (details.summary.isNotBlank()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.compression_summary), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Context compression", details.summary)))
                        } }) { Text(stringResource(R.string.compression_copy)) }
                    }
                }
                compressionSummaryContent(sections)
            }
            item {
                TextButton(onClick = { raw = !raw }) {
                    Text(stringResource(if (raw) R.string.compression_hide_metadata else R.string.compression_show_metadata))
                }
            }
            if (raw) {
                item { CompressionPlainBlock(stringResource(R.string.compression_event_metadata), context.tool.input) }
                if (details.summary.isNotBlank()) item {
                    CompressionPlainBlock(stringResource(R.string.compression_original_summary), details.summary)
                }
            }
        }
    }
}

@Composable
private fun rememberElapsed(details: CompressionDetails): Long? {
    var now by remember(details.text("started_elapsed_ms")) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(details.state, details.text("started_elapsed_ms")) {
        while (details.state == CompressionState.RUNNING) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    return details.elapsedMs(now)
}

@Composable
private fun phase(details: CompressionDetails): String {
    val text = stringResource(when (details.text("phase")) {
        "summarizing" -> R.string.compression_phase_summary
        "merging" -> R.string.compression_phase_merge
        "saving" -> R.string.compression_phase_save
        else -> R.string.compression_phase_prepare
    })
    val pass = details.number("pass")
    val parts = details.number("parts")
    return if (pass != null && parts != null) text + " · " + stringResource(R.string.compression_pass, pass, parts) else text
}

private fun CompressionState.label() = when (this) {
    CompressionState.RUNNING -> R.string.compression_running
    CompressionState.COMPLETED -> R.string.compression_completed
    CompressionState.FAILED -> R.string.compression_failed
    CompressionState.CANCELLED -> R.string.compression_cancelled
    CompressionState.INTERRUPTED -> R.string.compression_interrupted
    CompressionState.UNKNOWN -> R.string.compression_unknown
}

private fun tokens(value: Long) = NumberFormat.getIntegerInstance().format(value)

@Composable
private fun Metric(label: String, value: Long?, modifier: Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value?.let(::tokens) ?: "—", style = MaterialTheme.typography.titleLarge, softWrap = true)
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, modifier = Modifier.fillMaxWidth(), softWrap = true, style = MaterialTheme.typography.bodyMedium) }
    }
}
