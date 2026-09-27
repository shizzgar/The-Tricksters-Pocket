package me.rerere.ai.provider

import kotlinx.serialization.Serializable

/**
 * Local provenance for one model request. Contains identifiers/counts only, never prompt text.
 * This lets gauges distinguish this request from earlier rounds merged into the same message.
 */
@Serializable
data class GenerationRequestContext(
    val modelId: String,
    val startedAtEpochMillis: Long,
    val estimatedInputTokens: Int,
    val contextPrefixMessageId: String? = null,
    val responseMessageId: String? = null,
    val responsePrefixTokens: Long = 0,
    val includedToolCallIds: Set<String> = emptySet(),
    val configurationKey: String? = null,
    val inputMessageCount: Int? = null,
    val inputContentHash: String? = null,
    val responsePrefixPartCount: Int? = null,
    val responsePrefixLastTextLength: Int? = null,
    val responsePrefixContentHash: String? = null,
    // User hooks are one-request additions, unlike the retained conversation history.
    val hookDeliveryIds: Set<String> = emptySet(),
    val transientHookTokens: Int = 0,
)
