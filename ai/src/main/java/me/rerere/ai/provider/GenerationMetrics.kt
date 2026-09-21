package me.rerere.ai.provider

import kotlinx.serialization.Serializable
import me.rerere.ai.core.TokenUsage

/** Durations, not monotonic timestamps, are persisted: they remain valid after reboot. */
@Serializable
data class GenerationRequestMetrics(
    val requestId: String,
    val phase: String,
    val totalMs: Long,
    val queueMs: Long,
    val firstContentMs: Long? = null,
    val receivingMs: Long? = null,
    val usage: TokenUsage? = null,
    val backend: String? = null,
    val httpStatus: Int? = null,
    val streamed: Boolean = true,
) {
    val tokensPerSecond: Double? get() = if (streamed && (receivingMs ?: 0) > 0 && (usage?.completionTokens ?: 0) > 0)
        usage!!.completionTokens * 1000.0 / receivingMs!! else null
}

/** Never divide one request's output by the entire message/tool execution lifetime. */
fun List<GenerationRequestMetrics>.generationTokensPerSecond(): Double? {
    val measured = filter { it.tokensPerSecond != null }
    val millis = measured.sumOf { it.receivingMs ?: 0 }
    return if (millis > 0) measured.sumOf { it.usage?.completionTokens?.toLong() ?: 0 } * 1000.0 / millis else null
}

fun GenerationProgress.metrics(now: Long = System.nanoTime() / 1_000_000): GenerationRequestMetrics {
    val end = finishedAt ?: now
    return GenerationRequestMetrics(requestId, phase.name, (end - startedAt).coerceAtLeast(0),
        ((dispatchedAt ?: end) - startedAt).coerceAtLeast(0),
        firstContentAt?.let { (it - (dispatchedAt ?: startedAt)).coerceAtLeast(0) },
        firstContentAt?.let { first -> lastContentAt?.let { (it - first).coerceAtLeast(0) } },
        usage, backend, httpStatus, streamed)
}
