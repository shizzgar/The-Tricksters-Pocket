package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.GenerationRequestContext

data class ContextBudgetPlan(
    val estimatedInputTokens: Int,
    val triggerTokens: Int,
    val shouldCompact: Boolean,
)

/**
 * Conservative provider-independent context estimator.
 *
 * The most recent real usage is preferred because it includes provider-specific system and
 * tool-schema overhead. Messages added after that response are estimated locally. Providers
 * that do not report usage fall back to estimating the complete message list.
 */
object ContextBudgetPlanner {
    private const val CHARS_PER_TOKEN = 3
    private const val MESSAGE_OVERHEAD_TOKENS = 8
    private const val MEDIA_PART_TOKENS = 1_024

    fun plan(
        messages: List<UIMessage>,
        contextLength: Int,
        thresholdPercent: Int,
        thresholdTokensK: Int? = null,
        reservedTokens: Int = 0,
    ): ContextBudgetPlan {
        require(contextLength > 0) { "contextLength must be positive" }
        val normalizedThreshold = thresholdPercent.coerceIn(5, 95)
        val safeInputBudget = (contextLength.toLong() - reservedTokens.coerceAtLeast(0).toLong())
            .coerceAtLeast(1L)
        val configuredTriggerTokens = thresholdTokensK?.let { tokensK ->
            (tokensK.toLong().coerceAtLeast(1L) * 1_000L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        } ?: (contextLength.toLong() * normalizedThreshold / 100L)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val triggerTokens = minOf(configuredTriggerTokens.toLong(), safeInputBudget)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val estimatedInputTokens = estimateInputTokens(messages)
        return ContextBudgetPlan(
            estimatedInputTokens = estimatedInputTokens,
            triggerTokens = triggerTokens,
            shouldCompact = estimatedInputTokens >= triggerTokens,
        )
    }

    fun estimateInputTokens(messages: List<UIMessage>): Int {
        val usageIndex = messages.indexOfLast { message ->
            latestRequestUsage(message) != null || message.generationMetrics.lastOrNull()?.context != null
        }
        val estimate = if (usageIndex >= 0) {
            val message = messages[usageIndex]
            estimateRequestTokens(message, latestRequestUsage(message), message.generationMetrics.lastOrNull()?.context) +
                messages.drop(usageIndex + 1).sumOf(::estimateMessageTokens)
        } else {
            messages.sumOf(::estimateMessageTokens)
        }
        return estimate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** A later request without usage must not inherit an earlier request's positive fields. */
    fun latestRequestUsage(message: UIMessage): TokenUsage? =
        (if (message.generationMetrics.isNotEmpty()) message.generationMetrics.last().usage else message.usage)
            ?.takeIf { it.promptTokens > 0 && !it.aggregatedRequests }

    fun estimateRequestTokens(
        message: UIMessage?,
        usage: TokenUsage?,
        request: GenerationRequestContext?,
        receiving: Boolean = false,
    ): Long {
        val prompt = usage?.promptTokens?.takeIf { it > 0 }?.toLong()
            ?: request?.estimatedInputTokens?.coerceAtLeast(0)?.toLong() ?: 0L
        val freshToolResults = message?.let {
            estimatePostUsageToolOutputTokens(it, request?.includedToolCallIds.orEmpty())
        } ?: 0L
        val prefix = request?.takeIf { it.responseMessageId == message?.id?.toString() }
            ?.responsePrefixTokens?.coerceAtLeast(0L) ?: 0L
        val responseEstimate = ((message?.let(::estimateMessageTokens) ?: 0L) - prefix - freshToolResults)
            .coerceAtLeast(0L)
        val reportedOutput = usage?.completionTokens?.coerceAtLeast(0)?.toLong()
        val output = when {
            reportedOutput == null -> responseEstimate
            receiving -> maxOf(reportedOutput, responseEstimate)
            else -> reportedOutput
        }
        // A hook is present in this live request, but is not retained in the next one.
        val expiredHookTokens = if (receiving) 0L else request?.transientHookTokens?.coerceAtLeast(0)?.toLong() ?: 0L
        return (prompt - expiredHookTokens).coerceAtLeast(0L) + output + freshToolResults
    }

    /**
     * Estimates the context represented by the supplied messages without using provider usage.
     * This is required after compaction because reported usage belongs to the pre-compaction
     * request and cannot describe the synthetic summary that replaced it.
     */
    fun estimateContextTokens(messages: List<UIMessage>): Int = messages
        .sumOf(::estimateMessageTokens)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

    fun estimateMessageTokens(message: UIMessage): Long {
        val contentTokens = message.parts.sumOf(::estimatePartTokens)
        return MESSAGE_OVERHEAD_TOKENS + contentTokens
    }

    /**
     * Estimates only the execution results that were appended after provider usage was emitted.
     * Tool-call names and arguments already belong to the assistant completion represented by the
     * reported usage, so counting them here would double-count that response.
     */
    @Suppress("DEPRECATION")
    private fun estimatePostUsageToolOutputTokens(message: UIMessage, includedToolCallIds: Set<String>): Long = message.parts.sumOf { part ->
        when (part) {
            is UIMessagePart.Tool -> if (part.toolCallId in includedToolCallIds) 0L else part.output.sumOf(::estimatePartTokens)
            is UIMessagePart.ToolResult -> estimateTextTokens(part.content.toString())
            else -> 0L
        }
    }

    /** Selects a recent tail whose estimated size fits [targetTokens]. */
    fun chooseTailStartIndex(
        messages: List<UIMessage>,
        targetTokens: Int,
        minimumRecentMessages: Int = 4,
    ): Int {
        if (messages.size <= 1) return 0

        val minimumStart = (messages.size - minimumRecentMessages.coerceAtLeast(1))
            .coerceAtLeast(0)
        var start = messages.lastIndex
        var used = 0L
        while (start >= 0) {
            val next = estimateMessageTokens(messages[start])
            if (start < minimumStart && used + next > targetTokens) break
            used += next
            start--
        }
        return (start + 1).coerceIn(1, messages.lastIndex)
    }

    @Suppress("DEPRECATION")
    private fun estimatePartTokens(part: UIMessagePart): Long = when (part) {
        is UIMessagePart.Text -> estimateTextTokens(part.text)
        is UIMessagePart.Reasoning -> estimateTextTokens(part.reasoning)
        is UIMessagePart.Tool -> estimateTextTokens(part.toolName) +
            estimateTextTokens(part.input) + part.output.sumOf(::estimatePartTokens)
        is UIMessagePart.ServerTool -> estimateTextTokens(part.toolName) +
            estimateTextTokens(part.input.toString()) + estimateTextTokens(part.output.toString())
        is UIMessagePart.ToolCall -> estimateTextTokens(part.toolName) + estimateTextTokens(part.arguments)
        is UIMessagePart.ToolResult -> estimateTextTokens(part.toolName) +
            estimateTextTokens(part.arguments.toString()) + estimateTextTokens(part.content.toString())
        is UIMessagePart.Document -> MEDIA_PART_TOKENS + estimateTextTokens(part.fileName)
        is UIMessagePart.Image,
        is UIMessagePart.Video,
        is UIMessagePart.Audio,
        UIMessagePart.Search,
            -> MEDIA_PART_TOKENS.toLong()
    }

    /** Same text estimator used by the prepared-request accounting for hook instructions. */
    fun estimateHookPromptTokens(text: String): Int = estimateTextTokens(text).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun estimateTextTokens(text: String): Long {
        var asciiChars = 0L
        var nonAsciiChars = 0L
        text.forEach { char ->
            if (char.code <= 0x7F) asciiChars++ else nonAsciiChars++
        }
        return nonAsciiChars + (asciiChars + CHARS_PER_TOKEN - 1L) / CHARS_PER_TOKEN
    }
}
