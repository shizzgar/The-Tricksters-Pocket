package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.ai.transformers.buildWorkspaceReminder
import org.junit.Assert.*
import org.junit.Test

class TermuxWorkspacePathTest {
    private val root = "/data/data/com.termux/files/home/project"
    @Test fun `real paths and workspace file aliases map to the selected root`() {
        assertEquals("src/a.kt", TermuxWorkspaceBridge.relativePath(root, "$root/src/a.kt"))
        assertEquals("src/a.kt", TermuxWorkspaceBridge.relativePath(root, "/workspace/src/a.kt"))
        assertEquals("", TermuxWorkspaceBridge.relativePath(root, root))
        assertEquals("", TermuxWorkspaceBridge.relativePath(root, "/workspace"))
    }
    @Test fun `paths cannot use prefix collisions parent segments or absolute escapes`() {
        for (path in listOf("${root}-other/a", "../a", "/etc/passwd", "src/../a", "/workspace/../a", "x\u0000y")) {
            assertTrue(path, runCatching { TermuxWorkspaceBridge.relativePath(root, path) }.isFailure)
        }
    }
    @Test fun `prompt describes actual Termux paths jobs and skill synchronization`() {
        val workspace = WorkspaceEntity("id", "project", "id", "READY", 0, 0, termuxPath = root)
        val prompt = requireNotNull(buildWorkspaceReminder(workspace, true, "$root/src"))
        assertTrue(prompt.contains(root))
        assertTrue(prompt.contains("termux_skill_sync"))
        assertTrue(prompt.contains("workspace_run_background"))
        assertTrue(prompt.contains("not confined"))
        assertFalse(prompt.contains("mounted at `/skills`"))
        assertFalse(prompt.contains("install its rootfs"))
    }
}
