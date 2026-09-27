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
    val context: GenerationRequestContext? = null,
)

class GenerationProgressTracker(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val mutable = MutableStateFlow<GenerationProgress?>(null)
    val state = mutable.asStateFlow()
    private var sequence = 0L
    private var requestContext: GenerationRequestContext? = null
    private var onDispatched: ((String) -> Unit)? = null
    private var onFirstContent: ((String) -> Unit)? = null
    private var onFinished: ((GenerationProgress) -> Unit)? = null
    private val completed = ArrayDeque<GenerationRequestMetrics>()
    @Synchronized fun completedMetrics(): List<GenerationRequestMetrics> = completed.toList()

    @Synchronized fun prepare(
        context: GenerationRequestContext? = null,
        onDispatched: ((String) -> Unit)? = null,
        onFirstContent: ((String) -> Unit)? = null,
        onFinished: ((GenerationProgress) -> Unit)? = null,
    ) {
        this.onDispatched = onDispatched
        this.onFirstContent = onFirstContent
        this.onFinished = onFinished
        requestContext = context
        mutable.value = GenerationProgress(++sequence, GenerationPhase.PREPARING, clock(), context = context)
    }

    @Synchronized fun begin(streamed: Boolean = true): GenerationRequestObserver {
        val id = ++sequence
        mutable.value = GenerationProgress(id, GenerationPhase.QUEUED, clock(), streamed = streamed,
            context = requestContext?.copy(startedAtEpochMillis = System.currentTimeMillis()))
        return GenerationRequestObserver(this, id, requireNotNull(mutable.value).requestId, onDispatched, onFirstContent, onFinished)
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
class GenerationRequestObserver internal constructor(private val tracker: GenerationProgressTracker, private val id: Long, val requestId: String, private val onDispatched: ((String) -> Unit)? = null, private val onFirstContent: ((String) -> Unit)? = null, private val onFinished: ((GenerationProgress) -> Unit)? = null) {
    fun dispatched() = tracker.update(id) { p, now ->
        // Called after leaving the local request queue, immediately before transport. A
        // failed durable checkpoint aborts dispatch instead of losing pending instructions.
        if (p.dispatchedAt == null) onDispatched?.invoke(requestId)
        p.copy(phase = GenerationPhase.WAITING, dispatchedAt = p.dispatchedAt ?: now)
    }
    fun headers(status: Int, backend: String?) = tracker.update(id) { p, now ->
        p.copy(headersAt = p.headersAt ?: now, httpStatus = status,
            backend = backend?.takeIf { it == "primary" || it == "secondary" })
    }
    fun content() = tracker.update(id) { p, now ->
        if (p.firstContentAt == null) {
            try { onFirstContent?.invoke(requestId) } catch (_: Exception) { /* Bookkeeping cannot discard model output. */ }
        }
        p.copy(phase = GenerationPhase.RECEIVING, firstContentAt = p.firstContentAt ?: now, lastContentAt = now)
    }
    fun usage(usage: me.rerere.ai.core.TokenUsage) = tracker.update(id) { p, _ -> p.copy(usage = p.usage.merge(usage)) }
    fun chunk(chunk: StreamChunk) {
        if (chunk.hasProgressContent()) content()
        if (chunk is StreamChunk.Usage) usage(chunk.usage)
    }
    fun finish(phase: GenerationPhase) = tracker.update(id) { p, now ->
        p.copy(phase = phase, finishedAt = now).also { finished ->
            try { onFinished?.invoke(finished) } catch (_: Exception) { /* Preserve the actual terminal outcome. */ }
        }
    }
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
