package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Shared by automatic/manual compaction. Values are normalized again at execution time
 * because settings may also arrive through a backup, rather than through the settings UI. */
data class CompactionRuntimeLimits(
    val requestTimeoutMs: Long,
    val totalTimeoutMs: Long,
    val parallelRequests: Int,
) {
    init {
        require(requestTimeoutMs > 0 && totalTimeoutMs >= requestTimeoutMs)
        require(parallelRequests in 1..MAX_PARALLEL_REQUESTS)
    }

    suspend fun <T : Any> operation(block: suspend CoroutineScope.() -> T): T =
        withTimeoutOrNull(totalTimeoutMs, block)
            ?: throw CompactionTimeoutException("operation", totalTimeoutMs)

    suspend fun <T : Any> request(block: suspend CoroutineScope.() -> T): T =
        withTimeoutOrNull(requestTimeoutMs, block)
            ?: throw CompactionTimeoutException("request", requestTimeoutMs)

    companion object {
        const val DEFAULT_REQUEST_MINUTES = 15
        const val DEFAULT_TOTAL_MINUTES = 60
        const val DEFAULT_PARALLEL_REQUESTS = 2
        const val MAX_REQUEST_MINUTES = 120
        const val MAX_TOTAL_MINUTES = 240
        const val MAX_PARALLEL_REQUESTS = 4

        fun fromMinutes(request: Int, total: Int, parallel: Int): CompactionRuntimeLimits {
            val requestMinutes = request.coerceIn(1, MAX_REQUEST_MINUTES)
            val totalMinutes = total.coerceIn(requestMinutes, MAX_TOTAL_MINUTES)
            return CompactionRuntimeLimits(
                requestTimeoutMs = requestMinutes * 60_000L,
                totalTimeoutMs = totalMinutes * 60_000L,
                parallelRequests = parallel.coerceIn(1, MAX_PARALLEL_REQUESTS),
            )
        }
    }
}

/** A local deadline is a reportable failure, whereas user/parent cancellation must propagate. */
class CompactionTimeoutException(stage: String, timeoutMs: Long) : IllegalStateException(
    "Context compaction $stage exceeded ${timeoutMs / 1_000} seconds. " +
        "Check the compaction runtime settings and the provider/proxy timeout. " +
        "You can retry compaction from the conversation history.",
)
