package me.rerere.rikkahub.data.ai

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.datastore.AutoCompactionThresholdMode
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.ai.provider.GenerationProgress
import me.rerere.ai.provider.GenerationRequestContext
import me.rerere.ai.core.MessageRole
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
    val configuredCompactionTrigger: Int? = compactionTrigger,
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

/** The same effective trigger is consumed by the runtime and every context gauge. */
data class ContextUsageBudget(
    val contextLimit: Int?,
    val outputReserve: Int?,
    val configuredTrigger: Int?,
    val compactionTrigger: Int?,
    val userLimit: Boolean,
)

object ContextUsageCalculator {
    fun budget(assistant: Assistant, settings: Settings, model: Model?): ContextUsageBudget {
        val manualTrigger = (settings.autoCompactionThresholdTokensK.toLong().coerceAtLeast(1) * 1_000)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val modelLimit = model?.contextLength?.takeIf { it > 0 }
        val manual = settings.autoCompactionThresholdMode == AutoCompactionThresholdMode.TOKENS
        // A manual compaction trigger is not a replacement for known model capacity.
        // With missing metadata it is the explicit user budget and is labelled as such.
        val limit = modelLimit ?: manualTrigger.takeIf { manual }
        val reserve = assistant.maxTokens?.takeIf { it > 0 }
        val configured = if (!settings.enableAutoCompaction) null else if (manual) manualTrigger else limit?.let {
            (it.toLong() * settings.autoCompactionThresholdPercent.coerceIn(5, 95) / 100).coerceAtLeast(1).toInt()
        }
        val effective = configured?.let { trigger ->
            limit?.let { minOf(trigger.toLong(), (it.toLong() - (reserve ?: 0)).coerceAtLeast(1)).toInt() } ?: trigger
        }
        return ContextUsageBudget(limit, reserve, configured, effective, modelLimit == null && manual)
    }

    /** Central adapter: both chat gauges and the overlay resolve the same model/progress. */
    fun forConversation(
        conversation: Conversation,
        settings: Settings,
        compaction: ConversationCompaction? = null,
        progress: GenerationProgress? = null,
        streaming: Boolean = false,
    ): ContextUsageSnapshot {
        val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
        val model = settings.findModelById(conversation.chatModelId ?: assistant.chatModelId ?: settings.chatModelId)
        val savedRequests = conversation.currentMessages.flatMap { it.generationMetrics }
        val progressIndex = savedRequests.indexOfLast { it.requestId == progress?.requestId }
        val staleProgress = progressIndex >= 0 && progressIndex < savedRequests.lastIndex
        val live = progress?.takeIf { streaming && !staleProgress }
        val startedAt = live?.context?.startedAtEpochMillis?.let(Instant::ofEpochMilli)
        return snapshot(conversation, assistant, settings, model, compaction, streaming,
            liveUsage = live?.usage.takeIf { live?.context?.modelId == model?.id?.toString() },
            liveRequestStartedAt = startedAt, liveContext = live?.context,
            liveFinished = live?.finishedAt != null)
    }

    fun snapshot(
        conversation: Conversation,
        assistant: Assistant,
        settings: Settings,
        model: Model?,
        compaction: ConversationCompaction? = null,
        streaming: Boolean = false,
        liveUsage: TokenUsage? = null,
        liveRequestStartedAt: Instant? = null,
        liveContext: GenerationRequestContext? = null,
        liveFinished: Boolean = false,
    ): ContextUsageSnapshot {
        val effectiveCompaction = compaction?.takeUnless { it.isAuto && !settings.enableAutoCompaction }
        val view = ContextCompactionView.build(conversation, effectiveCompaction)
        val messages = view.messages.limitContext(assistant.contextMessageLimit)
        val limited = messages.size != view.messages.size
        val compactedAt = view.compaction?.createdAt
        val configurationKey = ContextRequestAccounting.configurationKey(
            settings.assistants.firstOrNull { it.id == assistant.id } ?: assistant, model,
            conversation.customSystemPrompt, conversation.modeInjectionIds, conversation.lorebookIds,
            conversation.workspaceCwd, AgentTaskPolicy.get(conversation.id.toString())?.systemPrompt,
        )
        fun validRequest(request: GenerationRequestContext): Boolean = model != null &&
            request.modelId == model.id.toString() &&
            request.contextPrefixMessageId == messages.firstOrNull()?.id?.toString() &&
            (request.configurationKey == null || request.configurationKey == configurationKey) &&
            ContextRequestAccounting.matchesInput(request, messages) &&
            (compactedAt == null || request.startedAtEpochMillis >= compactedAt.toEpochMilli())
        fun measuredAfterCompaction(message: UIMessage): Boolean = compactedAt == null ||
            message.finishedAt?.toInstant(TimeZone.currentSystemDefault())?.toEpochMilliseconds()?.let { it > compactedAt.toEpochMilli() } == true

        // Retain only the last request for each response. Searching backwards for positive
        // usage would revive an obsolete request when the newest provider omits its usage.
        val withMeasurements = messages.map { message ->
            val metric = message.generationMetrics.lastOrNull()
            val valid = metric?.context?.let(::validRequest) ?: (
                !limited && model != null && message.modelId == model.id && measuredAfterCompaction(message))
            if (valid) message else message.copy(usage = null, generationMetrics = emptyList())
        }
        val liveIsValid = liveContext?.let(::validRequest) ?: (
            !limited && model != null && (compactedAt == null || liveRequestStartedAt?.isAfter(compactedAt) == true))
        val live = liveUsage?.takeIf { liveIsValid && it.promptTokens > 0 && !it.aggregatedRequests }
        val prepared = liveContext?.takeIf { liveIsValid }
        val anchor = withMeasurements.lastOrNull {
            ContextBudgetPlanner.latestRequestUsage(it) != null || it.generationMetrics.lastOrNull()?.context != null
        }
        val latest = if (prepared != null || live != null) live else anchor?.let(ContextBudgetPlanner::latestRequestUsage)
        val systemPrompt = AgentTaskPolicy.get(conversation.id.toString())?.systemPrompt ?: if (
            assistant.allowConversationSystemPrompt && !conversation.customSystemPrompt.isNullOrBlank()
        ) conversation.customSystemPrompt.orEmpty() else assistant.systemPrompt
        val fallbackMessages = listOfNotNull(systemPrompt.takeIf { it.isNotBlank() }?.let { UIMessage.system(it) }) + withMeasurements
        val used = when {
            prepared != null || live != null -> ContextBudgetPlanner.estimateRequestTokens(
                messages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }, live, prepared,
                receiving = !liveFinished,
            ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            anchor != null -> ContextBudgetPlanner.estimateInputTokens(withMeasurements)
            else -> ContextBudgetPlanner.estimateContextTokens(fallbackMessages)
        }
        val budget = budget(assistant, settings, model)
        return ContextUsageSnapshot(used, budget.contextLimit, latest?.promptTokens, budget.outputReserve,
            budget.compactionTrigger, settings.enableAutoCompaction, budget.userLimit,
            latest != null, streaming, view.compaction != null, budget.configuredTrigger)
    }
}
