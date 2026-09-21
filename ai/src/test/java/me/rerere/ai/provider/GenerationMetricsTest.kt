package me.rerere.ai.provider

import kotlinx.coroutines.launch
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.*
import org.junit.Test

class GenerationMetricsTest {
    @Test fun `old queued work is not starved by perpetual high priority continuations`() = kotlinx.coroutines.runBlocking {
        var now = 0L
        val queue = GenerationRequestQueue { now }
        val opened = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = launch { queue.withSlot(GenerationPriority.CONTINUATION, 1) { opened.complete(Unit); release.await() } }
        opened.await()
        val old = launch { queue.withSlot(GenerationPriority.TITLE, 1) { order.add("old") } }
        kotlinx.coroutines.yield()
        now = 240_000
        val fresh = launch { queue.withSlot(GenerationPriority.CONTINUATION, 1) { order.add("fresh") } }
        kotlinx.coroutines.yield()
        release.complete(Unit)
        first.join(); old.join(); fresh.join()
        assertEquals(listOf("old", "fresh"), order)
    }

    @Test fun `queue and cold prefill do not dilute decode rate and tool time cannot change it`() {
        var now = 0L
        val tracker = GenerationProgressTracker { now }
        val request = tracker.begin()
        now = 120_000; request.dispatched()
        now = 534_000; request.chunk(StreamChunk.TextDelta("x", "first"))
        now = 554_000; request.chunk(StreamChunk.TextDelta("x", "last"))
        request.usage(TokenUsage(completionTokens = 1000))
        request.finish(GenerationPhase.COMPLETED)
        now += 900_000 // lengthy external tool
        val metric = tracker.completedMetrics().single()
        assertEquals(120_000L, metric.queueMs)
        assertEquals(414_000L, metric.firstContentMs)
        assertEquals(20_000L, metric.receivingMs)
        assertEquals(50.0, metric.tokensPerSecond!!, 0.001)
    }
    @Test fun `weighted rate ignores nonstream and absent usage instead of fabricating speed`() {
        fun metric(id: String, tokens: Int, ms: Long, streamed: Boolean = true) = GenerationRequestMetrics(
            requestId = id, phase = "COMPLETED", totalMs = 999_999, queueMs = 500_000,
            receivingMs = ms, usage = TokenUsage(completionTokens = tokens), streamed = streamed)
        assertEquals(40.0, listOf(metric("a", 1000, 20_000), metric("b", 200, 10_000), metric("c", 5000, 1, false)).generationTokensPerSecond()!!, .001)
        assertNull(emptyList<GenerationRequestMetrics>().generationTokensPerSecond())
        assertNull(metric("empty", 0, 3000).tokensPerSecond)
        assertNull(metric("single", 100, 0).tokensPerSecond)
    }
}
