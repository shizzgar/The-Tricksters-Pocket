package me.rerere.rikkahub.data.ai

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.GenerationRequestMetrics
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

    @Test fun `manual token capacity overrides metadata and retains configured output reserve`() {
        val snapshot = ContextUsageCalculator.snapshot(chat(response(100)), assistant,
            settings.copy(autoCompactionThresholdMode = AutoCompactionThresholdMode.TOKENS, autoCompactionThresholdTokensK = 8), model)
        assertEquals(8000, snapshot.contextLimit)
        assertEquals(8000, snapshot.compactionTrigger)
        assertEquals(100, snapshot.outputReserve)
        assertTrue(snapshot.userLimit)
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
}
