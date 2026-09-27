package me.rerere.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageTest {
    @org.junit.Test
    fun `aggregate scope survives partial output but resets for a fresh prompt`() {
        val aggregate = TokenUsage(promptTokens = 900, completionTokens = 20, aggregatedRequests = true)
        val output = aggregate.merge(TokenUsage(completionTokens = 30))
        org.junit.Assert.assertTrue(output.aggregatedRequests)
        val next = output.merge(TokenUsage(promptTokens = 500, completionTokens = 10))
        org.junit.Assert.assertFalse(next.aggregatedRequests)
        org.junit.Assert.assertEquals(510, next.totalTokens)
    }

    @Test
    fun `merge carries cost from the incoming chunk`() {
        val merged = (null as TokenUsage?).merge(
            TokenUsage(promptTokens = 10, completionTokens = 5, cost = 0.0012)
        )
        assertEquals(0.0012, merged.cost!!, 1e-9)
    }

    @Test
    fun `merge keeps prior cost when the incoming chunk has none`() {
        // Streaming: an early chunk reported cost; a later token-only delta must not wipe it.
        val merged = TokenUsage(cost = 0.0034).merge(TokenUsage(completionTokens = 3))
        assertEquals(0.0034, merged.cost!!, 1e-9)
    }

    @Test
    fun `merge prefers the newest cost`() {
        val merged = TokenUsage(cost = 0.001).merge(TokenUsage(cost = 0.002))
        assertEquals(0.002, merged.cost!!, 1e-9)
    }

    @Test
    fun `merge leaves cost null when neither side reports it`() {
        val merged = TokenUsage(promptTokens = 1).merge(TokenUsage(completionTokens = 1))
        assertNull(merged.cost)
    }
}
