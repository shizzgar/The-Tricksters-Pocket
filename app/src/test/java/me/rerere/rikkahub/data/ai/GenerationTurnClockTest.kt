package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GenerationTurnClockTest {
    @Test fun `long compaction does not consume model and tool time`() = runBlocking {
        var now = 0L
        val clock = GenerationTurnClock(3_600_000) { now }
        now = 120_000
        assertEquals(120_000L, clock.activeElapsedMs())
        assertEquals("summary", clock.duringCompaction { now += 900_000; "summary" })
        assertEquals(120_000L, clock.activeElapsedMs())
        now += 30_000
        assertEquals(150_000L, clock.activeElapsedMs())
    }

    @Test fun `callback returning null is not a timeout`() = runBlocking {
        val clock = GenerationTurnClock(60_000) { 0L }
        assertNull(clock.duringCompaction<String?> { null })
    }

    @Test fun `maintenance allowance is cumulative rather than reset by each compaction`() = runBlocking {
        var now = 0L
        val clock = GenerationTurnClock(60_000) { now }
        clock.duringCompaction { now += 30_000 }
        clock.duringCompaction { now += 30_000 }
        var thirdStarted = false
        try {
            clock.duringCompaction { thirdStarted = true }
            fail("Expected exhausted compaction allowance")
        } catch (e: CompactionTimeoutException) {
            assertTrue(e.message.orEmpty().contains("turn allowance"))
        }
        assertFalse(thirdStarted)
        assertEquals(0L, clock.activeElapsedMs())
    }
}
