package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ToolExecutionTimerTest {
    @Test fun `elapsed is monotonic and finish freezes it across later reads`() {
        var nanos = 3_000_000_000L
        val timer = ToolExecutionTimer { nanos }
        nanos += 4_500_000_000L
        assertEquals(4500L, timer.elapsedMillis())
        assertTrue(timer.isRunning)
        assertEquals(4500L, timer.finish())
        nanos += 500_000_000_000L
        assertFalse(timer.isRunning)
        assertEquals(4500L, timer.elapsedMillis())
        assertEquals(4500L, timer.finish())
    }

    @Test fun `history keeps the duration but never restores a process clock`() {
        val tool = UIMessagePart.Tool("call", "termux_run_command", "{}",
            executionStartedAt = 123L, executionDurationMs = 4500L, executionTimer = ToolExecutionTimer())
        val encoded = Json.encodeToString(UIMessagePart.Tool.serializer(), tool)
        assertFalse(encoded.contains("executionTimer"))
        val decoded = Json.decodeFromString(UIMessagePart.Tool.serializer(), encoded)
        assertEquals(4500L, decoded.executionDurationMs)
        assertNull(decoded.executionTimer)
    }

    @Test fun `old history without timing never invents zero duration`() {
        val decoded = Json.decodeFromString(UIMessagePart.Tool.serializer(),
            """{"toolCallId":"old","toolName":"termux_run_command","input":"{}","executionStartedAt":123}""")
        assertNull(decoded.executionDurationMs)
        assertNull(decoded.executionTimer)
    }
}
