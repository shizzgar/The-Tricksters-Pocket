package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart

/** Preserve the MCP outcome for agents and hooks without replacing the original content. */
internal fun withMcpToolStatus(parts: List<UIMessagePart>, isError: Boolean, errorCode: String = "mcp_tool_error"): List<UIMessagePart> {
    if (!isError) return parts
    val status = buildJsonObject { put("isError", true); put("error", errorCode) }
    return listOf(UIMessagePart.Text(status.toString())) + parts
}
