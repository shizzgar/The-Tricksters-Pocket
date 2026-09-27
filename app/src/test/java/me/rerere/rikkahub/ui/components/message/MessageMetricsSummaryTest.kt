package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.GenerationPhase
import me.rerere.ai.provider.GenerationProgress
import me.rerere.ai.provider.GenerationRequestMetrics
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.*
import org.junit.Test

class MessageMetricsSummaryTest {
    private fun request(id: String, input: Int, output: Int, phase: String = "COMPLETED") =
        GenerationRequestMetrics(id, phase, 2000, 100, receivingMs = 1000,
            usage = TokenUsage(promptTokens = input, completionTokens = output, totalTokens = input + output))

    @Test fun latestRequestAndWholeReplyTotalsStaySeparate() {
        val message = UIMessage.assistant("result").copy(generationMetrics = listOf(request("first", 100, 20), request("second", 800, 30)))
        val summary = MessageMetricsSummary.from(message, null, 0)
        assertEquals(800, summary.latestUsage!!.promptTokens)
        assertEquals(30, summary.latestUsage!!.completionTokens)
        assertEquals(900L, summary.inputTokens)
        assertEquals(50L, summary.outputTokens)
        assertEquals(25.0, summary.speed!!, 0.001)
        assertFalse(summary.partial)
    }

    @Test fun repeatedRequestUsesFreshObservationWithoutDoubleCounting() {
        val message = UIMessage.assistant("").copy(generationMetrics = listOf(request("same", 100, 2, "RECEIVING")))
        val live = GenerationProgress(1, GenerationPhase.RECEIVING, 0, dispatchedAt = 10,
            requestId = "same", usage = TokenUsage(promptTokens = 100, completionTokens = 20, totalTokens = 120))
        val summary = MessageMetricsSummary.from(message, live, 1000)
        assertEquals(1, summary.requests.size)
        assertEquals(20L, summary.outputTokens)
        assertTrue(summary.partial)
    }

    @Test fun completedRequestCannotBeReplacedByStaleRunningState() {
        val message = UIMessage.assistant("").copy(generationMetrics = listOf(request("same", 100, 40)))
        val stale = GenerationProgress(1, GenerationPhase.RECEIVING, 0, dispatchedAt = 10,
            requestId = "same", usage = TokenUsage(promptTokens = 100, completionTokens = 5, totalTokens = 105))
        val summary = MessageMetricsSummary.from(message, stale, 10_000)
        assertEquals(40L, summary.outputTokens)
        assertFalse(summary.partial)
        assertEquals(2000L, summary.latest!!.totalMs)
    }

    @Test fun legacyAggregateIsNeverCalledTheLatestRequest() {
        val message = UIMessage.assistant("").copy(usage = TokenUsage(promptTokens = 1000, completionTokens = 100, totalTokens = 1100))
        val summary = MessageMetricsSummary.from(message, null, 0)
        assertNull(summary.latestUsage)
        assertNull(summary.speed)
        assertEquals(100L, summary.outputTokens)
        assertNotNull(summary.legacyUsage)
    }

    @Test fun staleObservationCannotReorderCompletedRequests() {
        val message = UIMessage.assistant("").copy(generationMetrics = listOf(request("first", 100, 20), request("latest", 800, 30)))
        val stale = GenerationProgress(1, GenerationPhase.RECEIVING, 0, dispatchedAt = 10,
            requestId = "first", usage = TokenUsage(promptTokens = 100, completionTokens = 5))
        val summary = MessageMetricsSummary.from(message, stale, 10_000)
        assertEquals("latest", summary.latest!!.requestId)
        assertEquals(800, summary.latestUsage!!.promptTokens)
        assertEquals(50L, summary.outputTokens)
    }

    @Test fun interruptedUsageIsExplicitlyPartial() {
        val message = UIMessage.assistant("").copy(generationMetrics = listOf(request("cancelled", 100, 5, "CANCELLED")))
        val summary = MessageMetricsSummary.from(message, null, 0)
        assertEquals(5L, summary.outputTokens)
        assertTrue(summary.partial)
    }

    @Test fun missingLatestUsageDoesNotBorrowEarlierInput() {
        val message = UIMessage.assistant("").copy(generationMetrics = listOf(request("first", 100, 20), request("second", 800, 30).copy(usage = null)))
        val summary = MessageMetricsSummary.from(message, null, 0)
        assertNull(summary.latestUsage)
        assertEquals(20L, summary.outputTokens)
        assertEquals(1, summary.measuredRequests)
        assertTrue(summary.partial)
    }
}
