package me.rerere.ai.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage

@Serializable
enum class GenerationPriority { CONTINUATION, COMPACTION, INTERACTIVE, BACKGROUND, TITLE }

/** Local scheduling/transport metadata; never added to a provider's JSON payload. */
@Serializable
data class GenerationRuntimeSettings(
    val connectTimeoutSeconds: Int = 20,
    val readTimeoutMinutes: Int = 30,
    val firstResponseTimeoutMinutes: Int = 30,
    val requestTimeoutMinutes: Int = 60,
    val parallelRequests: Int = 2,
    val autonomousContinuation: Boolean = true,
    val waitForNetworkRecovery: Boolean = true,
    val resumeTasksAfterRestart: Boolean = true,
    val taskTimeoutMinutes: Int = 0,
) {
    fun normalized() = copy(
        connectTimeoutSeconds = connectTimeoutSeconds.coerceIn(5, 120),
        readTimeoutMinutes = readTimeoutMinutes.coerceIn(1, 120),
        firstResponseTimeoutMinutes = firstResponseTimeoutMinutes.coerceIn(1, 120),
        requestTimeoutMinutes = requestTimeoutMinutes.coerceIn(1, 240),
        parallelRequests = parallelRequests.coerceIn(1, 8),
        taskTimeoutMinutes = taskTimeoutMinutes.coerceIn(0, 43_200),
    )

    fun applyTo(params: TextGenerationParams): TextGenerationParams {
        val config = normalized()
        return params.copy(
            connectTimeoutMillis = config.connectTimeoutSeconds * 1_000L,
            readTimeoutMillis = config.readTimeoutMinutes * 60_000L,
            firstResponseTimeoutMillis = params.firstResponseTimeoutMillis ?: config.firstResponseTimeoutMinutes * 60_000L,
            requestTimeoutMillis = params.requestTimeoutMillis ?: config.requestTimeoutMinutes * 60_000L,
        )
    }
}

/** One queue across all text providers, including title and manual/automatic compaction.
 * Non-preemptive: a higher priority request does not abort an already running generation.
 * Cancelled waiters never dispatch; cancellation racing a grant returns the slot exactly once. */
internal class GenerationRequestQueue(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private class Ticket(val priority: GenerationPriority, val sequence: Long, val queuedAt: Long) {
        val ready = CompletableDeferred<Unit>()
        var granted = false
    }
    private val lock = Any()
    private val waiting = mutableListOf<Ticket>()
    private var active = 0
    private var sequence = 0L
    private var limit = 2

    suspend fun <T> withSlot(priority: GenerationPriority, parallelism: Int, block: suspend () -> T): T {
        val ticket = synchronized(lock) {
            limit = parallelism.coerceIn(1, 8)
            Ticket(priority, sequence++, clock()).also { waiting.add(it); drain() }
        }
        try {
            ticket.ready.await()
            currentCoroutineContext().ensureActive()
            return block()
        } finally {
            synchronized(lock) {
                waiting.remove(ticket)
                if (ticket.granted) active--
                drain()
            }
        }
    }

    private fun drain() {
        while (active < limit && waiting.isNotEmpty()) {
            // Age waiting work one rank per minute so perpetual continuations cannot starve another chat.
            val now = clock()
            val next = waiting.minWith(compareBy<Ticket> {
                (it.priority.ordinal - ((now - it.queuedAt).coerceAtLeast(0) / 60_000L)).coerceAtLeast(0)
            }.thenBy { it.sequence })
            waiting.remove(next)
            active++
            next.granted = true
            next.ready.complete(Unit)
        }
    }
}

internal class ScheduledProvider<T : ProviderSetting>(
    private val delegate: Provider<T>,
    private val queue: GenerationRequestQueue,
    private val settings: () -> GenerationRuntimeSettings,
) : Provider<T> by delegate {
    override suspend fun generateText(providerSetting: T, messages: List<UIMessage>, params: TextGenerationParams): TextGenerationResult {
        val config = settings().normalized()
        val observer = params.progressTracker?.begin(streamed = false)
        val scoped = config.applyTo(params).copy(requestObserver = observer)
        // Includes local queue time, body read, and parsing; cancellation reaches the HTTP call.
        return observeRequest(observer) { withTimeout(requireNotNull(scoped.requestTimeoutMillis)) {
            queue.withSlot(scoped.priority, config.parallelRequests) {
                val requestId = params.progressTracker?.state?.value?.requestId ?: java.util.UUID.randomUUID().toString()
                GenerationTrace.request(requestId, messages, scoped, false)
                observer?.dispatched()
                delegate.generateText(providerSetting, messages, scoped).also { result ->
                    result.usage?.let { observer?.usage(it) }
                    GenerationTrace.record(scoped.sessionId, "model.response", kotlinx.serialization.json.buildJsonObject {
                        put("request_id", kotlinx.serialization.json.JsonPrimitive(requestId))
                        put("response", kotlinx.serialization.json.Json.encodeToJsonElement(TextGenerationResult.serializer(), result))
                    })
                }
            }
        } }
    }

    override suspend fun streamText(providerSetting: T, messages: List<UIMessage>, params: TextGenerationParams): Flow<StreamChunk> = flow {
        val config = settings().normalized()
        val observer = params.progressTracker?.begin()
        val scoped = config.applyTo(params).copy(requestObserver = observer)
        observeRequest(observer) { withTimeout(requireNotNull(scoped.requestTimeoutMillis)) {
            queue.withSlot(scoped.priority, config.parallelRequests) {
                val requestId = params.progressTracker?.state?.value?.requestId ?: java.util.UUID.randomUUID().toString()
                GenerationTrace.request(requestId, messages, scoped, true)
                observer?.dispatched()
                coroutineScope {
                    val firstChunk = CompletableDeferred<Unit>()
                    // Heartbeat comments do not create provider chunks and cannot hide a
                    // permanently stuck prefill. Start only after leaving the local queue.
                    val firstResponseWatchdog = launch {
                        val received = withTimeoutOrNull(requireNotNull(scoped.firstResponseTimeoutMillis)) {
                            firstChunk.await()
                            true
                        }
                        if (received != true) throw FirstGenerationResponseTimeoutException()
                    }
                    val pendingTrace = mutableListOf<StreamChunk>()
                    var traceFlushedAt = System.nanoTime()
                    var finishReason: String? = null
                    var receivedFinish = false
                    var streamError: String? = null
                    try {
                        delegate.streamText(providerSetting, messages, scoped).collect {
                            firstChunk.complete(Unit)
                            observer?.chunk(it)
                            pendingTrace.add(it)
                            if (it is StreamChunk.Finish) { receivedFinish = true; finishReason = it.finishReason }
                            emit(it)
                            if (pendingTrace.size >= 64 || System.nanoTime() - traceFlushedAt >= 500_000_000) {
                                GenerationTrace.chunks(scoped.sessionId, requestId, pendingTrace.toList())
                                pendingTrace.clear()
                                traceFlushedAt = System.nanoTime()
                            }
                        }
                    } catch (failure: Throwable) {
                        streamError = failure.javaClass.simpleName
                        throw failure
                    } finally {
                        firstResponseWatchdog.cancel()
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            if (pendingTrace.isNotEmpty()) GenerationTrace.chunks(scoped.sessionId, requestId, pendingTrace.toList())
                            GenerationTrace.record(scoped.sessionId, "model.response", kotlinx.serialization.json.buildJsonObject {
                                put("request_id", kotlinx.serialization.json.JsonPrimitive(requestId))
                                put("stream_finished", kotlinx.serialization.json.JsonPrimitive(receivedFinish))
                                streamError?.let { put("error_type", kotlinx.serialization.json.JsonPrimitive(it)) }
                                finishReason?.let { put("finish_reason", kotlinx.serialization.json.JsonPrimitive(it)) }
                            })
                        }
                    }
                }
            }
        } }
    }
}

private suspend fun <T> observeRequest(observer: GenerationRequestObserver?, block: suspend () -> T): T {
    try {
        return block().also { observer?.finish(GenerationPhase.COMPLETED) }
    } catch (e: Throwable) {
        observer?.finish(if (e is CancellationException && e !is TimeoutCancellationException) GenerationPhase.CANCELLED else GenerationPhase.FAILED)
        throw e
    }
}

internal class FirstGenerationResponseTimeoutException : java.io.IOException(
    "Timed out waiting for the first model response. Check prefill, provider queue, and generation timeout settings.",
)
