package me.rerere.ai.ui

/** Once output is materialized, replay cannot safely combine it with another model attempt. */
fun hasMeaningfulModelOutput(chunk: StreamChunk): Boolean = when (chunk) {
    is StreamChunk.ToolCallStart -> chunk.toolName.isNotBlank()
    is StreamChunk.TextDelta,
    is StreamChunk.ReasoningDelta,
    is StreamChunk.ToolCallDelta,
    is StreamChunk.ImageDelta,
    is StreamChunk.ImageSnapshot,
    is StreamChunk.ServerToolStart,
    is StreamChunk.ServerToolInputDelta,
    is StreamChunk.ServerToolEnd,
    is StreamChunk.Annotations -> true
    else -> false
}
