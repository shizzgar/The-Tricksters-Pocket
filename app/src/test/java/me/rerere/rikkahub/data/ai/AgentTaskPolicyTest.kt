package me.rerere.rikkahub.data.ai

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class AgentTaskPolicyTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `step reservation survives approval and simulated process restart`() {
        val dir = temp.newFolder("first")
        val id = UUID.randomUUID().toString()
        AgentTaskPolicy.initialize(dir)
        AgentTaskPolicy.set(id, ScopedAgentPolicy(1, deadlineAtMs = 9000, allowedTools = setOf("workspace_read_file")))
        assertNull(AgentTaskPolicy.reserveStep(id, now = 100))
        // Loading a different store discards process-local state, then reload the original.
        AgentTaskPolicy.initialize(temp.newFolder("other"))
        AgentTaskPolicy.initialize(dir)
        assertEquals(1, AgentTaskPolicy.get(id)?.usedSteps)
        assertEquals(GenerationStopReason.RUN_STEP_LIMIT, AgentTaskPolicy.reserveStep(id, now = 101))
        assertTrue(AgentToolPolicy.permits("workspace_read_file", id, false))
        assertFalse(AgentToolPolicy.permits("workspace_write_file", id, false))
        assertFalse(GenerationStopReason.RUN_STEP_LIMIT.canContinueAutomatically())
    }

    @Test fun `deadline and cancellation survive restart and deny new requests`() {
        val dir = temp.newFolder()
        AgentTaskPolicy.initialize(dir)
        val id = UUID.randomUUID().toString()
        AgentTaskPolicy.set(id, ScopedAgentPolicy(5, deadlineAtMs = 100))
        assertEquals(GenerationStopReason.TASK_DEADLINE, AgentTaskPolicy.reserveStep(id, now = 100))
        AgentTaskPolicy.stop(id)
        AgentTaskPolicy.initialize(temp.newFolder())
        AgentTaskPolicy.initialize(dir)
        assertEquals(GenerationStopReason.CANCELLED, AgentTaskPolicy.reserveStep(id, now = 10))
    }

    @Test fun `corrupt policy fails closed rather than restoring unrestricted tools`() {
        val dir = temp.newFolder()
        val id = UUID.randomUUID().toString()
        File(dir, "agent-policies").mkdirs()
        File(dir, "agent-policies/$id.json").writeText("incomplete")
        AgentTaskPolicy.initialize(dir)
        assertFalse(AgentToolPolicy.permits("workspace_read_file", id, false))
        assertEquals(GenerationStopReason.CANCELLED, AgentTaskPolicy.reserveStep(id))
    }

    @Test fun `readonly is retained when assistant changes and unknown tools never qualify`() {
        AgentTaskPolicy.initialize(temp.newFolder())
        val id = UUID.randomUUID().toString()
        AgentTaskPolicy.set(id, ScopedAgentPolicy(3, readOnly = true))
        listOf("workspace_shell", "termux_execute", "workspace_write_file", "mcp__server__read", "use_skill", "termux_skill_sync")
            .forEach { assertFalse(it, AgentToolPolicy.permits(it, id, false)) }
        listOf("workspace_read_file", "web_fetch", "skill_get_content", "conversation_history_read")
            .forEach { assertTrue(it, AgentToolPolicy.permits(it, id, false)) }
    }
    @Test fun `review keeps scoped workspace and read only tools across restart`() {
        val directory = temp.newFolder()
        val id = UUID.randomUUID().toString()
        val workspace = UUID.randomUUID().toString()
        AgentTaskPolicy.initialize(directory)
        AgentTaskPolicy.set(id, me.rerere.rikkahub.ui.pages.chat.taskReviewPolicy(workspace))
        AgentTaskPolicy.initialize(temp.newFolder())
        AgentTaskPolicy.initialize(directory)
        assertEquals(workspace, AgentTaskPolicy.get(id)?.scopedWorkspaceId)
        assertTrue(AgentTaskPolicy.get(id)?.readOnly == true)
        assertFalse(AgentToolPolicy.permits("workspace_shell", id, false))
        assertFalse(AgentToolPolicy.permits("workspace_write_file", id, false))
        assertTrue(AgentToolPolicy.permits("workspace_read_file", id, false))
        assertThrows(IllegalArgumentException::class.java) { ScopedAgentPolicy(5, scopedWorkspaceId = "../workspace") }
    }

    @Test fun `restored review reconstructs read only scope when execution policy was excluded from backup`() {
        val directory = temp.newFolder()
        val id = UUID.randomUUID().toString()
        val workspace = UUID.randomUUID().toString()
        AgentTaskPolicy.initialize(directory)
        assertNull(AgentTaskPolicy.get(id))

        AgentTaskPolicy.ensureReview(id, workspace)

        assertTrue(AgentToolPolicy.permits("workspace_read_file", id, readOnly = false))
        listOf("workspace_write_file", "workspace_shell", "termux_job_start", "mcp__server__write")
            .forEach { assertFalse(it, AgentToolPolicy.permits(it, id, readOnly = false)) }
        assertEquals(workspace, AgentTaskPolicy.get(id)?.scopedWorkspaceId)
        AgentTaskPolicy.initialize(temp.newFolder())
        AgentTaskPolicy.initialize(directory)
        assertTrue(AgentTaskPolicy.get(id)?.readOnly == true)
        assertEquals(workspace, AgentTaskPolicy.get(id)?.scopedWorkspaceId)
    }

    @Test fun `ordinary review restoration keeps stop and explicit continuation preserves all execution limits`() {
        AgentTaskPolicy.initialize(temp.newFolder())
        val id = UUID.randomUUID().toString()
        val workspace = UUID.randomUUID().toString()
        val existing = ScopedAgentPolicy(
            maxSteps = 4, usedSteps = 3, deadlineAtMs = 9000,
            allowedTools = setOf("workspace_read_file"), readOnly = false,
            systemPrompt = "Review only the selected evidence", stopped = true,
        )
        AgentTaskPolicy.set(id, existing)

        AgentTaskPolicy.ensureReview(id, workspace)
        val stopped = existing.copy(readOnly = true, scopedWorkspaceId = workspace)
        assertEquals(stopped, AgentTaskPolicy.get(id))
        assertEquals(GenerationStopReason.CANCELLED, AgentTaskPolicy.check(id, now = 100))

        AgentTaskPolicy.ensureReview(id, workspace, resumeStopped = true)
        assertEquals(stopped.copy(stopped = false), AgentTaskPolicy.get(id))
        assertFalse(AgentToolPolicy.permits("web_fetch", id, readOnly = false))
        assertFalse(AgentToolPolicy.permits("workspace_write_file", id, readOnly = false))
        assertEquals(GenerationStopReason.TASK_DEADLINE, AgentTaskPolicy.check(id, now = 9000))
        assertNull(AgentTaskPolicy.reserveStep(id, now = 100))
        assertEquals(GenerationStopReason.RUN_STEP_LIMIT, AgentTaskPolicy.reserveStep(id, now = 101))
        AgentTaskPolicy.ensureReview(id, workspace, resumeStopped = true)
        assertEquals(GenerationStopReason.RUN_STEP_LIMIT, AgentTaskPolicy.check(id, now = 102))
    }

}
