package me.rerere.rikkahub.ui.components.message.tools

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.utils.JsonInstantPretty

internal val TermuxToolUIs: List<ToolUIRenderer> = listOf(
    "termux_run_command", "termux_session_start", "termux_session_send",
    "termux_session_read", "termux_session_list", "termux_session_kill", "termux_session_manage",
    "termux_job_start", "termux_job_read", "termux_job_wait", "termux_job_cancel", "termux_job_list", "termux_job_forget", "termux_output_read",
).map { TermuxToolUI(it) }

private class TermuxToolUI(override val toolName: String) : ToolUIRenderer {
    override fun icon(context: ToolUIContext) = HugeIcons.ComputerTerminal01

    @Composable
    override fun title(context: ToolUIContext): String = stringResource(when (toolName) {
        "termux_session_start" -> R.string.termux_preview_start
        "termux_session_send" -> R.string.termux_preview_send
        "termux_session_read" -> R.string.termux_preview_read
        "termux_session_list" -> R.string.termux_preview_list
        "termux_session_kill" -> R.string.termux_preview_kill
        else -> R.string.termux_preview_command
    })

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val view = remember(context) {
            presentTermux(toolName, context.arguments, context.content, context.loading,
                context.tool.executionStartedAt != null, context.tool.output.isNotEmpty(),
                denied = context.tool.approvalState is ToolApprovalState.Denied && !context.tool.isExecuted,
                pendingApproval = context.tool.isPending)
        }
        val out = view.output
        var raw by remember(context.tool.toolCallId) { mutableStateOf(false) }
        val failure = view.status in listOf(TermuxStatus.FAILED, TermuxStatus.TIMEOUT, TermuxStatus.DENIED)
        val statusColor = when {
            failure -> MaterialTheme.colorScheme.errorContainer
            view.status == TermuxStatus.COMPLETED -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.secondaryContainer
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(HugeIcons.ComputerTerminal01, contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text(title(context), style = MaterialTheme.typography.titleLarge)
                        Text(toolName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.termux_preview_close)) }
                }
            }
            item {
                Surface(color = statusColor, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(view.status.label()), style = MaterialTheme.typography.titleMedium)
                        view.exitCode?.let { Text(stringResource(R.string.termux_preview_exit, it), fontFamily = FontFamily.Monospace) }
                        if (view.status == TermuxStatus.TIMEOUT) Text(stringResource(R.string.termux_preview_timeout_note))
                        if (view.status == TermuxStatus.DISPATCHED) Text(stringResource(R.string.termux_preview_dispatch_note))
                        if (view.status == TermuxStatus.SESSION_UPDATED) Text(stringResource(R.string.termux_preview_session_note))
                    }
                }
            }
            view.command?.let { command ->
                item { TerminalBlock(stringResource(R.string.termux_preview_command), command) }
            }
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        view.sessionId?.let { Field(stringResource(R.string.termux_preview_session), it) }
                        view.pid?.let { Field("PID", it) }
                        view.mode?.let { Field(stringResource(R.string.termux_preview_mode), it) }
                        (context.arguments as? JsonObject)?.forEach { (key, value) ->
                            if (key !in setOf("command", "input", "executable", "arguments", "session_id")) {
                                Field(argumentLabel(key), displayValue(value))
                            }
                        }
                        if ((context.arguments as? JsonObject).isNullOrEmpty() && view.sessionId == null && view.mode == null) {
                            Text(stringResource(R.string.termux_preview_no_parameters), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            listOf("error", "reason", "note", "recovery").forEach { key ->
                out?.get(key)?.let { value -> item { TerminalBlock(argumentLabel(key), displayValue(value)) } }
            }
            listOf("job_id", "operation_id", "state", "stop_reason", "output_ref", "archive_truncated", "logs_truncated", "wait_timed_out", "cancel_confirmed", "next_cursor", "has_more", "log_path", "cancel_scope").forEach { key ->
                out?.get(key)?.let { value -> item { Field(argumentLabel(key), displayValue(value)) } }
            }
            listOf("stdout", "stderr", "screen", "text").forEach { key ->
                out?.get(key)?.let { value -> item { TerminalBlock(argumentLabel(key), displayValue(value), key == "stderr") } }
            }
            out?.get("matched_wait_for")?.let { value ->
                item { Field(stringResource(R.string.termux_preview_matched), displayValue(value)) }
            }
            ((out?.get("sessions") ?: out?.get("jobs")) as? JsonArray)?.let { sessions ->
                if (sessions.isEmpty()) item { Text(stringResource(R.string.termux_preview_no_sessions)) }
                sessions.forEach { session -> item {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            (session as? JsonObject)?.forEach { (key, value) -> Field(argumentLabel(key), displayValue(value)) }
                                ?: Text(displayValue(session))
                        }
                    }
                } }
            }
            // Budget envelopes, malformed/legacy results and additional output parts must stay visible.
            context.tool.output.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> if (out == null || part != context.tool.output.firstOrNull()) {
                        item { TerminalBlock(stringResource(R.string.termux_preview_output), part.text) }
                    }
                    is UIMessagePart.Image -> item { ZoomableAsyncImage(part.url, null, Modifier.fillMaxWidth()) }
                    else -> Unit
                }
            }
            item {
                TextButton(onClick = { raw = !raw }) {
                    Text(stringResource(if (raw) R.string.termux_preview_hide_json else R.string.termux_preview_show_json))
                }
            }
            if (raw) {
                item { TerminalBlock(stringResource(R.string.termux_preview_arguments), context.tool.input) }
                context.tool.output.filterIsInstance<UIMessagePart.Text>().forEach { part -> item {
                    TerminalBlock(stringResource(R.string.termux_preview_result), part.text)
                } }
            }
        }
    }
}

private fun TermuxStatus.label(): Int = when (this) {
    TermuxStatus.PENDING -> R.string.termux_preview_pending
    TermuxStatus.APPROVAL -> R.string.termux_preview_approval
    TermuxStatus.DENIED -> R.string.termux_preview_denied
    TermuxStatus.RUNNING -> R.string.termux_preview_running
    TermuxStatus.UNKNOWN -> R.string.termux_preview_unknown
    TermuxStatus.COMPLETED -> R.string.termux_preview_completed
    TermuxStatus.FAILED -> R.string.termux_preview_failed
    TermuxStatus.TIMEOUT -> R.string.termux_preview_timeout
    TermuxStatus.DISPATCHED -> R.string.termux_preview_dispatched
    TermuxStatus.SESSION_UPDATED -> R.string.termux_preview_session_updated
}

private fun displayValue(value: JsonElement): String =
    (value as? JsonPrimitive)?.contentOrNull ?: JsonInstantPretty.encodeToString(value)

@Composable
private fun argumentLabel(key: String): String = when (key) {
    "working_dir" -> stringResource(R.string.termux_preview_directory)
    "timeout_seconds" -> stringResource(R.string.termux_preview_timeout_seconds)
    "background" -> stringResource(R.string.termux_preview_background)
    "interactive" -> stringResource(R.string.termux_preview_interactive)
    "wait_for" -> stringResource(R.string.termux_preview_wait_for)
    "keys" -> stringResource(R.string.termux_preview_keys)
    "enter" -> stringResource(R.string.termux_preview_enter)
    "session_id" -> stringResource(R.string.termux_preview_session)
    "screen" -> stringResource(R.string.termux_preview_screen)
    "error" -> stringResource(R.string.termux_preview_error)
    "reason" -> stringResource(R.string.termux_preview_reason)
    "note" -> stringResource(R.string.termux_preview_note)
    "recovery" -> stringResource(R.string.termux_preview_recovery)
    else -> key
}

@Composable
private fun Field(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, modifier = Modifier.fillMaxWidth(), softWrap = true,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr)) }
    }
}

/** Plain text and visual soft wraps preserve shell syntax and safe copy/paste. No HTML, no execution. */
@Composable
private fun TerminalBlock(label: String, text: String, error: Boolean = false) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var shown by remember(text) { mutableIntStateOf(12_000) }
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text))) } }) {
                    Text(stringResource(R.string.code_block_copy))
                }
            }
            SelectionContainer {
                Text(
                    text = if (text.isEmpty()) stringResource(R.string.termux_preview_empty) else text.take(shown),
                    modifier = Modifier.fillMaxWidth(), softWrap = true,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp, lineHeight = 18.sp, textDirection = TextDirection.Ltr,
                        fontFeatureSettings = "'calt' 0, 'liga' 0, 'clig' 0"),
                )
            }
            if (shown < text.length) TextButton(onClick = { shown += 12_000 }) {
                Text(stringResource(R.string.termux_preview_more, text.length - shown))
            }
        }
    }
}
