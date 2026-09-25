package me.rerere.rikkahub.ui.components.message.tools

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.context.LocalNavController

internal val SkillToolUIs: List<ToolUIRenderer> = listOf("use_skill", "skill_get_content", "termux_skill_sync", "skill_create",
    "skill_list_files", "skill_read_file", "skill_write_file", "skill_edit_file", "skill_manage_files", "skill_delete",
    "skill_install_from_url", "skill_install_from_text").map { SkillToolUI(it) }

private class SkillToolUI(override val toolName: String) : ToolUIRenderer {
    override fun icon(context: ToolUIContext) = HugeIcons.MagicWand01
    override fun hasSummary(context: ToolUIContext) = true
    @Composable private fun presentation(context: ToolUIContext) = remember(context) {
        presentSkill(toolName, context.arguments, context.tool.output.filterIsInstance<UIMessagePart.Text>().map { it.text },
            context.loading, context.tool.executionStartedAt != null, context.tool.isPending,
            context.tool.approvalState is ToolApprovalState.Denied && !context.tool.isExecuted)
    }
    @Composable override fun title(context: ToolUIContext): String = stringResource(R.string.pocket_skill_title,
        context.arguments.getStringContent("name") ?: toolName.removePrefix("skill_"))
    @Composable override fun Summary(context: ToolUIContext) {
        val view = presentation(context)
        Text(stringResource(view.status.label()), style = MaterialTheme.typography.labelMedium,
            color = if (view.errors.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(view.path ?: when (toolName) { "termux_skill_sync" -> "Termux"; else -> "SKILL.md" },
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val excerpt = view.errors.firstOrNull() ?: view.documents.firstOrNull()?.text?.lineSequence()
            ?.filter { it.isNotBlank() }?.take(3)?.joinToString(" ")
        if (!excerpt.isNullOrBlank()) Text(excerpt.take(320), maxLines = 3, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall)
    }
    @Composable override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val view = presentation(context)
        val navigator = LocalNavController.current
        var raw by remember(context.tool.toolCallId) { mutableStateOf(false) }
        if (raw) { DefaultToolPreview(context) { TextButton(onClick = { raw = false }) { Text(stringResource(R.string.pocket_readable)) } }; return }
        SkillPreviewContent(view, onRaw = { raw = true }, onOpenSkill = {
            onDismissRequest(); navigator.navigate(Screen.SkillDetail(view.name))
        })
    }
}

@Composable
internal fun SkillPreviewContent(view: SkillPresentation, onRaw: () -> Unit, onOpenSkill: () -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.85f).testTag("skill-tool-preview"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(HugeIcons.MagicWand01, null)
                Column(Modifier.weight(1f)) {
                    Text(view.name, style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(view.status.label()), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        item {
            Row {
                TextButton(onClick = onOpenSkill) { Text(stringResource(R.string.pocket_open_skill)) }
                TextButton(onClick = onRaw) { Text(stringResource(R.string.pocket_raw_response)) }
            }
        }
        items(view.errors) { error ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                SelectionContainer { Text(error, Modifier.padding(12.dp)) }
            }
        }
        val metadata = view.metadata
        listOf("skill_root", "revision", "file_count", "size_bytes", "offset", "has_more", "source_label").forEach { key ->
            metadata[key]?.let { value -> item {
                SelectionContainer { Text("${stringResource(skillMetadataLabel(key))}: ${(value as? JsonPrimitive)?.contentOrNull ?: value}",
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            } }
        }
        if (view.binary) item { Text(stringResource(R.string.pocket_skill_binary)) }
        items(view.documents) { document -> SkillDocumentCard(document) }
        val entries = (metadata["entries"] as? JsonArray).orEmpty()
        items(entries.take(200)) { entry ->
            val path = entry.getStringContent("path").orEmpty()
            ListItem(headlineContent = { Text(path, fontFamily = FontFamily.Monospace) },
                supportingContent = { Text(entry.getStringContent("size_bytes")?.let { "$it B" }.orEmpty()) })
        }
        if (entries.size > 200) item { Text(stringResource(R.string.pocket_skill_more_entries, entries.size - 200)) }
    }
}

@Composable
private fun SkillDocumentCard(document: SkillDocumentPreview) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var page by remember(document) { mutableIntStateOf(0) }
    val chunks = remember(document.text) { document.text.chunked(16000).ifEmpty { listOf("") } }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(document.path, Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(document.path, document.text))) } }) {
                    Text(stringResource(R.string.code_block_copy))
                }
            }
            if (chunks.size > 1) Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = page > 0, onClick = { page-- }) { Text("←") }
                Text(stringResource(R.string.pocket_skill_page, page + 1, chunks.size))
                TextButton(enabled = page < chunks.lastIndex, onClick = { page++ }) { Text("→") }
            }
            if (document.language == "markdown") MarkdownBlock(chunks[page], style = MaterialTheme.typography.bodyMedium)
            else if (document.language == "text") SelectionContainer { Text(chunks[page], fontFamily = FontFamily.Monospace) }
            else HighlightCodeBlock(code = chunks[page], language = document.language, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun SkillOperationStatus.label() = when (this) {
    SkillOperationStatus.PENDING -> R.string.termux_preview_pending
    SkillOperationStatus.RUNNING -> R.string.termux_preview_running
    SkillOperationStatus.APPROVAL -> R.string.termux_preview_approval
    SkillOperationStatus.DENIED -> R.string.termux_preview_denied
    SkillOperationStatus.COMPLETED -> R.string.pocket_skill_completed
    SkillOperationStatus.FAILED -> R.string.termux_preview_failed
    SkillOperationStatus.PARTIAL -> R.string.pocket_skill_partial
}

private fun skillMetadataLabel(key: String) = when (key) {
    "skill_root" -> R.string.pocket_skill_root
    "revision" -> R.string.pocket_skill_revision
    "file_count" -> R.string.pocket_skill_files
    "size_bytes" -> R.string.pocket_skill_bytes
    "offset" -> R.string.pocket_skill_offset
    "has_more" -> R.string.pocket_skill_continues
    else -> R.string.pocket_skill_source
}
