package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.GenerationProgress
import me.rerere.ai.provider.GenerationRequestMetrics
import me.rerere.ai.provider.generationTokensPerSecond
import me.rerere.ai.provider.metrics
import me.rerere.ai.ui.UIMessage

/** Request usage and whole-reply totals have deliberately separate names and display scopes. */
internal data class MessageMetricsSummary(
    val requests: List<GenerationRequestMetrics>,
    val legacyUsage: TokenUsage?,
) {
    val latest get() = requests.lastOrNull()
    val latestUsage get() = latest?.usage
    val measuredRequests get() = requests.count { it.usage != null }
    val inputTokens: Long? get() = measuredTotal { it.promptTokens }
    val outputTokens: Long? get() = measuredTotal { it.completionTokens }
    val partial: Boolean get() = requests.isNotEmpty() &&
        (measuredRequests != requests.size || requests.any { it.phase != "COMPLETED" })
    val speed: Double? get() = requests.generationTokensPerSecond()
    val cost: Double? get() = requests.mapNotNull { it.usage?.cost }
        .ifEmpty { listOfNotNull(legacyUsage?.cost) }.takeIf { it.isNotEmpty() }?.sum()

    private fun measuredTotal(tokens: (TokenUsage) -> Int): Long? {
        val usages = requests.mapNotNull { it.usage }
        return if (usages.isNotEmpty()) usages.sumOf { tokens(it).coerceAtLeast(0).toLong() }
        else legacyUsage?.let { tokens(it).coerceAtLeast(0).toLong() }
    }

    companion object {
        private val finishedPhases = setOf("COMPLETED", "FAILED", "CANCELLED")

        fun from(message: UIMessage, progress: GenerationProgress?, now: Long): MessageMetricsSummary {
            val byId = linkedMapOf<String, GenerationRequestMetrics>()
            val live = progress?.takeIf { it.dispatchedAt != null }?.metrics(now)
            for (request in message.generationMetrics + listOfNotNull(live)) {
                val previous = byId[request.requestId]
                // A stale in-flight observation cannot restart a persisted completed request.
                val chosen = if (previous?.phase in finishedPhases && request.phase !in finishedPhases) previous!! else request
                byId[request.requestId] = chosen.copy(usage = chosen.usage ?: previous?.usage)
            }
            return MessageMetricsSummary(byId.values.toList(), message.usage.takeIf { byId.isEmpty() })
        }
    }
}
