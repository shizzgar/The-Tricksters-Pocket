package me.rerere.rikkahub.ui.pages.extensions.skills

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.*
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.skills.SkillEntry
import me.rerere.rikkahub.skills.SkillWorkspace
import java.text.DateFormat
import java.util.Date

private data class WorkspacePrompt(val kind: String, val paths: Set<String> = emptySet(), val initial: String = "")

@Composable
internal fun SkillWorkbenchScreen(vm: SkillDetailVM, onBack: () -> Unit, onTest: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var prompt by remember { mutableStateOf<WorkspacePrompt?>(null) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var menu by remember { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf("name") }
    var exportPath by rememberSaveable { mutableStateOf<String?>(null) }
    var replacePath by rememberSaveable { mutableStateOf<String?>(null) }
    var replaceHash by rememberSaveable { mutableStateOf<String?>(null) }
    var importFolder by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    fun guarded(action: () -> Unit) { if (state.editor?.dirty == true) pending = action else action() }
    fun back() { guarded { if (state.editor != null) vm.closeEditor() else if (state.folder.isNotEmpty()) vm.folder(state.folder.substringBeforeLast('/', "")) else onBack() } }
    BackHandler { if (state.busy) Unit else back() }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.importFiles(uris, importFolder) }
    val replace = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val path = replacePath; val hash = replaceHash
        if (uri != null && path != null && hash != null) vm.replaceFile(uri, path, hash)
        replacePath = null; replaceHash = null
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if (uri != null) vm.export(uri, exportPath) }
    fun exportFile(path: String?) { exportPath = path; export.launch(path?.substringAfterLast('/') ?: "${state.name}.zip") }

    Scaffold(Modifier.testTag("skill-workbench"), topBar = {
        TopAppBar(title = { Column {
            Text(state.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.skill_workbench_title), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }, navigationIcon = { IconButton(onClick = { back() }, enabled = !state.busy) { Icon(Lucide.ArrowLeft, stringResource(R.string.jobs_back)) } }, actions = {
            IconButton(onClick = { guarded { vm.sync() } }, enabled = !state.busy) { Icon(Lucide.RefreshCw, stringResource(R.string.skill_workbench_sync)) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Lucide.EllipsisVertical, stringResource(R.string.skill_workbench_actions)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.skill_workbench_export_package)) }, onClick = { menu = false; guarded { exportFile(null) } }, leadingIcon = { Icon(Lucide.Download, null) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.skill_workbench_restore)) }, enabled = state.snapshot?.canRestore == true && !state.busy, onClick = { menu = false; guarded { prompt = WorkspacePrompt("restore") } }, leadingIcon = { Icon(Lucide.History, null) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.skill_tester_run)) }, onClick = { menu = false; guarded(onTest) }, leadingIcon = { Icon(Lucide.Play, null) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.jobs_refresh)) }, onClick = { menu = false; vm.refresh() }, enabled = !state.busy)
                }
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall) }
            BoxWithConstraints(Modifier.weight(1f)) {
                val wide = maxWidth >= 840.dp
                Row(Modifier.fillMaxSize()) {
                    if (wide || state.editor == null) Column(Modifier.then(if (wide) Modifier.width(320.dp) else Modifier.fillMaxWidth()).fillMaxHeight()) {
                        val snapshot = state.snapshot
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.skill_workbench_inventory, snapshot?.files ?: 0, skillSize(snapshot?.bytes ?: 0)), style = MaterialTheme.typography.labelMedium)
                                Text(if (state.syncedRevision == snapshot?.revision && state.syncRoot != null) stringResource(R.string.skill_workbench_in_sync) else stringResource(R.string.skill_workbench_local), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedTextField(state.query, vm::query, Modifier.fillMaxWidth().testTag("skill-file-search"), singleLine = true, placeholder = { Text(stringResource(R.string.skill_workbench_search)) }, leadingIcon = { Icon(Lucide.Search, null, Modifier.size(18.dp)) })
                            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { vm.folder("") }) { Text(stringResource(R.string.skill_workbench_root)) }
                                var path = ""
                                state.folder.split('/').filter { it.isNotEmpty() }.forEach { part ->
                                    path = listOf(path, part).filter { it.isNotEmpty() }.joinToString("/")
                                    val target = path
                                    Text("/", color = MaterialTheme.colorScheme.outline)
                                    TextButton(onClick = { vm.folder(target) }) { Text(part) }
                                }
                            }
                            if (state.selected.isEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Row {
                                    IconButton(onClick = { guarded { prompt = WorkspacePrompt("file", initial = if (state.folder.isBlank()) "" else state.folder + "/") } }, enabled = !state.busy) { Icon(Lucide.FilePlus2, stringResource(R.string.skill_workbench_new_file)) }
                                    IconButton(onClick = { guarded { prompt = WorkspacePrompt("folder", initial = if (state.folder.isBlank()) "" else state.folder + "/") } }, enabled = !state.busy) { Icon(Lucide.FolderPlus, stringResource(R.string.skill_workbench_new_folder)) }
                                    IconButton(onClick = { guarded { importFolder = state.folder; import.launch(arrayOf("*/*")) } }, enabled = !state.busy) { Icon(Lucide.Upload, stringResource(R.string.skill_workbench_import)) }
                                }
                                TextButton(onClick = { sort = when (sort) { "name" -> "size"; "size" -> "modified"; else -> "name" } }) {
                                    Text(stringResource(when (sort) { "size" -> R.string.skill_workbench_size; "modified" -> R.string.skill_workbench_modified; else -> R.string.skill_workbench_name }))
                                }
                            } else {
                                Text(stringResource(R.string.skill_workbench_selected, state.selected.size), style = MaterialTheme.typography.labelMedium)
                                Row {
                                    TextButton(onClick = { guarded { prompt = WorkspacePrompt("move", state.selected) } }, enabled = !state.busy && "SKILL.md" !in state.selected) { Text(stringResource(R.string.skill_workbench_move)) }
                                    TextButton(onClick = { guarded { prompt = WorkspacePrompt("delete", state.selected) } }, enabled = !state.busy && "SKILL.md" !in state.selected) { Text(stringResource(R.string.delete)) }
                                    TextButton(onClick = vm::clearSelection) { Text(stringResource(R.string.cancel)) }
                                }
                            }
                        }
                        val entries = snapshot?.entries.orEmpty().filter { entry ->
                            if (state.query.isNotBlank()) entry.path.contains(state.query, true) else entry.path.substringBeforeLast('/', "") == state.folder
                        }.sortedWith(compareBy<SkillEntry> { !it.directory }.thenBy { if (sort == "name") it.path.lowercase() else "" }.thenByDescending { if (sort == "size") it.size else if (sort == "modified") it.modified else 0 })
                        LazyColumn(Modifier.weight(1f).testTag("skill-file-list"), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (entries.isEmpty() && !state.busy) item { Text(stringResource(R.string.skill_workbench_empty), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
                            items(entries, key = { it.path }) { entry ->
                                var expanded by remember(entry.path) { mutableStateOf(false) }
                                val selected = entry.path in state.selected || entry.path == state.editor?.document?.path
                                Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxWidth().combinedClickable(enabled = !state.busy,
                                        onClick = { if (state.selected.isNotEmpty()) vm.select(entry.path) else if (entry.directory) vm.folder(entry.path) else guarded { vm.open(entry.path) } },
                                        onLongClick = { vm.select(entry.path) })) {
                                    Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        if (state.selected.isNotEmpty()) Checkbox(entry.path in state.selected, { vm.select(entry.path) }, Modifier.size(24.dp))
                                        else Icon(skillIcon(entry), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                                        Column(Modifier.weight(1f)) {
                                            Text(if (state.query.isBlank()) entry.path.substringAfterLast('/') else entry.path, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text(if (entry.directory) stringResource(R.string.skill_workbench_folder) else "${skillSize(entry.size)} · ${entry.path.substringAfterLast('.', "file").uppercase()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Box {
                                            IconButton(onClick = { expanded = true }, enabled = !state.busy) { Icon(Lucide.EllipsisVertical, stringResource(R.string.skill_workbench_actions), Modifier.size(18.dp)) }
                                            DropdownMenu(expanded, { expanded = false }) {
                                                DropdownMenuItem(text = { Text(stringResource(R.string.skill_workbench_rename)) }, enabled = entry.path != "SKILL.md", onClick = { expanded = false; guarded { prompt = WorkspacePrompt("rename", setOf(entry.path), entry.path) } })
                                                DropdownMenuItem(text = { Text(stringResource(R.string.skill_workbench_duplicate)) }, onClick = { expanded = false; guarded { prompt = WorkspacePrompt("copy", setOf(entry.path), entry.path + ".copy") } })
                                                DropdownMenuItem(text = { Text(stringResource(R.string.skill_workbench_move)) }, enabled = entry.path != "SKILL.md", onClick = { expanded = false; guarded { prompt = WorkspacePrompt("move", setOf(entry.path)) } })
                                                if (!entry.directory) DropdownMenuItem(text = { Text(stringResource(R.string.skill_workbench_export_file)) }, onClick = { expanded = false; exportFile(entry.path) })
                                                DropdownMenuItem(text = { Text(stringResource(R.string.skill_workbench_copy_path)) }, onClick = { expanded = false; scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Skill path", entry.path))) } })
                                                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, enabled = entry.path != "SKILL.md", onClick = { expanded = false; guarded { prompt = WorkspacePrompt("delete", setOf(entry.path)) } })
                                            }
                                        }
                                    }
                                }
                            }
                            item { Text(stringResource(R.string.skill_workbench_file_hint), Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        if (state.syncRoot != null) TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("skill_root", state.syncRoot))) } }, modifier = Modifier.padding(horizontal = 12.dp)) { Text(stringResource(R.string.skill_workbench_copy_root)) }
                    }
                    if (wide) VerticalDivider()
                    state.editor?.let { editor ->
                        SkillEditorPane(editor, state.busy, Modifier.weight(1f).fillMaxHeight(), vm::edit, { vm.save() }, { vm.undo() }, { vm.undo(true) },
                            onClose = { guarded { vm.closeEditor() } }, onHex = vm::hexEditor, onReload = { guarded { vm.open(editor.document.path) } },
                            onExport = { exportFile(editor.document.path) }, onReplace = { guarded {
                                replacePath = vm.state.value.editor?.document?.path; replaceHash = vm.state.value.editor?.document?.hash; replace.launch(arrayOf("*/*"))
                            } })
                    } ?: if (wide) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Column(Modifier.widthIn(max = 420.dp).padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(Lucide.FolderOpen, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.skill_workbench_welcome), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(R.string.skill_workbench_welcome_detail), style = MaterialTheme.typography.bodyMedium)
                            FilledTonalButton(onClick = { vm.open("SKILL.md") }, enabled = !state.busy) { Text("SKILL.md") }
                        }
                    } else Unit
                }
            }
        }
    }
    pending?.let { next ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text(stringResource(R.string.skill_workbench_unsaved)) }, text = { Text(stringResource(R.string.skill_workbench_unsaved_detail)) },
            confirmButton = { TextButton(onClick = { pending = null; vm.save(next) }) { Text(stringResource(R.string.skill_workbench_save)) } },
            dismissButton = { Row {
                TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = { pending = null; vm.discardThen(next) }) { Text(stringResource(R.string.skill_workbench_discard)) }
            } })
    }
    prompt?.let { current ->
        var value by remember(current) { mutableStateOf(current.initial) }
        val title = when (current.kind) {
            "file" -> R.string.skill_workbench_new_file; "folder" -> R.string.skill_workbench_new_folder
            "rename" -> R.string.skill_workbench_rename; "copy" -> R.string.skill_workbench_duplicate
            "move" -> R.string.skill_workbench_move; "restore" -> R.string.skill_workbench_restore
            else -> R.string.delete
        }
        AlertDialog(onDismissRequest = { prompt = null }, title = { Text(stringResource(title)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (current.kind) {
                    "delete" -> Text(stringResource(R.string.skill_workbench_delete_detail, current.paths.joinToString("\n")))
                    "restore" -> Text(stringResource(R.string.skill_workbench_restore_detail))
                    "move" -> {
                        Text(stringResource(R.string.skill_workbench_destination))
                        Column(Modifier.heightIn(max = 280.dp).fillMaxWidth()) {
                            LazyColumn(Modifier.widthIn(min = 220.dp)) {
                                val folders = listOf("") + state.snapshot?.entries.orEmpty().filter { it.directory && current.paths.none { path -> it.path == path || it.path.startsWith("$path/") } }.map { it.path }
                                items(folders) { folder -> Row(Modifier.fillMaxWidth().clickable { value = folder }, verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(value == folder, { value = folder }); Text(folder.ifEmpty { stringResource(R.string.skill_workbench_root) }, style = MaterialTheme.typography.bodySmall)
                                } }
                            }
                        }
                    }
                    else -> {
                        OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth().testTag("skill-path-input"), label = { Text(stringResource(R.string.skill_workbench_relative_path)) }, singleLine = true, isError = value.isNotBlank() && !SkillWorkspace.validPath(value))
                        Text(stringResource(R.string.skill_workbench_path_help), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }, confirmButton = { TextButton(enabled = !state.busy && (current.kind in setOf("delete", "restore", "move") || SkillWorkspace.validPath(value)), onClick = {
            prompt = null
            when (current.kind) {
                "file" -> vm.create(value, false); "folder" -> vm.create(value, true)
                "rename", "copy" -> vm.rename(current.paths.first(), value, current.kind == "copy")
                "move" -> vm.move(current.paths, value); "delete" -> vm.delete(current.paths); "restore" -> vm.restore()
            }
        }) { Text(stringResource(if (current.kind == "delete") R.string.delete else R.string.confirm)) } }, dismissButton = { TextButton(onClick = { prompt = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

internal fun skillSize(bytes: Long): String = when { bytes < 1024 -> "$bytes B"; bytes < 1024 * 1024 -> "%.1f KiB".format(bytes / 1024.0); else -> "%.1f MiB".format(bytes / 1048576.0) }
private fun skillIcon(entry: SkillEntry) = when {
    entry.directory -> Lucide.Folder
    entry.path.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp") -> Lucide.Image
    entry.path.substringAfterLast('.').lowercase() in setOf("py", "sh", "js", "ts", "kt", "rs", "java", "css", "html", "json", "yaml", "yml") -> Lucide.FileCode
    entry.path.substringAfterLast('.').lowercase() in setOf("md", "txt", "csv") -> Lucide.FileText
    else -> Lucide.File
}
