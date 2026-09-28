package me.rerere.rikkahub.ui.pages.extensions.workspace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import org.junit.Assert.*
import org.junit.Test

class WorkspaceFilesControllerTest {
    private fun entry(path: String, directory: Boolean = false) = WorkspaceFileEntry(path, path, directory, 1, 0)

    @Test fun `slow old area response cannot overwrite selected area or action target`() = runBlocking {
        val state = MutableStateFlow(WorkspaceDetailState())
        val old = CompletableDeferred<List<WorkspaceFileEntry>>()
        val linux = entry("same-path")
        val controller = WorkspaceFilesController(state, this) { area, _ ->
            if (area == WorkspaceStorageArea.FILES) old.await() else listOf(linux)
        }
        controller.refresh(); yield()
        controller.selectArea(WorkspaceStorageArea.LINUX); yield()
        val target = WorkspaceFileTarget(linux, state.value.area)
        old.complete(listOf(entry("wrong-file"))); yield()
        assertEquals(listOf(linux), state.value.entries)
        assertEquals(WorkspaceStorageArea.LINUX, state.value.area)
        controller.selectArea(WorkspaceStorageArea.FILES); yield()
        assertEquals(WorkspaceStorageArea.LINUX, target.area)
    }

    @Test fun `old failure and expanded directory result do not pollute new directory`() = runBlocking {
        val state = MutableStateFlow(WorkspaceDetailState())
        val old = CompletableDeferred<List<WorkspaceFileEntry>>()
        val expansion = CompletableDeferred<List<WorkspaceFileEntry>>()
        val controller = WorkspaceFilesController(state, this) { _, path ->
            when (path) { "" -> old.await(); "expanded" -> expansion.await(); else -> listOf(entry("new/ok")) }
        }
        controller.refresh(); yield()
        controller.toggleExpand(entry("expanded", true)); yield()
        controller.open(entry("new", true)); yield()
        old.completeExceptionally(IllegalStateException("stale failure"))
        expansion.complete(listOf(entry("expanded/old"))); yield()
        assertEquals("new", state.value.path)
        assertEquals(listOf(entry("new/ok")), state.value.entries)
        assertTrue(state.value.childrenCache.isEmpty())
        assertNull(state.value.error)
        assertFalse(state.value.loading)
    }
}
