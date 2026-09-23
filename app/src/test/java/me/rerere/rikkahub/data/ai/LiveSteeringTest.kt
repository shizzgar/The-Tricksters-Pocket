package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import org.junit.Assert.*
import org.junit.Test

class LiveSteeringTest {
    private fun tool(id: String) = UIMessagePart.Tool(toolCallId = id, toolName = "fixture_tool", input = "{}")
    private fun assistant(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

    @Test fun `finished tool retains exact output while unstarted batch remainder is closed`() {
        val done = tool("done").copy(executionStartedAt = 1L, output = listOf(UIMessagePart.Text("original stdout")))
        val next = tool("next").copy(approvalState = ToolApprovalState.Approved)
        val reasoning = UIMessagePart.Reasoning(reasoning = "provider reasoning")
        val messages = listOf(assistant(reasoning, done, next))
        assertTrue(canSteerAtBoundary(messages))
        val result = supersedeUnstartedTools(messages).single()
        assertEquals(reasoning, result.parts.first())
        assertEquals(done, result.getTools().first())
        val skipped = result.getTools().last()
        assertEquals("next", skipped.toolCallId)
        assertTrue(skipped.isExecuted)
        assertNull(skipped.executionStartedAt)
        assertTrue(skipped.approvalState is ToolApprovalState.Denied)
        assertTrue(skipped.output.toString().contains("superseded_by_user_input"))
        assertEquals(result, supersedeUnstartedTools(listOf(result)).single())
    }

    @Test fun `running and approval pending tools block input without approving or cancelling`() {
        val pending = listOf(assistant(tool("pending").copy(approvalState = ToolApprovalState.Pending)))
        val running = listOf(assistant(tool("running").copy(executionStartedAt = 1L)))
        for (messages in listOf(pending, running)) {
            assertFalse(canSteerAtBoundary(messages))
            assertThrows(IllegalStateException::class.java) { supersedeUnstartedTools(messages) }
            assertFalse(messages.single().getTools().single().isExecuted)
        }
    }

    @Test fun `partial batch checkpoint retains previous tools and stable message identity`() {
        val first = tool("first")
        val second = tool("second")
        val messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("task"))), assistant(first, second))
        val done = first.copy(executionStartedAt = 100, output = listOf(UIMessagePart.Text("done")))
        val changed = applyCompletedTools(messages, listOf(done))
        assertEquals(messages.first(), changed.first())
        assertEquals(messages.last().id, changed.last().id)
        assertEquals(listOf(done, second), changed.last().getTools())
        assertEquals(listOf(first, second), messages.last().getTools())
    }

    @Test fun `text and reasoning responses need no tool placeholders`() {
        val messages = listOf(assistant(UIMessagePart.Reasoning(reasoning = "done thinking"), UIMessagePart.Text("response")))
        assertTrue(canSteerAtBoundary(messages))
        assertEquals(messages, supersedeUnstartedTools(messages))
    }
}
