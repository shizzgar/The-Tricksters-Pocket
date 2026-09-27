package me.rerere.ai.ui

/** One attempt's process-local clock. Never serialize its monotonic origin. */
class ToolExecutionTimer(private val nanoTime: () -> Long = System::nanoTime) {
    private val startedNanos = nanoTime()
    @Volatile private var finishedMillis: Long? = null

    val isRunning: Boolean get() = finishedMillis == null
    fun elapsedMillis(): Long = finishedMillis ?: ((nanoTime() - startedNanos) / 1_000_000).coerceAtLeast(0)

    @Synchronized
    fun finish(): Long = finishedMillis ?: elapsedMillis().also { finishedMillis = it }
}
