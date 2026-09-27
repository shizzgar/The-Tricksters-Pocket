package me.rerere.rikkahub.data.ai

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.GenerationRequestMetrics
import me.rerere.ai.provider.GenerationRequestContext
import me.rerere.ai.provider.GenerationProgress
import me.rerere.ai.provider.GenerationPhase
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.AutoCompactionThresholdMode
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationCompaction
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.time.toKotlinInstant

class ContextUsageSnapshotTest {
    private val model = Model(modelId = "test", contextLength = 1000)
    private val assistant = Assistant(maxTokens = 100)
    private val settings = Settings(enableAutoCompaction = true, autoCompactionThresholdPercent = 80)
    private fun chat(vararg messages: UIMessage) = Conversation.ofId(id = kotlin.uuid.Uuid.random(), assistantId = assistant.id, messages = messages.map { it.toMessageNode() })
    private fun response(prompt: Int, completion: Int = 20) = UIMessage.assistant("done").copy(modelId = model.id,
        usage = TokenUsage(promptTokens = prompt, completionTokens = completion, totalTokens = prompt + completion))

    @Test fun `latest request input is used instead of accumulated chat usage`() {
        val snapshot = ContextUsageCalculator.snapshot(chat(response(500), response(600)), assistant, settings, model)
        assertEquals(600, snapshot.latestPromptTokens)
        assertEquals(620, snapshot.usedTokens)
        assertEquals(62, snapshot.percent)
        assertEquals(280, snapshot.availableInput)
        assertTrue(snapshot.providerAnchored)
    }

    @Test fun `latest individual request beats aggregate message usage and ignores reported total`() {
        val message = response(900).copy(generationMetrics = listOf(
            GenerationRequestMetrics("first", "COMPLETED", 0, 0, usage = TokenUsage(500, 10, totalTokens = 510)),
            GenerationRequestMetrics("last", "COMPLETED", 0, 0, usage = TokenUsage(100, 20, totalTokens = 9999)),
        ))
        val snapshot = ContextUsageCalculator.snapshot(chat(message), assistant, settings, model)
        assertEquals(100, snapshot.latestPromptTokens)
        assertEquals(120, snapshot.usedTokens)
    }

    @Test fun `unknown capacity is explicit and never presents a zero percent ring`() {
        val snapshot = ContextUsageCalculator.snapshot(chat(response(200)), assistant, settings, model.copy(contextLength = null))
        assertNull(snapshot.contextLimit)
        assertNull(snapshot.fraction)
        assertNull(snapshot.percent)
        assertNull(snapshot.compactionTrigger)
        assertEquals(ContextUsageLevel.UNKNOWN, snapshot.level)
    }

    @Test fun `manual compaction threshold does not replace known model capacity`() {
        val snapshot = ContextUsageCalculator.snapshot(chat(response(100)), assistant,
            settings.copy(autoCompactionThresholdMode = AutoCompactionThresholdMode.TOKENS, autoCompactionThresholdTokensK = 8), model)
        assertEquals(1000, snapshot.contextLimit)
        assertEquals(8000, snapshot.configuredCompactionTrigger)
        assertEquals(900, snapshot.compactionTrigger)
        assertEquals(100, snapshot.outputReserve)
        assertFalse(snapshot.userLimit)
    }

    @Test fun `threshold and reserve boundaries are distinct and gauge is clamped`() {
        val base = ContextUsageCalculator.snapshot(chat(response(100)), assistant, settings, model)
        assertEquals(ContextUsageLevel.NORMAL, base.copy(usedTokens = 679).level)
        assertEquals(ContextUsageLevel.NEAR_THRESHOLD, base.copy(usedTokens = 680).level)
        assertEquals(ContextUsageLevel.THRESHOLD_REACHED, base.copy(usedTokens = 800).level)
        assertEquals(ContextUsageLevel.FULL, base.copy(usedTokens = 900).level)
        assertEquals(1f, base.copy(usedTokens = Int.MAX_VALUE).fraction)
        assertEquals(0, base.copy(usedTokens = Int.MAX_VALUE).availableInput)
        assertEquals(ContextUsageLevel.NORMAL, base.copy(usedTokens = 700, autoCompactionEnabled = false).level)
    }

    @Test fun `compaction drops old measurements including usage on the retained tail`() {
        val old = response(900).copy(finishedAt = Instant.ofEpochMilli(1000).toKotlinInstant().toLocalDateTime(TimeZone.currentSystemDefault()))
        val tail = response(950).copy(finishedAt = old.finishedAt)
        val conversation = chat(old, tail)
        val compaction = ConversationCompaction(conversation.id, "Summary", conversation.messageNodes[1].id,
            conversation.messageNodes[0].id, model.id, true, 950, Instant.ofEpochMilli(2000))
        val snapshot = ContextUsageCalculator.snapshot(conversation, assistant, settings, model, compaction)
        assertTrue(snapshot.compacted)
        assertNull(snapshot.latestPromptTokens)
        assertFalse(snapshot.providerAnchored)
        assertTrue(snapshot.usedTokens < 100)
        val liveStale = ContextUsageCalculator.snapshot(conversation, assistant, settings, model, compaction,
            true, TokenUsage(promptTokens = 950), Instant.ofEpochMilli(1000))
        assertNull(liveStale.latestPromptTokens)
        val liveNew = ContextUsageCalculator.snapshot(conversation, assistant, settings, model, compaction,
            true, TokenUsage(promptTokens = 60), Instant.ofEpochMilli(3000))
        assertEquals(60, liveNew.latestPromptTokens)
        assertTrue(liveNew.streaming)
    }

    @Test fun `changing model or trimming history invalidates the old provider anchor`() {
        val conversation = chat(response(800), response(850), response(900))
        val changed = ContextUsageCalculator.snapshot(conversation, assistant, settings, Model(contextLength = 2000))
        assertNull(changed.latestPromptTokens)
        assertEquals(2000, changed.contextLimit)
        assertTrue(changed.usedTokens < 100)
        val cropped = ContextUsageCalculator.snapshot(conversation, assistant.copy(contextMessageLimit = 1), settings, model)
        assertNull(cropped.latestPromptTokens)
        assertTrue(cropped.usedTokens < 100)
    }

    @Test fun `manual summaries remain active when automatic compaction is disabled`() {
        val conversation = chat(response(900))
        val compaction = ConversationCompaction(conversation.id, "Summary", null,
            conversation.messageNodes[0].id, model.id, false, 950, Instant.ofEpochMilli(2000))
        val snapshot = ContextUsageCalculator.snapshot(conversation, assistant, settings.copy(enableAutoCompaction = false), model, compaction)
        assertTrue(snapshot.compacted)
        assertNull(snapshot.compactionTrigger)
        assertFalse(snapshot.autoCompactionEnabled)
    }
    private fun requestContext(conversation: Conversation, prefix: UIMessage? = null,
        included: List<UIMessage> = conversation.currentMessages, estimate: Int = 5000): GenerationRequestContext =
        GenerationRequestContext(model.id.toString(), 3000, estimate,
            contextPrefixMessageId = included.firstOrNull()?.id?.toString(),
            responseMessageId = prefix?.id?.toString(),
            responsePrefixTokens = prefix?.let(ContextBudgetPlanner::estimateMessageTokens) ?: 0,
            includedToolCallIds = prefix?.parts?.filterIsInstance<UIMessagePart.Tool>()
                ?.filter { it.isExecuted }?.map { it.toolCallId }?.toSet().orEmpty())

    @Test fun `manual budget is marked as user supplied only without model metadata`() {
        val snapshot = ContextUsageCalculator.snapshot(chat(response(100)), assistant,
            settings.copy(autoCompactionThresholdMode = AutoCompactionThresholdMode.TOKENS, autoCompactionThresholdTokensK = 8),
            model.copy(contextLength = null))
        assertEquals(8000, snapshot.contextLimit)
        assertEquals(8000, snapshot.configuredCompactionTrigger)
        assertEquals(7900, snapshot.compactionTrigger)
        assertTrue(snapshot.userLimit)
    }

    @Test fun `runtime and gauge share output reserve adjusted threshold`() {
        val configured = settings.copy(autoCompactionThresholdPercent = 95)
        val budget = ContextUsageCalculator.budget(assistant, configured, model)
        val snapshot = ContextUsageCalculator.snapshot(chat(response(880)), assistant, configured, model)
        assertEquals(950, snapshot.configuredCompactionTrigger)
        assertEquals(900, snapshot.compactionTrigger)
        assertEquals(budget.compactionTrigger, snapshot.compactionTrigger)
        assertEquals(ContextUsageLevel.FULL, snapshot.level)
    }

    @Test fun `streaming continuation adds only current request response to its input`() {
        val prefix = response(4500).copy(parts = listOf(UIMessagePart.Text("x".repeat(3000)),
            UIMessagePart.Tool("old", "read", "{}", output = listOf(UIMessagePart.Text("x".repeat(6000))))))
        val initial = chat(UIMessage.user("task"), prefix)
        val request = requestContext(initial, prefix)
        val streamingMessage = prefix.copy(parts = prefix.parts + UIMessagePart.Text("x".repeat(300)))
        val current = initial.copy(messageNodes = listOf(initial.messageNodes.first(), streamingMessage.toMessageNode()))
        val snapshot = ContextUsageCalculator.snapshot(current, assistant, settings, model, streaming = true,
            liveUsage = TokenUsage(5000, 20), liveContext = request)
        assertEquals(5100, snapshot.usedTokens)
        assertEquals(5000, snapshot.latestPromptTokens)
    }

    @Test fun `tool checkpoint and completed live progress use identical accounting`() {
        val prefix = response(4500).copy(parts = listOf(
            UIMessagePart.Tool("old", "read", "{}", output = listOf(UIMessagePart.Text("x".repeat(6000))))))
        val initial = chat(UIMessage.user("task"), prefix)
        val request = requestContext(initial, prefix)
        val usage = TokenUsage(5000, 20, totalTokens = 99999)
        val completed = prefix.copy(parts = prefix.parts + UIMessagePart.Tool("new", "read", "{}",
            output = listOf(UIMessagePart.Text("x".repeat(900)))), generationMetrics = listOf(
            GenerationRequestMetrics("round2", "COMPLETED", 10, 0, usage = usage, context = request)))
        val current = initial.copy(messageNodes = listOf(initial.messageNodes.first(), completed.toMessageNode()))
        val saved = ContextUsageCalculator.snapshot(current, assistant, settings, model)
        val live = ContextUsageCalculator.snapshot(current, assistant, settings, model, streaming = true,
            liveUsage = usage, liveContext = request, liveFinished = true)
        assertEquals(5320, saved.usedTokens)
        assertEquals(saved.usedTokens, live.usedTokens)
        assertEquals(saved.latestPromptTokens, live.latestPromptTokens)
    }

    @Test fun `prepared request includes transformed input before provider reports usage`() {
        val prefix = response(9000)
        val initial = chat(UIMessage.user("task"), prefix)
        val request = requestContext(initial, prefix, estimate = 500)
        val current = initial.copy(messageNodes = listOf(initial.messageNodes.first(),
            prefix.copy(parts = prefix.parts + UIMessagePart.Text("x".repeat(300))).toMessageNode()))
        val snapshot = ContextUsageCalculator.snapshot(current, assistant, settings, model, streaming = true,
            liveContext = request)
        assertEquals(600, snapshot.usedTokens)
        assertNull(snapshot.latestPromptTokens)
        assertFalse(snapshot.providerAnchored)
    }

    @Test fun `missing usage on newest request never resurrects older positive measurement`() {
        val prefix = response(9000)
        val initial = chat(UIMessage.user("task"), prefix)
        val request = requestContext(initial, prefix, estimate = 500)
        val message = prefix.copy(parts = prefix.parts + UIMessagePart.Text("x".repeat(300)),
            generationMetrics = listOf(
                GenerationRequestMetrics("old", "COMPLETED", 10, 0, usage = TokenUsage(9000, 20)),
                GenerationRequestMetrics("new", "COMPLETED", 10, 0, context = request)))
        val current = initial.copy(messageNodes = listOf(initial.messageNodes.first(), message.toMessageNode()))
        val snapshot = ContextUsageCalculator.snapshot(current, assistant, settings, model)
        assertEquals(600, snapshot.usedTokens)
        assertNull(snapshot.latestPromptTokens)
        assertFalse(snapshot.providerAnchored)
    }

    @Test fun `new request remains valid after history is cropped at the same boundary`() {
        val last = response(850)
        val initial = chat(response(750), response(800), last)
        val request = requestContext(initial, included = listOf(last))
        val measured = last.copy(generationMetrics = listOf(GenerationRequestMetrics("cropped", "COMPLETED", 10, 0,
            usage = TokenUsage(400, 20), context = request)))
        val current = initial.copy(messageNodes = initial.messageNodes.dropLast(1) + measured.toMessageNode())
        val snapshot = ContextUsageCalculator.snapshot(current, assistant.copy(contextMessageLimit = 1), settings, model)
        assertEquals(400, snapshot.latestPromptTokens)
        assertEquals(420, snapshot.usedTokens)
    }

    @Test fun `per request model identity overrides stale merged response model id`() {
        val oldModel = Model(modelId = "old")
        val message = response(700).copy(modelId = oldModel.id)
        val initial = chat(message)
        val request = requestContext(initial)
        val current = chat(message.copy(generationMetrics = listOf(GenerationRequestMetrics("switched", "COMPLETED", 10, 0,
            usage = TokenUsage(400, 20), context = request))))
        val snapshot = ContextUsageCalculator.snapshot(current, assistant, settings, model)
        assertEquals(400, snapshot.latestPromptTokens)
        assertEquals(420, snapshot.usedTokens)
    }

    @Test fun `changing configured system prompt invalidates previous request anchor`() {
        val message = response(900)
        val initial = chat(message)
        val key = ContextRequestAccounting.configurationKey(assistant, model, null, emptySet(), emptySet(), null, null)
        val request = requestContext(initial).copy(configurationKey = key)
        val current = chat(message.copy(generationMetrics = listOf(GenerationRequestMetrics("prior", "COMPLETED", 10, 0,
            usage = TokenUsage(900, 20), context = request))))
        val snapshot = ContextUsageCalculator.snapshot(current, assistant.copy(systemPrompt = "new instructions"), settings, model)
        assertNull(snapshot.latestPromptTokens)
        assertFalse(snapshot.providerAnchored)
        assertTrue(snapshot.usedTokens < 100)
    }

    @Test fun `request arithmetic saturates instead of overflowing on malformed provider counts`() {
        val snapshot = ContextUsageCalculator.snapshot(chat(response(Int.MAX_VALUE, Int.MAX_VALUE)), assistant, settings, model)
        assertEquals(Int.MAX_VALUE, snapshot.usedTokens)
        assertEquals(0, snapshot.availableInput)
    }

    @Test fun `common conversation adapter uses request provenance before response model id exists`() {
        val initial = chat(UIMessage.user("task"))
        val configured = settings.copy(assistants = listOf(assistant), assistantId = assistant.id,
            chatModelId = model.id, providers = listOf(me.rerere.ai.provider.ProviderSetting.OpenAI(models = listOf(model))))
        val progress = GenerationProgress(1, GenerationPhase.WAITING, 0,
            context = requestContext(initial, estimate = 700), usage = TokenUsage(600, 0))
        val snapshot = ContextUsageCalculator.forConversation(initial, configured, progress = progress, streaming = true)
        assertEquals(600, snapshot.usedTokens)
        assertEquals(600, snapshot.latestPromptTokens)
    }

    @Test fun `request fingerprint permits streaming append but rejects edited input and rerun output`() {
        val user = UIMessage.user("original task")
        val tool = UIMessagePart.Tool("read-1", "read", "{}", output = listOf(UIMessagePart.Text("original result")))
        val response = response(500).copy(parts = listOf(tool, UIMessagePart.Text("prefix")))
        val messages = listOf(user, response)
        val request = ContextRequestAccounting.capture(model, messages, messages, messages, "config")
        assertTrue(ContextRequestAccounting.matchesInput(request, messages))
        val appended = response.copy(parts = listOf(tool, UIMessagePart.Text("prefix and new completion")))
        assertTrue(ContextRequestAccounting.matchesInput(request, listOf(user, appended)))
        assertFalse(ContextRequestAccounting.matchesInput(request, listOf(
            user.copy(parts = listOf(UIMessagePart.Text("modified task"))), appended)))
        val rerun = response.copy(parts = listOf(tool.copy(output = listOf(UIMessagePart.Text("much larger new result"))),
            UIMessagePart.Text("prefix")))
        assertFalse(ContextRequestAccounting.matchesInput(request, listOf(user, rerun)))
    }

    @Test fun `fingerprint accepts a new assistant response after a user prompt`() {
        val user = UIMessage.user("task")
        val messages = listOf(user)
        val request = ContextRequestAccounting.capture(model, messages, messages, messages, "config")
        assertTrue(ContextRequestAccounting.matchesInput(request, messages + response(500)))
        assertFalse(ContextRequestAccounting.matchesInput(request, listOf(UIMessage.user("task")) + response(500)))
    }

    @Test fun `new finish timestamp cannot revive a request prepared before compaction`() {
        val old = response(900)
        val tail = response(950).copy(finishedAt = Instant.ofEpochMilli(5000).toKotlinInstant()
            .toLocalDateTime(TimeZone.currentSystemDefault()))
        val initial = chat(old, tail)
        val compaction = ConversationCompaction(initial.id, "summary", initial.messageNodes[1].id,
            initial.messageNodes[0].id, model.id, true, 950, Instant.ofEpochMilli(2000))
        val view = ContextCompactionView.build(initial, compaction)
        val stale = requestContext(initial, included = view.messages).copy(startedAtEpochMillis = 1000)
        val measured = tail.copy(generationMetrics = listOf(GenerationRequestMetrics("stale", "COMPLETED", 10, 0,
            usage = TokenUsage(950, 20), context = stale)))
        val current = initial.copy(messageNodes = initial.messageNodes.dropLast(1) + initial.messageNodes.last().copy(messages = listOf(measured)))
        val snapshot = ContextUsageCalculator.snapshot(current, assistant, settings, model, compaction)
        assertNull(snapshot.latestPromptTokens)
        assertTrue(snapshot.compacted)
        assertTrue(snapshot.usedTokens < 100)
    }

    @Test fun `hidden provider continuation totals are billing not context measurements`() {
        val prefix = response(9000)
        val initial = chat(UIMessage.user("task"), prefix)
        val request = requestContext(initial, prefix, estimate = 500)
        val aggregate = TokenUsage(9000, 400, aggregatedRequests = true)
        val message = prefix.copy(parts = prefix.parts + UIMessagePart.Text("x".repeat(300)),
            generationMetrics = listOf(GenerationRequestMetrics("provider-cycle", "COMPLETED", 10, 0,
                usage = aggregate, context = request)))
        val current = initial.copy(messageNodes = listOf(initial.messageNodes.first(), message.toMessageNode()))
        val snapshot = ContextUsageCalculator.snapshot(current, assistant, settings, model)
        val live = ContextUsageCalculator.snapshot(current, assistant, settings, model, streaming = true,
            liveUsage = aggregate, liveContext = request, liveFinished = true)
        assertEquals(600, snapshot.usedTokens)
        assertEquals(snapshot.usedTokens, live.usedTokens)
        assertNull(snapshot.latestPromptTokens)
        assertFalse(snapshot.providerAnchored)
    }

    @Test fun `stale progress cannot replace newer persisted request context`() {
        val message = response(700).copy(generationMetrics = listOf(
            GenerationRequestMetrics("old", "COMPLETED", 10, 0, usage = TokenUsage(700, 20)),
            GenerationRequestMetrics("new", "COMPLETED", 10, 0, usage = TokenUsage(400, 20))))
        val initial = chat(message)
        val configured = settings.copy(assistants = listOf(assistant), assistantId = assistant.id,
            chatModelId = model.id, providers = listOf(me.rerere.ai.provider.ProviderSetting.OpenAI(models = listOf(model))))
        val progress = GenerationProgress(1, GenerationPhase.COMPLETED, 0, requestId = "old",
            context = requestContext(initial), usage = TokenUsage(700, 20))
        val snapshot = ContextUsageCalculator.forConversation(initial, configured, progress = progress, streaming = true)
        assertEquals(420, snapshot.usedTokens)
        assertEquals(400, snapshot.latestPromptTokens)
    }

}
