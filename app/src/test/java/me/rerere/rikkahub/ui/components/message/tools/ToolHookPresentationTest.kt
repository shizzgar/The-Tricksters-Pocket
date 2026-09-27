package me.rerere.rikkahub.ui.components.message.tools

import me.rerere.ai.ui.ToolHookNotice
import me.rerere.ai.ui.ToolHookNoticeStatus
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.hooks.ToolHookMatcher
import me.rerere.rikkahub.data.ai.hooks.ToolHookScopeContext
import me.rerere.rikkahub.data.model.ToolHook
import me.rerere.rikkahub.data.model.ToolHookAction
import me.rerere.rikkahub.data.model.ToolHookCondition
import me.rerere.rikkahub.data.model.ToolHookScope
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ToolHookPresentationTest {
    private val scope = ToolHookScopeContext(Uuid.random(), conversationId = Uuid.random())
    private val rule = ToolHook(name = "JADX help", scope = ToolHookScope(global = true),
        condition = ToolHookCondition(toolNames = setOf("termux_job_start"), commandExecutable = "jadx", stderrContains = "unknown option"),
        action = ToolHookAction(prompt = "Read jadx --help before retrying."))
    private fun tool(id: String, name: String, args: String, result: String) = UIMessagePart.Tool(
        id, name, args, output = listOf(UIMessagePart.Text(result)), executionStartedAt = 1L,
    )
    private fun start(command: String = "jadx --bad sample.apk") = tool("start", "termux_job_start",
        """{"command":"$command"}""", """{"job_id":"job-1","state":"running"}""")
    private fun terminal(exit: Int = 2) = tool("wait", "termux_job_wait", """{"job_id":"job-1"}""",
        """{"job_id":"job-1","state":"completed","exit_code":$exit,"stderr":"unknown option --bad"}""")

    @Test fun `current dispatch status replaces queued message snapshot without duplicate notice`() {
        val pending = ToolHookNotice("notice", "rule", "JADX help", "Exit 2", "# Guide")
        val dispatched = pending.copy(status = ToolHookNoticeStatus.DISPATCHED, requestId = "request-2")
        assertEquals(listOf(dispatched), mergeToolHookNotices(listOf(pending), listOf(dispatched)))
        assertEquals(listOf(pending), mergeToolHookNotices(listOf(pending), emptyList()))
    }

    @Test fun `restored pending snapshots do not claim a missing queue is active`() {
        val pending = ToolHookNotice("restored", "rule", "JADX help", "Original reason", "Exact original prompt")
        assertTrue(toolHookNoticeState(listOf(pending), emptyList(), loaded = false).unavailablePendingIds.isEmpty())
        val restored = toolHookNoticeState(listOf(pending), emptyList(), loaded = true)
        assertEquals(setOf("restored"), restored.unavailablePendingIds)
        assertEquals(listOf(pending), restored.notices)
        assertTrue(toolHookNoticeState(listOf(pending), listOf(pending), loaded = true).unavailablePendingIds.isEmpty())
    }

    @Test fun `independent notices retain order and exact instruction snapshots`() {
        val first = ToolHookNotice("a", "rule-a", "A", "A matched", "```sh\njadx --help\n```", "JADX/SKILL.md")
        val second = ToolHookNotice("b", "rule-b", "B", "B matched", "second")
        assertEquals(listOf(first, second), mergeToolHookNotices(listOf(first), listOf(second)))
    }

    @Test fun `dry run correlates terminal poll with original command without changing stored call`() {
        val history = listOf(start(), terminal())
        val calls = toolHookPastCalls(history)
        assertFalse(ToolHookMatcher.evaluate(rule, calls.first().event, scope).matched)
        assertTrue(ToolHookMatcher.evaluate(rule, calls.last().event, scope).matched)
        assertEquals("termux_job_start", calls.last().event.originToolName)
        assertEquals("jadx --bad sample.apk", calls.last().event.command)
        assertTrue(history.all { it.hookNotices.isEmpty() })
        assertEquals("""{"job_id":"job-1"}""", history.last().input)
    }

    @Test fun `successful exit and a different command do not match the JADX error rule`() {
        assertFalse(ToolHookMatcher.evaluate(rule, toolHookPastCalls(listOf(start(), terminal(0))).last().event, scope).matched)
        assertFalse(ToolHookMatcher.evaluate(rule, toolHookPastCalls(listOf(start("echo jadx"), terminal())).last().event, scope).matched)
    }

    @Test fun `a later launch cannot supply origin for an earlier observation`() {
        val calls = toolHookPastCalls(listOf(terminal(), start()))
        assertNull(calls.first().event.originToolName)
        assertFalse(ToolHookMatcher.evaluate(rule, calls.first().event, scope).matched)
    }

    @Test fun `job lists and multiple text outputs reuse runtime observation parsing`() {
        val list = tool("list", "termux_job_list", "{}", """{"jobs":[
            {"job_id":"job-1","state":"completed","exit_code":2,"stderr":"unknown option"},
            {"job_id":"job-2","state":"running"}]}""")
        val calls = toolHookPastCalls(listOf(start(), list))
        assertEquals(3, calls.size)
        assertTrue(ToolHookMatcher.evaluate(rule, calls[1].event, scope).matched)
        assertFalse(ToolHookMatcher.evaluate(rule, calls[2].event, scope).matched)
        assertNotEquals(calls[1].key, calls[2].key)
    }

    @Test fun `workspace background observations retain their original executable`() {
        val start = tool("workspace-start", "workspace_run_background", """{"command":"jadx --bad sample.apk"}""",
            """{"id":"process-1","status":"running"}""")
        val status = tool("workspace-status", "workspace_background_status", "{}",
            """{"processes":[{"id":"process-1","status":"exited","exitCode":2,"stderr":"unknown option"}]}""")
        val workspaceRule = rule.copy(condition = rule.condition.copy(toolNames = setOf("workspace_run_background")))
        val event = toolHookPastCalls(listOf(start, status)).last().event
        assertEquals("workspace_run_background", event.originToolName)
        assertTrue(ToolHookMatcher.evaluate(workspaceRule, event, scope).matched)
    }

    @Test fun `queued and unstarted synthetic tool results are excluded from preview`() {
        val pending = start().copy(output = emptyList())
        val unstarted = terminal().copy(executionStartedAt = null)
        assertTrue(toolHookPastCalls(listOf(pending, unstarted)).isEmpty())
    }
}
