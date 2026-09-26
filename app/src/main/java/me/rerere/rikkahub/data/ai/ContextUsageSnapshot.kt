package me.rerere.rikkahub.data.ai

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.datastore.AutoCompactionThresholdMode
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCompactionContextLength
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationCompaction
import java.time.Instant

enum class ContextUsageLevel { UNKNOWN, NORMAL, NEAR_THRESHOLD, THRESHOLD_REACHED, FULL }

/** Current request context, distinct from cumulative task cost/billing and historical statistics. */
data class ContextUsageSnapshot(
    val usedTokens: Int,
    val contextLimit: Int?,
    val latestPromptTokens: Int?,
    val outputReserve: Int?,
    val compactionTrigger: Int?,
    val autoCompactionEnabled: Boolean,
    val userLimit: Boolean,
    val providerAnchored: Boolean,
    val streaming: Boolean,
    val compacted: Boolean,
) {
    val fraction: Float? get() = contextLimit?.let { (usedTokens.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
    val percent: Int? get() = contextLimit?.let { (usedTokens.toLong() * 100L / it).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
    val availableInput: Int? get() = contextLimit?.let { (it.toLong() - usedTokens - (outputReserve ?: 0)).coerceAtLeast(0L).toInt() }
    val level: ContextUsageLevel get() {
        val limit = contextLimit ?: return ContextUsageLevel.UNKNOWN
        val safe = (limit.toLong() - (outputReserve ?: 0)).coerceAtLeast(1L)
        val threshold = if (autoCompactionEnabled) compactionTrigger?.toLong()?.coerceAtMost(safe) ?: safe else safe
        return when {
            usedTokens >= safe -> ContextUsageLevel.FULL
            usedTokens >= threshold -> ContextUsageLevel.THRESHOLD_REACHED
            usedTokens.toLong() * 100 >= threshold * 85 -> ContextUsageLevel.NEAR_THRESHOLD
            else -> ContextUsageLevel.NORMAL
        }
    }
}

object ContextUsageCalculator {
    fun snapshot(
        conversation: Conversation,
        assistant: Assistant,
        settings: Settings,
        model: Model?,
        compaction: ConversationCompaction? = null,
        streaming: Boolean = false,
        liveUsage: TokenUsage? = null,
        liveRequestStartedAt: Instant? = null,
    ): ContextUsageSnapshot {
        val effectiveCompaction = compaction?.takeUnless { it.isAuto && !settings.enableAutoCompaction }
        val view = ContextCompactionView.build(conversation, effectiveCompaction)
        val messages = view.messages.limitContext(assistant.contextMessageLimit)
        val limited = messages.size != view.messages.size
        val compactedAt = view.compaction?.createdAt
        fun measuredAfterCompaction(message: UIMessage): Boolean = compactedAt == null ||
            message.finishedAt?.toInstant(TimeZone.currentSystemDefault())?.toEpochMilliseconds()?.let { it > compactedAt.toEpochMilli() } == true
        val withMeasurements = messages.map { message ->
            // Never carry pre-summary usage, another model's usage, or an old cropped prefix
            // into the new context. Metrics are per-request; message.usage is only a legacy fallback.
            val usage = if (!limited && model != null && message.modelId == model.id && measuredAfterCompaction(message)) {
                message.generationMetrics.lastOrNull { (it.usage?.promptTokens ?: 0) > 0 }?.usage ?: message.usage
            } else null
            message.copy(usage = usage?.takeIf { it.promptTokens > 0 }?.let {
                it.copy(totalTokens = (it.promptTokens.toLong() + it.completionTokens.coerceAtLeast(0)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            })
        }
        val live = liveUsage?.takeIf {
            !limited && it.promptTokens > 0 && model != null &&
                (compactedAt == null || liveRequestStartedAt?.isAfter(compactedAt) == true)
        }
        val latest = live ?: withMeasurements.lastOrNull { it.usage != null }?.usage
        val systemPrompt = if (assistant.allowConversationSystemPrompt && !conversation.customSystemPrompt.isNullOrBlank()) {
            conversation.customSystemPrompt.orEmpty()
        } else assistant.systemPrompt
        val fallbackMessages = listOfNotNull(systemPrompt.takeIf { it.isNotBlank() }?.let { UIMessage.system(it) }) + withMeasurements
        val used = if (live != null) {
            // Provider prompt includes system/tool overhead. Streaming output is incomplete,
            // so use the larger observed/estimated current response and keep the whole gauge approximate.
            val output = maxOf(live.completionTokens.toLong(), messages.lastOrNull()
                ?.takeIf { it.role == me.rerere.ai.core.MessageRole.ASSISTANT }
                ?.let(ContextBudgetPlanner::estimateMessageTokens) ?: 0L)
            (live.promptTokens.toLong() + output).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else if (latest != null) ContextBudgetPlanner.estimateInputTokens(withMeasurements)
        else ContextBudgetPlanner.estimateContextTokens(fallbackMessages)
        val limit = settings.getCompactionContextLength(model)?.takeIf { it > 0 }
        val reserve = assistant.maxTokens?.takeIf { it > 0 }
        val trigger = if (settings.enableAutoCompaction) when (settings.autoCompactionThresholdMode) {
            AutoCompactionThresholdMode.TOKENS -> limit
            AutoCompactionThresholdMode.PERCENT -> limit?.let {
                (it.toLong() * settings.autoCompactionThresholdPercent.coerceIn(5, 95) / 100).coerceAtLeast(1).toInt()
            }
        } else null
        return ContextUsageSnapshot(used, limit, latest?.promptTokens, reserve, trigger,
            settings.enableAutoCompaction, settings.autoCompactionThresholdMode == AutoCompactionThresholdMode.TOKENS,
            latest != null, streaming, view.compaction != null)
    }
}
