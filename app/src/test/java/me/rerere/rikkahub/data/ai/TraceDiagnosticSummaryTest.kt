package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TraceDiagnosticSummaryTest {
    @Test fun `diagnostic allowlist never copies user strings hashes ids or errors`() {
        val secret = "synthetic-secret-user-text"
        val record = TraceRecord(1, 42, secret, secret, secret, secret,
            TraceSummary(operation = secret, kind = secret, title = secret, phase = secret, state = secret,
                parent = secret, preview = secret, childConversation = secret, inputTokens = 30, elapsedMs = 120))
        val exported = diagnosticSummary(TracePage(listOf(record), null, 9, secret))
        assertFalse(exported.contains(secret))
        val result = Json.parseToJsonElement(exported).jsonObject
        assertTrue(result.getValue("truncated").jsonPrimitive.boolean)
        assertTrue(result.getValue("integrity_warning").jsonPrimitive.boolean)
        assertEquals(30, result.getValue("events").jsonArray.single().jsonObject.getValue("input_tokens").jsonPrimitive.int)
    }
}
