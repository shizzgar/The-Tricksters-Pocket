package me.rerere.rikkahub.ui.pages.extensions.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea

internal data class WorkspaceFileTarget(val entry: WorkspaceFileEntry, val area: WorkspaceStorageArea)

/** Owns the request identity as well as navigation; late I/O cannot change a newer view. */
internal class WorkspaceFilesController(
    private val state: MutableStateFlow<WorkspaceDetailState>,
    private val scope: CoroutineScope,
    private val list: suspend (WorkspaceStorageArea, String) -> List<WorkspaceFileEntry>,
) {
    private var generation = 0L

    fun selectArea(area: WorkspaceStorageArea) {
        state.update { it.copy(area = area, path = "", entries = emptyList(), error = null) }
        refresh()
    }

    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        state.update { it.copy(path = entry.path, entries = emptyList(), error = null) }
        refresh()
    }

    fun goUp() {
        if (state.value.path.isBlank()) return
        state.update { it.copy(path = it.path.substringBeforeLast('/', ""), entries = emptyList(), error = null) }
        refresh()
    }

    fun refresh() {
        val request = ++generation
        val selected = state.value
        state.update { it.copy(loading = true, error = null, expandedPaths = emptySet(), childrenCache = emptyMap()) }
        scope.launch {
            try {
                val entries = list(selected.area, selected.path)
                if (request == generation) state.update { it.copy(entries = entries, loading = false) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (request == generation) state.update {
                    it.copy(entries = emptyList(), loading = false, error = error.message ?: "Failed to load workspace files")
                }
            }
        }
    }

    fun toggleExpand(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        val path = entry.path
        if (path in state.value.expandedPaths) {
            state.update { it.copy(expandedPaths = it.expandedPaths - path) }
            return
        }
        state.update { it.copy(expandedPaths = it.expandedPaths + path) }
        if (path in state.value.childrenCache) return
        val selected = state.value
        val request = generation
        scope.launch {
            try {
                val children = list(selected.area, path)
                if (request == generation) state.update { it.copy(childrenCache = it.childrenCache + (path to children)) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (request == generation) state.update {
                    it.copy(expandedPaths = it.expandedPaths - path, error = error.message ?: "Failed to load workspace files")
                }
            }
        }
    }
}
