package me.rerere.rikkahub.ui.components.message.tools

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock

/** Keep cards as lazy items, so opening a long evidence index does not compose all its outputs. */
internal fun LazyListScope.compressionSummaryContent(sections: List<CompressionSection>) {
    sections.forEach { section ->
        when (section) {
            is CompressionSection.Markdown -> item {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                    SelectionContainer {
                        MarkdownBlock(section.text, modifier = Modifier.fillMaxWidth().padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            is CompressionSection.Requests -> {
                item { SectionHeading(stringResource(R.string.compression_requests_title, section.entries.size),
                    stringResource(R.string.compression_requests_note)) }
                section.entries.forEachIndexed { index, entry -> item { RequestCard(entry, index + 1) } }
            }
            is CompressionSection.Evidence -> {
                item { SectionHeading(stringResource(R.string.compression_evidence_title),
                    stringResource(R.string.compression_evidence_counts, section.entries.size, section.recorded, section.omitted)) }
                item {
                    Text(stringResource(R.string.compression_evidence_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                section.entries.forEach { entry -> item { EvidenceCard(entry) } }
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, note: String) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RequestCard(entry: JsonObject, number: Int) {
    val text = entry.compressionText("text_excerpt").orEmpty()
    var details by rememberSaveable(entry.compressionText("message_id")) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(number.toString(), Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
                }
                Text(stringResource(R.string.compression_request_excerpt), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                CopyTextButton(text)
            }
            // These are quotations, not newly active messages or model-generated Markdown.
            SelectionContainer { Text(text, modifier = Modifier.fillMaxWidth(), softWrap = true, style = MaterialTheme.typography.bodyMedium) }
            TextButton(onClick = { details = !details }) {
                Text(stringResource(if (details) R.string.compression_hide_reference else R.string.compression_show_reference))
            }
            if (details) {
                EvidenceField(stringResource(R.string.compression_message_id), entry.compressionText("message_id").orEmpty())
                val extra = JsonObject(entry.filterKeys { it !in setOf("message_id", "text_excerpt") })
                if (extra.isNotEmpty()) CompressionPlainBlock(stringResource(R.string.compression_extra_data), extra.toString())
            }
        }
    }
}

@Composable
private fun EvidenceCard(entry: JsonObject) {
    var expanded by rememberSaveable(entry.compressionText("call_id")) { mutableStateOf(false) }
    var metadata by rememberSaveable(entry.compressionText("call_id")) { mutableStateOf(false) }
    val state = compressionEvidenceState(entry)
    val command = remember(entry) { compressionEvidenceCommand(entry) }
    val arguments = remember(entry) { compressionEvidenceInput(entry) }
    val exitCode = (entry["exit_code"] as? JsonPrimitive)?.intOrNull
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(HugeIcons.ComputerTerminal01, contentDescription = null, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.compressionText("tool").orEmpty(), style = MaterialTheme.typography.titleSmall, softWrap = true)
                    val label = stringResource(when (state) {
                        CompressionEvidenceState.RECORDED -> R.string.compression_evidence_recorded
                        CompressionEvidenceState.FAILED -> R.string.compression_evidence_failed
                        CompressionEvidenceState.TIMEOUT -> R.string.compression_evidence_timeout
                    })
                    Text(label + (exitCode?.let { " · " + stringResource(R.string.termux_preview_exit, it) } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (state == CompressionEvidenceState.RECORDED) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error)
                }
            }
            val preview = command ?: entry.compressionText("input_excerpt")
                ?: entry.compressionText("result_excerpt") ?: entry.compressionText("stdout_excerpt")
            if (!expanded && !preview.isNullOrBlank()) Text(preview, modifier = Modifier.fillMaxWidth(), softWrap = true,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr))
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.compression_evidence_collapse else R.string.compression_evidence_expand))
            }
            if (expanded) {
                if (command != null) CompressionPlainBlock(stringResource(R.string.compression_command_excerpt), command)
                // Show all argument fields, including keys/enter, cwd, timeouts and background flags.
                if (arguments != null) {
                    val commandKeys = when {
                        command == null -> emptySet()
                        arguments.compressionText("command") != null -> setOf("command")
                        arguments.compressionText("input") != null -> setOf("input")
                        else -> setOf("executable", "arguments")
                    }
                    arguments.filterKeys { it !in commandKeys }.forEach { (key, value) ->
                        EvidenceField(compressionFieldLabel(key), value.display())
                    }
                } else entry.compressionText("input_excerpt")?.let {
                    CompressionPlainBlock(stringResource(R.string.compression_input_excerpt), it)
                }
                listOf("error", "state", "status", "job_id", "session_id").forEach { key ->
                    entry[key]?.takeIf { it != JsonNull }?.let { EvidenceField(compressionFieldLabel(key), it.display()) }
                }
                listOf("stdout_excerpt", "stderr_excerpt", "screen_excerpt", "result_excerpt", "reason_excerpt", "recovery_excerpt", "note_excerpt").forEach { key ->
                    entry.compressionText(key)?.takeIf { it.isNotBlank() }?.let {
                        CompressionPlainBlock(compressionFieldLabel(key), it, error = key == "stderr_excerpt")
                    }
                }
                HorizontalDivider()
                EvidenceField(stringResource(R.string.compression_call_id), entry.compressionText("call_id").orEmpty())
                listOf("output_ref", "log_path", "next_cursor").forEach { key ->
                    entry[key]?.takeIf { it != JsonNull }?.let { EvidenceField(compressionFieldLabel(key), it.display()) }
                }
                (entry["output_chars"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }?.let {
                    Text(stringResource(R.string.compression_output_size, it), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { metadata = !metadata }) {
                    Text(stringResource(if (metadata) R.string.compression_hide_metadata else R.string.compression_show_metadata))
                }
                if (metadata) CompressionPlainBlock(stringResource(R.string.compression_evidence_record), entry.toString())
            }
        }
    }
}

@Composable
private fun EvidenceField(label: String, text: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(text, modifier = Modifier.fillMaxWidth(), softWrap = true,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr)) }
    }
}

/** Visual wrapping never inserts newlines into copied commands and terminal evidence is never Markdown. */
@Composable
internal fun CompressionPlainBlock(label: String, text: String, error: Boolean = false) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                CopyTextButton(text)
            }
            SelectionContainer { Text(text, modifier = Modifier.fillMaxWidth(), softWrap = true,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr)) }
        }
    }
}

@Composable
private fun CopyTextButton(text: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", text))) } }) {
        Text(stringResource(R.string.code_block_copy))
    }
}

private fun JsonElement.display(): String = (this as? JsonPrimitive)?.contentOrNull ?: toString()

@Composable
private fun compressionFieldLabel(key: String): String = when (key) {
    "stdout_excerpt" -> stringResource(R.string.compression_stdout_excerpt)
    "stderr_excerpt" -> stringResource(R.string.compression_stderr_excerpt)
    "screen_excerpt" -> stringResource(R.string.termux_preview_screen)
    "result_excerpt" -> stringResource(R.string.compression_result_excerpt)
    "reason_excerpt" -> stringResource(R.string.termux_preview_reason)
    "recovery_excerpt" -> stringResource(R.string.termux_preview_recovery)
    "note_excerpt" -> stringResource(R.string.termux_preview_note)
    "error" -> stringResource(R.string.termux_preview_error)
    "state" -> stringResource(R.string.termux_preview_state)
    "status" -> stringResource(R.string.compression_recorded_status)
    "job_id" -> stringResource(R.string.termux_preview_job_id)
    "session_id" -> stringResource(R.string.termux_preview_session)
    "output_ref" -> stringResource(R.string.termux_preview_output_ref)
    "log_path" -> stringResource(R.string.termux_preview_log_path)
    "next_cursor" -> stringResource(R.string.termux_preview_next_cursor)
    "working_dir" -> stringResource(R.string.termux_preview_directory)
    "timeout_seconds" -> stringResource(R.string.termux_preview_timeout_seconds)
    "background" -> stringResource(R.string.termux_preview_background)
    "interactive" -> stringResource(R.string.termux_preview_interactive)
    "keys" -> stringResource(R.string.termux_preview_keys)
    "enter" -> stringResource(R.string.termux_preview_enter)
    else -> key
}
