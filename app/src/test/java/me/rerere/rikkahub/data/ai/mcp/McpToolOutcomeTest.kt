package me.rerere.rikkahub.data.ai.mcp

import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.*
import org.junit.Test

class McpToolOutcomeTest {
    @Test fun `success retains original parts without invented success metadata`() {
        val parts = listOf(UIMessagePart.Text("unchanged"))
        assertSame(parts, withMcpToolStatus(parts, false))
    }
    @Test fun `error preserves protocol flag and original result`() {
        val parts = listOf(UIMessagePart.Text("Invalid option"), UIMessagePart.Text("Usage details"))
        val result = withMcpToolStatus(parts, true)
        val status = Json.parseToJsonElement((result.first() as UIMessagePart.Text).text).jsonObject
        assertTrue(status.getValue("isError").jsonPrimitive.boolean)
        assertEquals("mcp_tool_error", status.getValue("error").jsonPrimitive.content)
        assertEquals(parts, result.drop(1))
    }
}
