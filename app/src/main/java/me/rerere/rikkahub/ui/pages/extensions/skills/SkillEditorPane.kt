package me.rerere.rikkahub.ui.pages.extensions.skills

import android.content.ClipData
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.highlight.LocalCodeHighlighter
import me.rerere.rikkahub.R
import me.rerere.rikkahub.skills.SkillWorkspace
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeVisualTransformation
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock

@Composable
internal fun SkillEditorPane(editor: SkillEditBuffer, busy: Boolean, modifier: Modifier, onEdit: (TextFieldValue) -> Unit,
    onSave: () -> Unit, onUndo: () -> Unit, onRedo: () -> Unit, onClose: () -> Unit, onHex: () -> Unit, onReload: () -> Unit, onExport: () -> Unit, onReplace: () -> Unit) {
    val document = editor.document
    var mode by rememberSaveable(document.path) { mutableStateOf(if (editor.editable) "code" else "preview") }
    var searching by rememberSaveable(document.path) { mutableStateOf(false) }
    var find by rememberSaveable(document.path) { mutableStateOf("") }
    var replacement by rememberSaveable(document.path) { mutableStateOf("") }
    var matchCase by rememberSaveable { mutableStateOf(false) }
    var wrap by rememberSaveable { mutableStateOf(false) }
    var goToLine by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf("") }
    var copyLimit by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val previewData = remember(document.hash, editor.hex, editor.value.text) {
        if (editor.hex) runCatching { SkillWorkspace.decodeHex(editor.value.text) } else Result.success(document.bytes)
    }
    val displayBytes = previewData.getOrNull() ?: byteArrayOf()
    val extension = document.path.substringAfterLast('.', "").lowercase()
    LaunchedEffect(editor.hex) { if (editor.hex) mode = "code" }
    Column(modifier.testTag("skill-editor")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(document.path.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium)
                    Text(document.path.substringBeforeLast('/', "/"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (editor.editable) FilledTonalButton(onClick = onSave, enabled = editor.dirty && !busy, modifier = Modifier.testTag("skill-save")) { Text(stringResource(R.string.skill_workbench_save)) }
                IconButton(onClick = onClose, enabled = !busy) { Icon(Lucide.X, stringResource(R.string.jobs_close)) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (editor.editable) FilterChip(mode == "code", { mode = "code" }, label = { Text(if (editor.hex) "HEX" else stringResource(R.string.skill_workbench_code)) })
                FilterChip(mode == "preview", { mode = "preview" }, label = { Text(stringResource(R.string.skill_workbench_preview)) })
                FilterChip(mode == "info", { mode = "info" }, label = { Text(stringResource(R.string.skill_workbench_properties)) })
                Text(stringResource(if (editor.dirty) R.string.skill_workbench_unsaved_short else R.string.skill_workbench_saved_short), color = if (editor.dirty) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (mode == "code" && editor.editable) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onUndo, enabled = editor.undo.isNotEmpty() && !busy) { Icon(Lucide.Undo2, stringResource(R.string.skill_workbench_undo)) }
                IconButton(onClick = onRedo, enabled = editor.redo.isNotEmpty() && !busy) { Icon(Lucide.Redo2, stringResource(R.string.skill_workbench_redo)) }
                IconButton(onClick = { searching = !searching }) { Icon(Lucide.Search, stringResource(R.string.skill_workbench_find_replace)) }
                IconButton(onClick = { wrap = !wrap }) { Icon(Lucide.WrapText, stringResource(R.string.skill_workbench_wrap), tint = if (wrap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = { goToLine = true }) { Icon(Lucide.ListOrdered, stringResource(R.string.skill_workbench_go_line)) }
                IconButton(onClick = { scope.launch { if (editor.value.text.length <= 128_000) clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(document.path, editor.value.text))) else copyLimit = true } }) { Icon(Lucide.Copy, stringResource(R.string.jobs_copy)) }
                if (!editor.hex) TextButton(onClick = {
                    val selection = editor.value.selection
                    val text = editor.value.text.replaceRange(selection.min, selection.max, "    ")
                    onEdit(TextFieldValue(text, TextRange(selection.min + 4)))
                }, enabled = !busy) { Text("Tab") }
            }
            if (searching) Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(find, { find = it }, Modifier.weight(1f).testTag("skill-find"), label = { Text(stringResource(R.string.skill_workbench_find)) }, singleLine = true)
                    TextButton(onClick = { matchCase = !matchCase }) { Text(if (matchCase) "Aa ✓" else "Aa") }
                    IconButton(onClick = {
                        val start = editor.value.text.indexOf(find, editor.value.selection.max, ignoreCase = !matchCase).takeIf { it >= 0 }
                            ?: editor.value.text.indexOf(find, ignoreCase = !matchCase)
                        if (start >= 0) onEdit(editor.value.copy(selection = TextRange(start, start + find.length)))
                    }, enabled = find.isNotEmpty()) { Icon(Lucide.ArrowDown, stringResource(R.string.skill_workbench_find_next)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(replacement, { replacement = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.skill_workbench_replace_with)) }, singleLine = true)
                    TextButton(onClick = {
                        val result = editor.value.text.replace(find, replacement, ignoreCase = !matchCase)
                        onEdit(TextFieldValue(result))
                    }, enabled = find.isNotEmpty() && !busy) { Text(stringResource(R.string.skill_workbench_replace_all)) }
                }
            }
            val highlighter = LocalCodeHighlighter.current
            val language = when (extension) { "py" -> "python"; "js", "mjs" -> "javascript"; "ts" -> "typescript"; "sh", "bash" -> "bash"; "yml" -> "yaml"; "kt" -> "kotlin"; "md" -> "markdown"; else -> extension }
            val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
            val transform = remember(language, dark, editor.hex, editor.value.text.length > 48_000) {
                if (editor.hex || editor.value.text.length > 48_000) VisualTransformation.None else HighlightCodeVisualTransformation(language, highlighter, dark)
            }
            val vertical = rememberScrollState()
            val horizontal = rememberScrollState()
            val lineCount = remember(editor.value.text) { editor.value.text.count { it == '\n' } + 1 }
            val effectiveWrap = wrap || editor.value.text.lineSequence().any { it.length > 4000 } || lineCount > 4000
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                val minWidth = (maxWidth - 64.dp).coerceAtLeast(100.dp)
                val paneHeight = maxHeight
                Row(Modifier.fillMaxSize().verticalScroll(vertical).then(if (effectiveWrap) Modifier else Modifier.horizontalScroll(horizontal)).padding(vertical = 12.dp)) {
                    if (!effectiveWrap) Text((1..lineCount).joinToString("\n"), Modifier.width(48.dp).padding(end = 10.dp), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp), color = MaterialTheme.colorScheme.outline)
                    BasicTextField(editor.value, onEdit,
                        modifier = Modifier.then(if (effectiveWrap) Modifier.weight(1f) else Modifier.width(IntrinsicSize.Min).widthIn(min = minWidth)).heightIn(min = paneHeight).padding(horizontal = 12.dp).testTag("skill-code-input"),
                        enabled = !busy, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface, textDirection = TextDirection.Ltr),
                        visualTransformation = transform, cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Text))
                }
            }
            val selection = editor.value.selection.start.coerceIn(0, editor.value.text.length)
            val lineNo = editor.value.text.take(selection).count { it == '\n' } + 1
            val column = selection - editor.value.text.lastIndexOf('\n', (selection - 1).coerceAtLeast(0))
            Text(stringResource(R.string.skill_workbench_cursor, lineNo, column, skillSize(editor.value.text.toByteArray().size.toLong())), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall)
        } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (mode == "info") {
                EditorFact(stringResource(R.string.skill_workbench_relative_path), document.path)
                EditorFact(stringResource(R.string.skill_workbench_size), skillSize(document.bytes.size.toLong()))
                EditorFact(stringResource(R.string.skill_workbench_modified), java.text.DateFormat.getDateTimeInstance().format(java.util.Date(document.modified)))
                EditorFact(stringResource(R.string.skill_workbench_encoding), if (document.text != null) "UTF-8" else stringResource(R.string.skill_workbench_binary))
                EditorFact("SHA-256", document.hash)
                if (editor.dirty) Text(stringResource(R.string.skill_workbench_properties_saved), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.skill_workbench_limits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val picture by produceState<android.graphics.Bitmap?>(null, displayBytes) {
                    if (extension in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")) value = withContext(Dispatchers.Default) { runCatching {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(displayBytes, 0, displayBytes.size, bounds)
                        if (bounds.outWidth !in 1..100_000 || bounds.outHeight !in 1..100_000) null else {
                            var sample = 1
                            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                            BitmapFactory.decodeByteArray(displayBytes, 0, displayBytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                        }
                    }.getOrNull() }
                }
                picture?.let { Image(it.asImageBitmap(), document.path, Modifier.fillMaxWidth().heightIn(max = 440.dp), contentScale = ContentScale.Fit) }
                if (document.text != null) {
                    val text = if (editor.editable && !editor.hex) editor.value.text else document.text
                    val preview = text.lineSequence().take(500).joinToString("\n").take(40_000)
                    SelectionContainer {
                        if (extension in setOf("md", "markdown")) MarkdownBlock(preview)
                        else Text(preview, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                    }
                    if (preview.length < text.length) Text(stringResource(R.string.skill_workbench_preview_limit), style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.skill_workbench_binary), style = MaterialTheme.typography.titleMedium)
                    Text("${skillSize(document.bytes.size.toLong())} · ${extension.uppercase()}", style = MaterialTheme.typography.bodySmall)
                    var offset by remember(document.hash) { mutableIntStateOf(0) }
                    if (previewData.isFailure) Text(stringResource(R.string.skill_workbench_invalid_hex), color = MaterialTheme.colorScheme.error)
                    val bytes = displayBytes.copyOfRange(offset.coerceAtMost(displayBytes.size), (offset + 256).coerceAtMost(displayBytes.size))
                    SelectionContainer { Text(SkillWorkspace.encodeHex(bytes), Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { offset = (offset - 256).coerceAtLeast(0) }, enabled = offset > 0) { Text("←") }
                        Text("0x%08X · %s".format(offset, skillSize(document.bytes.size.toLong())), style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { offset += 256 }, enabled = offset + 256 < displayBytes.size) { Text("→") }
                    }
                    if (document.bytes.size <= SkillWorkspace.MAX_HEX_EDIT_BYTES && !editor.hex) FilledTonalButton(onClick = onHex, enabled = !busy) { Text(stringResource(R.string.skill_workbench_edit_hex)) }
                }
                if (!editor.editable) Text(stringResource(R.string.skill_workbench_limits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExport, enabled = !busy) { Text(stringResource(R.string.skill_workbench_export_file)) }
                OutlinedButton(onClick = onReload, enabled = !busy) { Text(stringResource(R.string.skill_workbench_reload)) }
                OutlinedButton(onClick = onReplace, enabled = !busy) { Text(stringResource(R.string.skill_workbench_replace_file)) }
            }
        }
    }
    if (copyLimit) AlertDialog(onDismissRequest = { copyLimit = false }, text = { Text(stringResource(R.string.skill_workbench_copy_limit)) }, confirmButton = { TextButton(onClick = { copyLimit = false }) { Text(stringResource(R.string.confirm)) } })
    if (goToLine) AlertDialog(onDismissRequest = { goToLine = false }, title = { Text(stringResource(R.string.skill_workbench_go_line)) }, text = {
        OutlinedTextField(line, { line = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }, confirmButton = { TextButton(onClick = {
        val desired = line.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val position = editor.value.text.lineSequence().take(desired - 1).sumOf { it.length + 1 }.coerceAtMost(editor.value.text.length)
        onEdit(editor.value.copy(selection = TextRange(position))); goToLine = false
    }) { Text(stringResource(R.string.confirm)) } }, dismissButton = { TextButton(onClick = { goToLine = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun EditorFact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
    }
}
