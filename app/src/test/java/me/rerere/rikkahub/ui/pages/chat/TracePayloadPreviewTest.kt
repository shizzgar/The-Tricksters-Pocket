package me.rerere.rikkahub.ui.pages.chat

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TracePayloadPreviewTest {
    @Test fun `tool names are identified in native and OpenAI declaration formats`() {
        val native = tracePayloadPreview(Json.parseToJsonElement("""{"name":"scrape_web","description":"Read a page","parameters":{"type":"object"}}"""))
        val wrapped = tracePayloadPreview(Json.parseToJsonElement("""{"type":"function","function":{"name":"scrape_web","description":"Read a page"}}"""))
        assertEquals("scrape_web", native.title)
        assertEquals("Read a page", native.detail)
        assertEquals(native, wrapped)
    }
    @Test fun `message preview includes role and content from recorded parts`() {
        val message = tracePayloadPreview(Json.parseToJsonElement("""{"role":"assistant","parts":[{"type":"text","text":"First line\nSecond line"}]}"""))
        assertEquals("assistant", message.title)
        assertEquals("First line Second line", message.detail)
        val reasoning = tracePayloadPreview(Json.parseToJsonElement("""{"type":"reasoning","reasoning":"Check the files"}"""))
        assertEquals("reasoning", reasoning.title)
        assertEquals("Check the files", reasoning.detail)
    }
    @Test fun `schema previews expose parameter names and scalar metadata`() {
        val schema = tracePayloadPreview(Json.parseToJsonElement("""{"type":"object","properties":{"url":{"type":"string"},"timeout":{"type":"integer"}}}"""))
        assertEquals("url · timeout", schema.detail)
        val unknown = tracePayloadPreview(Json.parseToJsonElement("""{"exit_code":0,"complete":true,"output":{"large":"payload"}}"""))
        assertEquals("exit_code: 0 · complete: true · output", unknown.detail)
    }
    @Test fun `huge payloads and collections produce bounded previews without serializing them`() {
        val text = tracePayloadPreview(buildJsonObject { put("description", "x".repeat(100_000)) })
        assertEquals(241, text.detail.length)
        val array = tracePayloadPreview(buildJsonArray { repeat(1000) { add(buildJsonObject { put("name", "tool_$it"); put("description", "y".repeat(500)) }) } })
        assertEquals("tool_0 · tool_1 · tool_2 · tool_3 …", array.detail)
        assertEquals("", tracePayloadPreview(JsonArray(emptyList())).detail)
    }
    @Test fun `tool search examines the full description beyond the displayed excerpt`() {
        val value = buildJsonObject { put("function", buildJsonObject {
            put("name", "long_tool"); put("description", "x".repeat(1000) + "needle")
        }) }
        assertFalse(tracePayloadPreview(value).detail.contains("needle"))
        assertTrue(traceToolMatches(value, "NEEDLE"))
        assertTrue(traceToolMatches(value, "long_tool"))
        assertFalse(traceToolMatches(value, "missing"))
    }
}
