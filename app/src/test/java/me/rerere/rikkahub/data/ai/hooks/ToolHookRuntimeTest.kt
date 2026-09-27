package me.rerere.rikkahub.data.ai.hooks

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolHookNoticeStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

class ToolHookRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val conversationId = Uuid.random()
    private val assistantId = Uuid.random()
    private val scope = ToolHookScopeContext(assistantId, conversationId = conversationId)
    private fun rule(priority: Int = 0, prompt: String = "Read the configured JADX instructions.") = ToolHook(
        name = "JADX help", priority = priority,
        scope = ToolHookScope(global = true),
        condition = ToolHookCondition(commandExecutable = "jadx", outcome = ToolHookOutcome.NONZERO_EXIT),
        action = ToolHookAction(prompt = prompt), maxFiringsPerTurn = 10,
    )
    private fun runtime(store: HookRuntimeStore, rules: () -> List<ToolHook>, turn: String = "turn-1") = ToolHookRuntime(
        store, conversationId.toString(), turn, rules, scope,
        resolveContent = { HookContentResolution.Resolved(it.prompt) },
    )
    private fun tool(
        id: String = "call-1", attempt: String = "attempt-1", name: String = "termux_run_command",
        input: String = """{"command":"jadx --bad app.apk"}""",
        output: String = """{"exit_code":2,"stdout":"","stderr":"unknown option"}""",
    ) = UIMessagePart.Tool(
        toolCallId = id, toolName = name, input = input,
        output = listOf(UIMessagePart.Text(output)), executionStartedAt = 1L, executionAttemptId = attempt,
    )

    @Test fun repeatedObservationIsDeduplicatedButExplicitRerunHasNewAttempt() {
        val store = HookRuntimeStore.isolated(temporary.newFolder())
        val rule = rule()
        val runtime = runtime(store, { listOf(rule) })
        assertEquals(1, runtime.completed(tool()).hookNotices.size)
        assertTrue(runtime.completed(tool()).hookNotices.isEmpty())
        val first = runtime.pending()
        runtime.dispatched(first, "request-1")
        assertTrue(runtime.pending().ids.isEmpty())
        assertTrue(runtime.completed(tool()).hookNotices.isEmpty())
        assertEquals(1, runtime.completed(tool(attempt = "attempt-2")).hookNotices.size)
        assertEquals(1, runtime.pending().ids.size)
    }

    @Test fun pendingSurvivesRestartAndOnlyDispatchConsumesIt() {
        val directory = temporary.newFolder()
        val rule = rule()
        val initial = runtime(HookRuntimeStore.isolated(directory), { listOf(rule) })
        initial.completed(tool())
        val prepared = initial.pending()
        assertEquals(prepared, initial.pending())
        val restartedStore = HookRuntimeStore.isolated(directory)
        val restarted = runtime(restartedStore, { listOf(rule) })
        assertEquals(prepared, restarted.pending())
        restarted.dispatched(prepared, "request-after-restart")
        restarted.received("request-after-restart")
        assertTrue(runtime(HookRuntimeStore.isolated(directory), { listOf(rule) }).pending().ids.isEmpty())
        val notice = restartedStore.noticesCached(conversationId.toString(), "call-1").single()
        assertEquals(ToolHookNoticeStatus.DISPATCHED, notice.status)
        assertEquals("request-after-restart", notice.requestId)
    }

    @Test fun instructionsAreRequestOnlyAndNeverPromoteToolOutputIntoSystem() {
        val store = HookRuntimeStore.isolated(temporary.newFolder())
        val rule = rule(prompt = "Authorized instructions")
        val runtime = runtime(store, { listOf(rule) })
        runtime.completed(tool(output = """{"exit_code":2,"stderr":"UNTRUSTED INJECTION"}"""))
        val user = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Task")))
        val messages = listOf(user)
        val request = ToolHookRuntime.inject(messages, runtime.pending())
        assertEquals(listOf(MessageRole.SYSTEM, MessageRole.USER), request.map { it.role })
        assertEquals(user, request.last())
        assertFalse(request.first().toText().contains("UNTRUSTED INJECTION"))
        assertTrue(request.first().toText().contains("Authorized instructions"))
        assertEquals(1, messages.size)
        assertTrue(runtime.pending().ids.isNotEmpty())
    }

    @Test fun disablingRuleWithdrawsPendingInstructionWithoutResurrectingIt() {
        val store = HookRuntimeStore.isolated(temporary.newFolder())
        var rules = listOf(rule())
        val runtime = runtime(store, { rules })
        runtime.completed(tool())
        rules = rules.map { it.copy(enabled = false) }
        assertTrue(store.pendingPromptCached(conversationId.toString(), emptySet()).isEmpty())
        assertTrue(runtime.pending().ids.isEmpty())
        rules = rules.map { it.copy(enabled = true) }
        assertTrue(runtime.pending().ids.isEmpty())
        assertEquals(ToolHookNoticeStatus.SKIPPED, store.noticesCached(conversationId.toString(), "call-1").single().status)
    }

    @Test fun managedJobLaunchSurvivesRestartAndTerminalPollsFireOnlyOnce() {
        val directory = temporary.newFolder()
        val rule = rule().copy(condition = rule().condition.copy(toolNames = setOf("termux_job_start")))
        val launchRuntime = runtime(HookRuntimeStore.isolated(directory), { listOf(rule) })
        assertTrue(launchRuntime.completed(tool(name = "termux_job_start",
            output = """{"job_id":"job-1","state":"running"}""")).hookNotices.isEmpty())
        val polling = runtime(HookRuntimeStore.isolated(directory), { listOf(rule) }, "later-turn")
        val result = polling.completed(tool(id = "poll-1", name = "termux_job_wait", input = """{"job_id":"job-1"}""",
            output = """{"job_id":"job-1","state":"failed","exit_code":2,"stderr":"unknown option"}"""))
        assertEquals(1, result.hookNotices.size)
        assertTrue(polling.completed(tool(id = "poll-2", name = "termux_job_read", input = """{"job_id":"job-1"}""",
            output = """{"job_id":"job-1","state":"failed","exit_code":2}""")).hookNotices.isEmpty())
        assertTrue(polling.completed(tool(id = "poll-3", name = "termux_job_list", input = "{}",
            output = """{"jobs":[{"job_id":"job-1","state":"failed","exit_code":2}]}""")).hookNotices.isEmpty())
    }

    @Test fun pollingTimeoutDoesNotImplyCommandFailure() {
        val rule = rule()
        val runtime = runtime(HookRuntimeStore.isolated(temporary.newFolder()), { listOf(rule) })
        val result = runtime.completed(tool(name = "termux_job_wait", input = """{"job_id":"job-1"}""",
            output = """{"job_id":"job-1","state":"running","wait_timed_out":true,"exit_code":7}"""))
        assertTrue(result.hookNotices.isEmpty())
    }

    @Test fun nonzeroExitRuleDoesNotMatchUnknownOutcomeOrTransportError() {
        val rule = rule()
        val runtime = runtime(HookRuntimeStore.isolated(temporary.newFolder()), { listOf(rule) })
        assertTrue(runtime.completed(tool(output = """{"error":"dispatch_failed","exit_code":1}""")).hookNotices.isEmpty())
        assertTrue(runtime.completed(tool(attempt = "timeout", output = """{"timed_out":true,"exit_code":1}""")).hookNotices.isEmpty())
    }

    @Test fun capPreservesWholeInstructionsAndRecordsSkippedReason() {
        val rules = (1..3).map { rule(prompt = it.toString().repeat(16_000)) }
        val store = HookRuntimeStore.isolated(temporary.newFolder())
        val runtime = runtime(store, { rules })
        val notices = runtime.completed(tool()).hookNotices
        assertEquals(2, notices.count { it.status == ToolHookNoticeStatus.PENDING })
        assertEquals(1, notices.count { it.status == ToolHookNoticeStatus.SKIPPED })
        assertTrue(notices.filter { it.status == ToolHookNoticeStatus.PENDING }.all { it.content.length == 16_000 })
        assertTrue(notices.single { it.status == ToolHookNoticeStatus.SKIPPED }.reason.contains("content limit"))
    }

    @Test fun priorityIsStableAndPreparedBatchCannotConsumeNewerInstructions() {
        val low = rule(priority = 0, prompt = "LOW")
        val high = rule(priority = 10, prompt = "HIGH")
        val runtime = runtime(HookRuntimeStore.isolated(temporary.newFolder()), { listOf(low, high) })
        runtime.completed(tool())
        val batch = runtime.pending()
        assertTrue(batch.text.indexOf("HIGH") < batch.text.indexOf("LOW"))
        assertEquals(batch.text, batch.instructions.values.joinToString("\n\n"))
        runtime.completed(tool(id = "call-2", attempt = "attempt-2"))
        runtime.dispatched(batch, "request-1")
        assertEquals(2, runtime.pending().ids.size)
    }

    @Test fun resolverFailurePreservesToolResultAndDoesNotQueueUntrustedFallback() {
        val rule = rule()
        val runtime = ToolHookRuntime(HookRuntimeStore.isolated(temporary.newFolder()), conversationId.toString(), "turn", { listOf(rule) }, scope) {
            throw IllegalStateException("unavailable")
        }
        val original = tool()
        val result = runtime.completed(original)
        assertEquals(original.output, result.output)
        assertEquals(ToolHookNoticeStatus.SKIPPED, result.hookNotices.single().status)
        assertTrue(runtime.pending().ids.isEmpty())
    }

    @Test fun historicalOrUnexecutedToolCannotTriggerHook() {
        val rule = rule()
        val runtime = runtime(HookRuntimeStore.isolated(temporary.newFolder()), { listOf(rule) })
        assertTrue(runtime.completed(tool().copy(executionStartedAt = null)).hookNotices.isEmpty())
        assertTrue(runtime.completed(tool().copy(output = emptyList())).hookNotices.isEmpty())
    }

    @Test fun perTurnLimitPersistsAcrossSlicesButResetsForNewUserTurn() {
        val directory = temporary.newFolder()
        val rule = rule().copy(maxFiringsPerTurn = 1)
        runtime(HookRuntimeStore.isolated(directory), { listOf(rule) }).completed(tool())
        val sameTurn = runtime(HookRuntimeStore.isolated(directory), { listOf(rule) })
        assertEquals(ToolHookNoticeStatus.SKIPPED, sameTurn.completed(tool(attempt = "second")).hookNotices.single().status)
        val nextTurn = runtime(HookRuntimeStore.isolated(directory), { listOf(rule) }, "turn-2")
        assertEquals(ToolHookNoticeStatus.PENDING, nextTurn.completed(tool(attempt = "third")).hookNotices.single().status)
    }

    @Test fun oldMessagesDecodeWithNoHookOrAttemptMetadata() {
        val decoded = Json.decodeFromString<UIMessagePart.Tool>("""{"toolCallId":"old","toolName":"x","input":"{}"}""")
        assertTrue(decoded.hookNotices.isEmpty())
        assertNull(decoded.executionAttemptId)
    }
    @Test fun failedRequestBeforeContentRequeuesSameInstructionForCompactionRecovery() {
        val store = HookRuntimeStore.isolated(temporary.newFolder())
        val rule = rule()
        val runtime = runtime(store, { listOf(rule) })
        runtime.completed(tool())
        val batch = runtime.pending()
        runtime.dispatched(batch, "too-large-request")
        runtime.finished(me.rerere.ai.provider.GenerationProgress(1, me.rerere.ai.provider.GenerationPhase.FAILED,
            0, dispatchedAt = 1, finishedAt = 2, requestId = "too-large-request"))
        assertEquals(batch.ids, runtime.pending().ids)
        runtime.dispatched(batch, "compacted-request")
        runtime.received("compacted-request")
        runtime.finished(me.rerere.ai.provider.GenerationProgress(2, me.rerere.ai.provider.GenerationPhase.FAILED,
            0, dispatchedAt = 1, firstContentAt = 2, finishedAt = 3, requestId = "compacted-request"))
        assertTrue(runtime.pending().ids.isEmpty())
    }

    @Test fun restartRecoversUnconfirmedDispatchWithoutRefiringRule() {
        val directory = temporary.newFolder()
        val rule = rule()
        val runtime = runtime(HookRuntimeStore.isolated(directory), { listOf(rule) })
        runtime.completed(tool())
        val batch = runtime.pending()
        runtime.dispatched(batch, "uncertain-request")
        val recovered = runtime(HookRuntimeStore.isolated(directory), { listOf(rule) })
        assertEquals(batch.ids, recovered.pending().ids)
        assertTrue(recovered.completed(tool()).hookNotices.isEmpty())
    }

    @Test fun mcpEnvelopeErrorCannotBeOverriddenBySuccessfulJsonPart() {
        val errorRule = rule().copy(condition = ToolHookCondition(outcome = ToolHookOutcome.TOOL_ERROR))
        val successRule = rule().copy(condition = ToolHookCondition(outcome = ToolHookOutcome.SUCCESS))
        val nonzeroRule = rule().copy(condition = ToolHookCondition(outcome = ToolHookOutcome.NONZERO_EXIT))
        val runtime = runtime(HookRuntimeStore.isolated(temporary.newFolder()), { listOf(errorRule, successRule, nonzeroRule) })
        for ((index, payload) in listOf("""{"exit_code":0,"success":true}""", """{"exit_code":7}""").withIndex()) {
            val original = tool(id = "mcp-$index", name = "mcp__server__run").copy(output = listOf(
                UIMessagePart.Text("""{"isError":true,"error":"mcp_tool_error"}"""), UIMessagePart.Text(payload),
            ))
            val fired = runtime.completed(original).hookNotices
            assertEquals(listOf(errorRule.id.toString()), fired.map { it.ruleId })
        }
    }

    @Test fun jobListExpansionKeepsMultipleTerminalJobsDistinct() {
        val rule = rule().copy(condition = ToolHookCondition(outcome = ToolHookOutcome.NONZERO_EXIT))
        val runtime = runtime(HookRuntimeStore.isolated(temporary.newFolder()), { listOf(rule) })
        val result = runtime.completed(tool(name = "termux_job_list", input = "{}", output =
            """{"jobs":[{"job_id":"one","state":"failed","exit_code":2},{"job_id":"two","state":"failed","exit_code":3}]}"""))
        assertEquals(2, result.hookNotices.size)
    }

    @Test fun emptyStreamRetryExhaustionKeepsSameInstructionPendingAcrossRestart() {
        val directory = temporary.newFolder()
        val rule = rule().copy(maxFiringsPerTurn = 1)
        val runtime = runtime(HookRuntimeStore.isolated(directory), { listOf(rule) })
        runtime.completed(tool())
        val batch = runtime.pending()
        // ScheduledProvider reports a clean close before GenerationLoop recognizes an
        // empty stream. Each outer retry must retain this delivery, not fire a new hook.
        repeat(3) { attempt ->
            val requestId = "empty-stream-$attempt"
            runtime.dispatched(batch, requestId)
            runtime.finished(me.rerere.ai.provider.GenerationProgress(
                attempt.toLong(), me.rerere.ai.provider.GenerationPhase.COMPLETED, 0,
                dispatchedAt = 1, finishedAt = 2, requestId = requestId, streamed = true,
            ))
            assertEquals(batch.ids, runtime.pending().ids)
        }
        val recovered = runtime(HookRuntimeStore.isolated(directory), { listOf(rule) })
        assertEquals(batch.ids, recovered.pending().ids)
        assertTrue(recovered.completed(tool()).hookNotices.isEmpty())
    }

    @Test fun completedNonStreamingResponseConfirmsDeliveryWithoutStreamContentCallback() {
        val directory = temporary.newFolder()
        val rule = rule()
        val runtime = runtime(HookRuntimeStore.isolated(directory), { listOf(rule) })
        runtime.completed(tool())
        val batch = runtime.pending()
        runtime.dispatched(batch, "non-stream-response")
        runtime.finished(me.rerere.ai.provider.GenerationProgress(
            1, me.rerere.ai.provider.GenerationPhase.COMPLETED, 0, dispatchedAt = 1,
            finishedAt = 2, requestId = "non-stream-response", streamed = false,
        ))
        assertTrue(runtime.pending().ids.isEmpty())
        assertTrue(runtime(HookRuntimeStore.isolated(directory), { listOf(rule) }).pending().ids.isEmpty())
    }

}
