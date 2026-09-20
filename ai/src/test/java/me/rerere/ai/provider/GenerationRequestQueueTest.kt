package me.rerere.ai.provider

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GenerationRequestQueueTest {
    @Test fun `waiting continuation overtakes title and cancellation never dispatches`() = runBlocking {
        val queue = GenerationRequestQueue()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val active = launch(start = CoroutineStart.UNDISPATCHED) { queue.withSlot(GenerationPriority.INTERACTIVE, 1) { release.await() } }
        val title = launch(start = CoroutineStart.UNDISPATCHED) { queue.withSlot(GenerationPriority.TITLE, 1) { events.add("title") } }
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { queue.withSlot(GenerationPriority.CONTINUATION, 1) { error("Cancelled waiter dispatched") } }
        val continuation = launch(start = CoroutineStart.UNDISPATCHED) { queue.withSlot(GenerationPriority.CONTINUATION, 1) { events.add("continuation") } }
        cancelled.cancelAndJoin()
        release.complete(Unit)
        withTimeout(5_000) { joinAll(active, title, continuation) }
        assertEquals(listOf("continuation", "title"), events)
    }
    @Test fun `two slots are bounded and cancellation releases one`() = runBlocking {
        val queue = GenerationRequestQueue()
        val gate = CompletableDeferred<Unit>()
        var active = 0
        var peak = 0
        val jobs = (1..4).map { launch(start = CoroutineStart.UNDISPATCHED) {
            queue.withSlot(GenerationPriority.COMPACTION, 2) {
                active++; peak = maxOf(peak, active)
                try { gate.await() } finally { active-- }
            }
        } }
        assertEquals(2, active)
        jobs.first().cancelAndJoin()
        yield()
        assertEquals(2, active)
        gate.complete(Unit)
        withTimeout(5_000) { jobs.joinAll() }
        assertEquals(2, peak)
        assertEquals(0, active)
    }
    @Test fun `cancellation racing a granted ticket does not leak capacity`() = runBlocking {
        repeat(50) {
            val queue = GenerationRequestQueue()
            val gate = CompletableDeferred<Unit>()
            val first = launch(start = CoroutineStart.UNDISPATCHED) { queue.withSlot(GenerationPriority.INTERACTIVE, 1) { gate.await() } }
            val second = launch(start = CoroutineStart.UNDISPATCHED) { queue.withSlot(GenerationPriority.INTERACTIVE, 1) { yield() } }
            gate.complete(Unit)
            first.join()
            second.cancelAndJoin()
            withTimeout(1_000) { queue.withSlot(GenerationPriority.INTERACTIVE, 1) { } }
        }
    }
}
