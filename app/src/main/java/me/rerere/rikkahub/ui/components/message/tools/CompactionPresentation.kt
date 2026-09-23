package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart

internal enum class CompressionState { RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED, UNKNOWN }

/** Pure presentation adapter: missing legacy timing is unknown, never a fabricated zero. */
internal data class CompressionDetails(
    val fields: JsonObject,
    val state: CompressionState,
    val summary: String,
) {
    fun text(key: String): String? = (fields[key] as? JsonPrimitive)?.contentOrNull
    fun number(key: String): Long? = (fields[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
    val isManual get() = text("mode") == "manual"
    fun elapsedMs(nowElapsed: Long): Long? = number("elapsed_ms") ?: if (state == CompressionState.RUNNING) {
        number("started_elapsed_ms")?.let { (nowElapsed - it).coerceAtLeast(0) }
    } else null
}

internal fun compressionDetails(tool: UIMessagePart.Tool, currentRuntimeId: String): CompressionDetails {
    val fields = runCatching { Json.parseToJsonElement(tool.input) as? JsonObject }.getOrNull()
        ?: JsonObject(emptyMap())
    val summary = tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    val state = when ((fields["state"] as? JsonPrimitive)?.contentOrNull) {
        "running" -> if ((fields["runtime_id"] as? JsonPrimitive)?.contentOrNull == currentRuntimeId)
            CompressionState.RUNNING else CompressionState.INTERRUPTED
        "completed" -> CompressionState.COMPLETED
        "failed" -> CompressionState.FAILED
        "cancelled" -> CompressionState.CANCELLED
        else -> if (summary.isNotBlank()) CompressionState.COMPLETED else CompressionState.UNKNOWN
    }
    return CompressionDetails(fields, state, summary)
}

internal fun formatCompressionDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
