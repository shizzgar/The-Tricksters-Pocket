package me.rerere.ai.provider

enum class GenerationFinishKind { COMPLETE, OUTPUT_LIMIT, INCOMPLETE, BLOCKED }

/** Preserve provider reason strings for diagnostics; share their meaning across consumers. */
fun classifyGenerationFinish(reason: String?): GenerationFinishKind {
    val normalized = reason?.trim()?.lowercase().orEmpty()
    val detail = normalized.removePrefix("incomplete:")
    return when {
        detail in setOf("length", "max_tokens", "max_output_tokens") -> GenerationFinishKind.OUTPUT_LIMIT
        detail in setOf("content_filter", "safety", "recitation", "blocklist", "prohibited_content", "spii", "image_safety") -> GenerationFinishKind.BLOCKED
        normalized == "incomplete" || normalized.startsWith("incomplete:") ||
            normalized in setOf("failed", "cancelled", "malformed_function_call", "unexpected_tool_call") -> GenerationFinishKind.INCOMPLETE
        else -> GenerationFinishKind.COMPLETE
    }
}
