package me.rerere.ai.provider

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.core.merge

enum class GenerationPhase { PREPARING, QUEUED, WAITING, RECEIVING, COMPLETED, FAILED, CANCELLED }

/** Ephemeral observations, never conversation content or provider request data. Times are monotonic. */
data class GenerationProgress(
    val attempt: Long,
    val phase: GenerationPhase,
    val startedAt: Long,
    val dispatchedAt: Long? = null,
    val headersAt: Long? = null,
    val firstContentAt: Long? = null,
    val lastContentAt: Long? = null,
    val finishedAt: Long? = null,
    val backend: String? = null,
    val httpStatus: Int? = null,
    val requestId: String = java.util.UUID.randomUUID().toString(),
    val usage: me.rerere.ai.core.TokenUsage? = null,
    val streamed: Boolean = true,
)

class GenerationProgressTracker(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val mutable = MutableStateFlow<GenerationProgress?>(null)
    val state = mutable.asStateFlow()
    private var sequence = 0L
    private val completed = ArrayDeque<GenerationRequestMetrics>()
    @Synchronized fun completedMetrics(): List<GenerationRequestMetrics> = completed.toList()

    @Synchronized fun prepare() {
        mutable.value = GenerationProgress(++sequence, GenerationPhase.PREPARING, clock())
    }

    @Synchronized fun begin(streamed: Boolean = true): GenerationRequestObserver {
        val id = ++sequence
        mutable.value = GenerationProgress(id, GenerationPhase.QUEUED, clock(), streamed = streamed)
        return GenerationRequestObserver(this, id)
    }

    @Synchronized internal fun update(id: Long, transform: (GenerationProgress, Long) -> GenerationProgress) {
        val previous = mutable.value ?: return
        if (previous.attempt != id || previous.finishedAt != null) return
        val updated = transform(previous, clock())
        mutable.value = updated
        if (updated.finishedAt != null) {
            completed.addLast(updated.metrics())
            while (completed.size > 512) completed.removeFirst()
        }
    }
}

/** A handle belongs to exactly one attempt. Late HTTP callbacks cannot overwrite a newer attempt. */
class GenerationRequestObserver internal constructor(private val tracker: GenerationProgressTracker, private val id: Long) {
    fun dispatched() = tracker.update(id) { p, now -> p.copy(phase = GenerationPhase.WAITING, dispatchedAt = now) }
    fun headers(status: Int, backend: String?) = tracker.update(id) { p, now ->
        p.copy(headersAt = p.headersAt ?: now, httpStatus = status,
            backend = backend?.takeIf { it == "primary" || it == "secondary" })
    }
    fun content() = tracker.update(id) { p, now ->
        p.copy(phase = GenerationPhase.RECEIVING, firstContentAt = p.firstContentAt ?: now, lastContentAt = now)
    }
    fun usage(usage: me.rerere.ai.core.TokenUsage) = tracker.update(id) { p, _ -> p.copy(usage = p.usage.merge(usage)) }
    fun chunk(chunk: StreamChunk) {
        if (chunk.hasProgressContent()) content()
        if (chunk is StreamChunk.Usage) usage(chunk.usage)
    }
    fun finish(phase: GenerationPhase) = tracker.update(id) { p, now -> p.copy(phase = phase, finishedAt = now) }
}

internal fun StreamChunk.hasProgressContent(): Boolean = when (this) {
    is StreamChunk.TextDelta -> text.isNotEmpty()
    is StreamChunk.ReasoningDelta -> text.isNotEmpty()
    is StreamChunk.ToolCallStart -> toolName.isNotEmpty()
    is StreamChunk.ToolCallDelta -> toolNameDelta.isNotEmpty() || inputDelta.isNotEmpty()
    is StreamChunk.ServerToolStart -> toolName.isNotEmpty() || input != null
    is StreamChunk.ServerToolInputDelta -> inputDelta.isNotEmpty()
    is StreamChunk.ServerToolEnd -> output != null
    is StreamChunk.ImageDelta -> data.isNotEmpty()
    is StreamChunk.ImageSnapshot -> data.isNotEmpty()
    else -> false
}
