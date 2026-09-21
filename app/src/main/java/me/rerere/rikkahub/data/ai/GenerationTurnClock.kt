package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.withTimeoutOrNull

/** Compaction has a separate, cumulative allowance per turn. Repeated compactions cannot
 * reset this allowance and silently turn a bounded agent turn into an unbounded job. */
internal class GenerationTurnClock(
    private val compactionBudgetMs: Long,
    private val nowMs: () -> Long,
) {
    private val startedAt = nowMs()
    private var compactionElapsedMs = 0L

    init { require(compactionBudgetMs > 0) }

    fun compactionAllowanceExhausted() = compactionElapsedMs >= compactionBudgetMs

    fun activeElapsedMs(): Long =
        (nowMs() - startedAt - compactionElapsedMs).coerceAtLeast(0)

    suspend fun <T> duringCompaction(block: suspend () -> T): T {
        val remaining = compactionBudgetMs - compactionElapsedMs
        if (remaining <= 0) throw CompactionTimeoutException("turn allowance", compactionBudgetMs)
        val started = nowMs()
        try {
            // The callback legitimately returns null when compaction is unnecessary.
            val completed = withTimeoutOrNull(remaining) { Value(block()) }
                ?: throw CompactionTimeoutException("turn allowance", compactionBudgetMs)
            return completed.value
        } finally {
            compactionElapsedMs += (nowMs() - started).coerceAtLeast(0)
        }
    }

    private data class Value<T>(val value: T)
}

