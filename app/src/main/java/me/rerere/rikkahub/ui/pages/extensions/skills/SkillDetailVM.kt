package me.rerere.rikkahub.ui.pages.extensions.skills

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.skills.*

internal data class SkillEditBuffer(
    val document: SkillDocument,
    val value: TextFieldValue,
    val baseline: String,
    val hex: Boolean = false,
    val undo: List<String> = emptyList(),
    val redo: List<String> = emptyList(),
) {
    val dirty get() = value.text != baseline
    val editable get() = if (hex) document.bytes.size <= SkillWorkspace.MAX_HEX_EDIT_BYTES else document.text != null && document.bytes.size <= SkillWorkspace.MAX_EDIT_BYTES
}
internal data class SkillWorkbenchState(
    val name: String = "",
    val snapshot: SkillSnapshot? = null,
    val folder: String = "",
    val query: String = "",
    val selected: Set<String> = emptySet(),
    val editor: SkillEditBuffer? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val syncRoot: String? = null,
    val syncedRevision: String? = null,
)

class SkillDetailVM(private val context: Context, private val skillManager: SkillManager, private val bridge: TermuxSkillBridge) : ViewModel() {
    private val _state = MutableStateFlow(SkillWorkbenchState())
    internal val state = _state.asStateFlow()
    private lateinit var workspace: SkillWorkspace
    private var draftJob: Job? = null
    private val draftLock = kotlinx.coroutines.sync.Mutex()
    private val draftFile get() = File(context.filesDir, "skill_workbench/${_state.value.name}/draft.json")

    fun init(name: String) {
        if (_state.value.name == name) return
        _state.value = SkillWorkbenchState(name = name)
        val root = skillManager.getSkillDir(name) ?: return
        workspace = SkillWorkspace(root, File(context.filesDir, "skill_workbench"), name)
        action {
            _state.update { it.copy(snapshot = workspace.snapshot()) }
            if (draftFile.isFile) {
                val draft = runCatching { Json.parseToJsonElement(draftFile.readText()).jsonObject }.getOrNull()
                val path = draft?.get("path")?.jsonPrimitive?.contentOrNull
                if (path != null && SkillWorkspace.validPath(path)) {
                    val document = runCatching { workspace.open(path) }.getOrNull()
                    if (document != null) {
                        val hex = draft["hex"]?.jsonPrimitive?.booleanOrNull == true
                        val text = draft["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val oldHash = draft["hash"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val original = draft["baseline"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        _state.update { it.copy(editor = SkillEditBuffer(document.copy(hash = oldHash), TextFieldValue(text), original, hex), message = context.getString(R.string.skill_workbench_draft_restored)) }
                    }
                }
            }
        }
    }
    private fun action(after: (() -> Unit)? = null, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            var completed = false
            try { withContext(Dispatchers.IO) { block() }; completed = true }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(message = if (e is SkillConflict) context.getString(R.string.skill_workbench_conflict) else e.message.orEmpty()) } }
            finally { _state.update { it.copy(busy = false) } }
            if (completed) after?.invoke()
        }
    }
    fun query(value: String) { _state.update { it.copy(query = value) } }
    fun folder(path: String) { _state.update { it.copy(folder = path, query = "", selected = emptySet()) } }
    fun select(path: String) { _state.update { it.copy(selected = if (path in it.selected) it.selected - path else it.selected + path) } }
    fun clearSelection() { _state.update { it.copy(selected = emptySet()) } }
    fun refresh() = action { _state.update { it.copy(snapshot = workspace.snapshot()) } }
    fun open(path: String) = action {
        val document = workspace.open(path)
        val snapshot = workspace.snapshot()
        _state.update { it.copy(editor = buffer(document), snapshot = snapshot, selected = emptySet()) }
    }
    private fun buffer(document: SkillDocument, hex: Boolean = false): SkillEditBuffer {
        val text = if (hex) SkillWorkspace.encodeHex(document.bytes) else document.text?.takeIf { document.bytes.size <= SkillWorkspace.MAX_EDIT_BYTES }.orEmpty()
        return SkillEditBuffer(document, TextFieldValue(text), text, hex)
    }
    fun hexEditor() {
        val editor = _state.value.editor ?: return
        if (editor.document.bytes.size > SkillWorkspace.MAX_HEX_EDIT_BYTES) return
        _state.update { it.copy(editor = buffer(editor.document, true)) }
    }
    fun edit(value: TextFieldValue) {
        val editor = _state.value.editor ?: return
        if (_state.value.busy || !editor.editable) return
        if (value.text.length > SkillWorkspace.MAX_EDIT_BYTES * 3) return
        val changed = value.text != editor.value.text
        val undo = if (changed) history(editor.undo + editor.value.text) else editor.undo
        _state.update { it.copy(editor = editor.copy(value = value, undo = undo, redo = if (changed) emptyList() else editor.redo)) }
        if (changed) scheduleDraft()
    }
    private fun history(items: List<String>): List<String> {
        var size = 0
        return items.asReversed().take(60).takeWhile { size += it.length * 2; size <= 2 * 1024 * 1024 }.asReversed()
    }
    fun undo(redo: Boolean = false) {
        val editor = _state.value.editor ?: return
        val from = if (redo) editor.redo else editor.undo
        val text = from.lastOrNull() ?: return
        _state.update { it.copy(editor = editor.copy(value = TextFieldValue(text, TextRange(text.length)),
            undo = if (redo) history(editor.undo + editor.value.text) else editor.undo.dropLast(1),
            redo = if (redo) editor.redo.dropLast(1) else history(editor.redo + editor.value.text))) }
        scheduleDraft()
    }
    private fun scheduleDraft() {
        draftJob?.cancel()
        val editor = _state.value.editor ?: return
        draftJob = viewModelScope.launch(Dispatchers.IO) {
            delay(400)
            try {
                draftLock.withLock {
                    ensureActive()
                    val file = draftFile
                    if (!editor.dirty) { file.delete(); return@withLock }
                    file.parentFile!!.mkdirs()
                    val temp = File(file.parentFile, "draft.tmp")
                    temp.writeText(buildJsonObject {
                        put("path", editor.document.path); put("hash", editor.document.hash)
                        put("hex", editor.hex); put("text", editor.value.text); put("baseline", editor.baseline)
                    }.toString())
                    check(temp.renameTo(file))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(message = e.message) } }
        }
    }

    private suspend fun clearDraft() { draftJob?.cancelAndJoin(); draftJob = null; draftFile.delete() }
    fun discardThen(next: () -> Unit) = action(after = next) {
        clearDraft()
        _state.update { it.copy(editor = it.editor?.let { e -> buffer(e.document, e.hex) }) }
    }
    fun closeEditor(discard: Boolean = false) = action {
        require(discard || _state.value.editor?.dirty != true)
        clearDraft(); _state.update { it.copy(editor = null) }
    }
    fun save(onSaved: () -> Unit = {}) = action(after = onSaved) {
        val state = _state.value; val editor = state.editor ?: return@action
        val bytes = if (editor.hex) SkillWorkspace.decodeHex(editor.value.text) else editor.value.text.toByteArray(Charsets.UTF_8)
        require(editor.hex || bytes.size <= SkillWorkspace.MAX_EDIT_BYTES) { "Text editing is limited to 256 KiB" }
        val snapshot = workspace.write(editor.document.path, bytes, requireNotNull(state.snapshot).revision, editor.document.hash)
        clearDraft(); skillManager.invalidateSkill(state.name)
        val document = workspace.open(editor.document.path)
        _state.update { it.copy(snapshot = snapshot, editor = buffer(document, editor.hex), message = context.getString(R.string.skill_workbench_saved)) }
    }
    private fun mutate(block: (SkillWorkbenchState) -> SkillSnapshot) = action {
        val before = _state.value
        require(before.editor?.dirty != true) { "Save or discard the open draft first" }
        val snapshot = block(before)
        skillManager.invalidateSkill(before.name)
        clearDraft()
        _state.update { it.copy(snapshot = snapshot, selected = emptySet(), editor = null, folder = it.folder.takeIf { path -> path.isBlank() || snapshot.entries.any { e -> e.path == path && e.directory } }.orEmpty(), message = context.getString(R.string.skill_workbench_done)) }
    }
    fun create(path: String, directory: Boolean) = mutate { s ->
        if (directory) workspace.createDirectory(path, requireNotNull(s.snapshot).revision)
        else workspace.write(path, byteArrayOf(), requireNotNull(s.snapshot).revision, create = true)
    }
    fun rename(path: String, target: String, copy: Boolean = false) = mutate { workspace.rename(path, target, requireNotNull(it.snapshot).revision, copy) }
    fun move(paths: Set<String>, destination: String) = mutate { workspace.move(paths, destination, requireNotNull(it.snapshot).revision) }
    fun delete(paths: Set<String>) = mutate { workspace.delete(paths, requireNotNull(it.snapshot).revision) }
    fun restore() = mutate { workspace.restore(requireNotNull(it.snapshot).revision) }
    fun importFiles(uris: List<Uri>, folder: String) = mutate { state ->
        var total = 0
        val files = linkedMapOf<String, ByteArray>()
        uris.forEach { uri ->
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: error("Could not read the filename")
            require('/' !in name && '\\' !in name)
            val path = listOf(folder, name).filter { it.isNotEmpty() }.joinToString("/")
            require(path !in files) { "Duplicate filename: $name" }
            val bytes = readUri(uri); total += bytes.size
            require(total <= SkillPackage.MAX_BYTES)
            files[path] = bytes
        }
        workspace.importFiles(files, requireNotNull(state.snapshot).revision)
    }
    fun replaceFile(uri: Uri, path: String, hash: String) = mutate { workspace.write(path, readUri(uri), requireNotNull(it.snapshot).revision, hash) }
    private fun readUri(uri: Uri): ByteArray = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
        val bytes = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val n = input.read(buffer); if (n < 0) break; require(bytes.size().toLong() + n <= SkillPackage.MAX_BYTES) { "File exceeds 20 MiB" }; bytes.write(buffer, 0, n) }
        bytes.toByteArray()
    }
    fun export(uri: Uri, path: String?) = action {
        if (path == null) {
            val archive = File.createTempFile("skill-export-", ".zip", context.cacheDir)
            try { workspace.export(archive); requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { out -> archive.inputStream().use { it.copyTo(out) } } }
            finally { archive.delete() }
        } else requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(workspace.open(path).bytes) }
        _state.update { it.copy(message = context.getString(R.string.skill_workbench_exported)) }
    }
    fun sync() = action {
        require(_state.value.editor?.dirty != true) { "Save the draft before synchronizing" }
        val before = workspace.snapshot()
        val skill = skillManager.listSkills().find { it.name == _state.value.name } ?: error("Skill is unavailable")
        val result = bridge.prepare(skill)
        require(result["success"]?.jsonPrimitive?.booleanOrNull == true) { result.toString() }
        val path = result["skill_root"]?.jsonPrimitive?.contentOrNull ?: error("Termux did not return skill_root")
        val after = workspace.snapshot()
        _state.update { it.copy(snapshot = after, syncedRevision = before.revision.takeIf { before.revision == after.revision }, syncRoot = path, message = context.getString(R.string.skill_workbench_synced)) }
    }
}
