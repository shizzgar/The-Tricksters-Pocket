package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompactionRuntimeTest {
    @Test fun `normalization bounds imported values and reserves an entire request`() {
        val limits = CompactionRuntimeLimits.fromMinutes(Int.MAX_VALUE, -1, 99)
        assertEquals(120 * 60_000L, limits.requestTimeoutMs)
        assertEquals(limits.requestTimeoutMs, limits.totalTimeoutMs)
        assertEquals(4, limits.parallelRequests)
        val lower = CompactionRuntimeLimits.fromMinutes(-1, -1, -1)
        assertEquals(60_000L, lower.requestTimeoutMs)
        assertEquals(60_000L, lower.totalTimeoutMs)
        assertEquals(1, lower.parallelRequests)
    }

    @Test fun `request timeout reports its stage`() = runBlocking {
        val limits = CompactionRuntimeLimits(20, 1_000, 1)
        try {
            limits.operation { limits.request<String> { awaitCancellation() } }
            fail("Expected request timeout")
        } catch (e: CompactionTimeoutException) {
            assertTrue(e.message.orEmpty().contains("request exceeded"))
        }
    }

    @Test fun `total timeout includes time outside a model request`() = runBlocking {
        val limits = CompactionRuntimeLimits(20, 40, 1)
        try {
            limits.operation<String> { awaitCancellation() }
            fail("Expected operation timeout")
        } catch (e: CompactionTimeoutException) {
            assertTrue(e.message.orEmpty().contains("operation exceeded"))
        }
    }

    @Test fun `user cancellation is not converted to a timeout failure`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val limits = CompactionRuntimeLimits(60_000, 120_000, 1)
        var sawCancellation = false
        val job = launch {
            try {
                limits.operation {
                    limits.request<String> {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            } catch (e: CancellationException) {
                sawCancellation = true
                throw e
            }
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(sawCancellation)
    }
}
