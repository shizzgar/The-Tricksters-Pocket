package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.preferences.TermuxRuntime
import me.rerere.rikkahub.data.ai.tools.local.TmuxOps
import org.junit.Assert.*
import org.junit.Test

class TermuxWorkspaceContextTest {
    private fun workspace(path: String?) = WorkspaceEntity("id", "Project", "local", createdAt = 0, updatedAt = 0, termuxPath = path)
    @Test fun `workspace is a default directory and allows leaving it`() {
        val ctx = workspace("/home/project").termuxContext("src")!!
        assertEquals("/home/project/src", ctx.workingDirectory)
        assertEquals("/home/other", resolveTermuxWorkingDirectory("../../other", ctx.workingDirectory))
        assertEquals("/tmp", resolveTermuxWorkingDirectory("/tmp", ctx.workingDirectory))
        assertEquals("workspace:id", ctx.owner)
        assertNull(workspace(null).termuxContext())
    }
    @Test fun `parallel invocations never mutate global default`() {
        val original = TermuxRuntime.defaultWorkingDir
        val first = workspace("/first").termuxContext()!!
        val second = workspace("/second").termuxContext()!!
        assertEquals("/first", resolveTermuxWorkingDirectory(null, first.workingDirectory))
        assertEquals("/second/sub", resolveTermuxWorkingDirectory("sub", second.workingDirectory))
        assertEquals(original, resolveTermuxWorkingDirectory(null))
        assertEquals(original, TermuxRuntime.defaultWorkingDir)
    }
    @Test fun `pty passes directory as one argv element`() {
        val argv = TmuxOps.startArgv("rk_test", 120, 50, "/home/project with spaces")
        assertEquals(listOf("-c", "/home/project with spaces"), argv.takeLast(2))
    }
    @Test fun `approval grants do not leak between workspace and global Termux`() {
        assertTrue(isScopedWorkspaceTool("termux_job_start", true))
        assertFalse(isScopedWorkspaceTool("termux_job_start", false))
        assertTrue(isScopedWorkspaceTool("workspace_shell", false))
        assertFalse(isScopedWorkspaceTool("search_web", true))
        assertTrue(resolveWorkspaceToolApproval("termux_run_command", emptyMap()))
        assertFalse(resolveWorkspaceToolApproval("termux_run_command", mapOf("termux_run_command" to false)))
        assertFalse(resolveWorkspaceToolApproval("termux_job_read", emptyMap()))
    }
}
