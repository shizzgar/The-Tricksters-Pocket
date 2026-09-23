package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TrajectoryTest {
    private var sequence = 0L
    private fun event(at: Long, source: String, payload: String): TraceEntry {
        val data = Json.parseToJsonElement(payload).jsonObject
        return TraceEntry(TraceRecord(++sequence, at, source, "", "", ""), traceSummary(source, data))
    }

    @Test fun `concurrent model and tool spans pair by id and retain parent`() {
        val events = listOf(
            event(100, "task.started", """{"run_id":"r"}"""),
            event(200, "model.request", """{"request_id":"a","model":"A"}"""),
            event(300, "model.request", """{"request_id":"b","model":"B"}"""),
            event(400, "model.response", """{"request_id":"b","elapsed_ms":100,"prompt_tokens":10,"completion_tokens":5}"""),
            event(500, "model.response", """{"request_id":"a","elapsed_ms":300}"""),
            event(600, "tool.started", """{"tool_call_id":"t","tool":"python","parent_request_id":"a"}"""),
            event(800, "tool.result", """{"tool_call_id":"t","tool":"python","output":[]}"""),
        )
        val spans = buildTraceSpans(events, false).associateBy { it.id }
        assertEquals(300L, spans.getValue("model:a").durationMs)
        assertEquals(100L, spans.getValue("model:b").durationMs)
        assertEquals(10L, spans.getValue("model:b").inputTokens)
        assertEquals("model:a", spans.getValue("tool:t").parent)
        assertEquals("task:r", spans.getValue("tool:t").run)
    }

    @Test fun `missing result is unknown after process loss and not a success`() {
        val events = listOf(event(100, "tool.started", """{"tool_call_id":"t","tool":"cmd"}"""))
        val span = buildTraceSpans(events, false).single()
        assertEquals("incomplete", span.state)
        assertNull(span.end)
        assertNull(span.durationMs)
    }

    @Test fun `result without start is explicitly partial`() {
        val span = buildTraceSpans(listOf(event(500, "tool.result", """{"tool_call_id":"t","elapsed_ms":200}""")), false).single()
        assertTrue(span.partial)
        assertEquals(300L, span.start)
        assertEquals(200L, span.durationMs)
    }

    @Test fun `tool error envelope differs from plain output containing error text`() {
        val error = event(100, "tool.result", """{"tool_call_id":"t","output":[{"type":"text","text":"{\"success\":false,\"error\":\"failed\"}"}]}""")
        val normal = event(200, "tool.result", """{"tool_call_id":"u","output":[{"type":"text","text":"0 errors"}]}""")
        assertEquals("error", error.summary.state)
        assertEquals("completed", normal.summary.state)
    }

    @Test fun `compaction updates collapse and preserve failure`() {
        val records = listOf(
            event(100, "compaction.event", """{"operation_id":"c","event":{"input":"{\"state\":\"running\",\"phase\":\"preparing\"}"}}"""),
            event(200, "compaction.event", """{"operation_id":"c","event":{"input":"{\"state\":\"failed\",\"elapsed_ms\":100}"}}"""),
        )
        val span = buildTraceSpans(records, false).single()
        assertFalse(span.partial)
        assertEquals("error", span.state)
        assertEquals(100L, span.durationMs)
    }

    @Test fun `subagent child navigation uses recorded identity`() {
        val span = buildTraceSpans(listOf(
            event(100, "subagent.started", """{"run_id":"agent","child_conversation":"child","task":"Research"}"""),
            event(200, "subagent.result", """{"run_id":"agent","status":"FAILED","error":"network"}"""),
        ), false).single()
        assertEquals("child", span.childConversation)
        assertEquals("error", span.state)
    }

    @Test fun `index summary does not duplicate prompt or tool output`() {
        val text = "private".repeat(100000)
        val summary = traceSummary("model.request", buildJsonObject {
            put("request_id", "r"); put("model", "test"); put("messages", text)
        })
        assertTrue(Json.encodeToString(TraceSummary.serializer(), summary).length < 500)
    }

    @Test fun `cancelled response is not a model failure`() {
        val record = event(100, "model.response", """{"request_id":"r","error_type":"JobCancellationException","stream_finished":false}""")
        assertEquals("cancelled", record.summary.state)
    }

    @Test fun `starting a new run does not revive historical unfinished requests`() {
        val spans = buildTraceSpans(listOf(
            event(100, "task.started", """{"run_id":"old"}"""),
            event(200, "model.request", """{"request_id":"lost"}"""),
            event(300, "task.started", """{"run_id":"new"}"""),
            event(400, "model.request", """{"request_id":"current"}"""),
        ), true).associateBy { it.id }
        assertEquals("incomplete", spans.getValue("model:lost").state)
        assertEquals("running", spans.getValue("model:current").state)
    }

    @Test fun `resumed task opens a new live segment without dropping its history`() {
        val span = buildTraceSpans(listOf(
            event(100, "task.started", """{"run_id":"task"}"""),
            event(200, "task.cancelled", """{"run_id":"task"}"""),
            event(300, "task.resumed", """{"run_id":"task"}"""),
        ), true).single()
        assertEquals("running", span.state)
        assertNull(span.end)
        assertEquals(3, span.entries.size)
    }

    @Test fun `persistence checkpoints do not double count tools`() {
        val spans = buildTraceSpans(listOf(
            event(100, "task.started", """{"run_id":"r"}"""),
            event(200, "tool.started", """{"tool_call_id":"t","tool":"cmd"}"""),
            event(300, "tool.result", """{"tool_call_id":"t","tool":"cmd"}"""),
            event(400, "tool.checkpoint", """{"tools":[]}"""),
        ), true)
        assertEquals(1, spans.count { it.kind == "tool" })
        assertEquals("running", spans.first { it.kind == "task" }.state)
        assertEquals(2, spans.first { it.kind == "task" }.entries.size)
    }
}
