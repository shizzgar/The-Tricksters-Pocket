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
}
