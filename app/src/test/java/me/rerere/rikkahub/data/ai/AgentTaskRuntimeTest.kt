package me.rerere.rikkahub.data.ai

import org.junit.Assert.*
import org.junit.Test

class AgentTaskRuntimeTest {
    @Test fun `stop or edits racing startup prevent automatic recovery`() {
        val task = AgentTaskRecord("test", checkpoint = "persisted")
        assertTrue(mayRestoreAgentTask(task, "persisted", true))
        assertFalse(mayRestoreAgentTask(task.copy(status = "cancelled"), "persisted", true))
        assertFalse(mayRestoreAgentTask(task.copy(status = "paused"), "persisted", true))
        assertFalse(mayRestoreAgentTask(task.copy(recoverAutomatically = false), "persisted", true))
        assertFalse(mayRestoreAgentTask(task, "edited", true))
        assertFalse(mayRestoreAgentTask(task, "persisted", false))
        assertFalse(mayRestoreAgentTask(null, "persisted", true))
    }

    @Test fun `network recovery excludes partial output cancellation and local failures`() {
        assertTrue(canWaitForNetwork(java.net.SocketTimeoutException(), false))
        assertFalse(canWaitForNetwork(java.net.SocketTimeoutException(), true))
        assertFalse(canWaitForNetwork(kotlinx.coroutines.CancellationException(), false))
        assertFalse(canWaitForNetwork(java.io.IOException("disk full"), false))
        assertTrue(canWaitForNetwork(me.rerere.ai.util.HttpException("backend recovering", 503), false))
        assertFalse(canWaitForNetwork(me.rerere.ai.util.HttpException("backend recovering", 502), true))
        assertFalse(canWaitForNetwork(me.rerere.ai.util.HttpException("invalid key", 401), false))
        assertFalse(canWaitForNetwork(me.rerere.ai.util.HttpException("quota exhausted", 429), false))
        assertFalse(canWaitForNetwork(IllegalStateException("invalid model"), false))
    }

    @Test fun `only progressing soft boundaries may continue automatically`() {
        GenerationStopReason.entries.forEach { reason ->
            val outcome = GenerationSliceOutcome(reason)
            assertEquals(reason in setOf(GenerationStopReason.STEP_LIMIT, GenerationStopReason.CYCLE_DEADLINE, GenerationStopReason.COMPACTION_LIMIT, GenerationStopReason.OUTPUT_LIMIT), shouldContinueTask(true, outcome, true))
            assertFalse(shouldContinueTask(true, outcome, false))
            assertFalse(shouldContinueTask(false, outcome, true))
        }
    }
}
