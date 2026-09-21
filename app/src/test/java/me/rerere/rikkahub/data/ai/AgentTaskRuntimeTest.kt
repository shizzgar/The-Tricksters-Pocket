package me.rerere.rikkahub.data.ai

import org.junit.Assert.*
import org.junit.Test

class AgentTaskRuntimeTest {
    @Test fun `only progressing soft boundaries may continue automatically`() {
        GenerationStopReason.entries.forEach { reason ->
            val outcome = GenerationSliceOutcome(reason)
            assertEquals(reason in setOf(GenerationStopReason.STEP_LIMIT, GenerationStopReason.CYCLE_DEADLINE, GenerationStopReason.COMPACTION_LIMIT), shouldContinueTask(true, outcome, true))
            assertFalse(shouldContinueTask(true, outcome, false))
            assertFalse(shouldContinueTask(false, outcome, true))
        }
    }
}
