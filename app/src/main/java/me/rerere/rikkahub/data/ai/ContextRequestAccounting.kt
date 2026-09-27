package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.GenerationRequestContext
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Request metadata shared by generation, the composer, message statistics and the overlay. */
object ContextRequestAccounting {
    private class IdentityPart(val value: UIMessagePart) {
        override fun hashCode() = System.identityHashCode(value)
        override fun equals(other: Any?) = other is IdentityPart && value === other.value
    }
    // Immutable message parts are shared between stream updates. Bound retained references and
    // avoid serializing old multi-megabyte tool output again for each generated token.
    private val partHashes = object : LinkedHashMap<IdentityPart, String>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<IdentityPart, String>?) = size > 256
    }
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { byte -> (byte.toInt() and 255).toString(16).padStart(2, '0') }

    private fun partsHash(parts: List<UIMessagePart>): String = digest(parts.joinToString("|") { part ->
        synchronized(partHashes) {
            partHashes.getOrPut(IdentityPart(part)) {
                // Delivery labels are local metadata, not part of the model input.
                val inputPart = if (part is UIMessagePart.Tool) part.copy(hookNotices = emptyList(), executionAttemptId = null) else part
                digest(Json.encodeToString(UIMessagePart.serializer(), inputPart))
            }
        }
    })

    private fun messagesHash(messages: List<UIMessage>): String = digest(messages.joinToString("|") {
        "${it.id}:${it.role}:${partsHash(it.parts)}"
    })

    /** Editing input or rerunning an included tool invalidates its old provider measurement. */
    fun matchesInput(request: GenerationRequestContext, messages: List<UIMessage>): Boolean {
        val count = request.inputMessageCount ?: return true // legacy provenance
        if (count < 0 || count > messages.size) return false
        if (messagesHash(messages.take(count)) != request.inputContentHash) return false
        val prefixCount = request.responsePrefixPartCount ?: return true
        val response = messages.getOrNull(count)?.takeIf { it.id.toString() == request.responseMessageId } ?: return false
        if (prefixCount < 0 || response.parts.size < prefixCount) return false
        val prefix = response.parts.take(prefixCount).toMutableList()
        request.responsePrefixLastTextLength?.let { length ->
            val last = prefix.lastOrNull() as? UIMessagePart.Text ?: return false
            if (length < 0 || last.text.length < length) return false
            if (last.text.length != length) prefix[prefix.lastIndex] = last.copy(text = last.text.take(length))
        }
        return partsHash(prefix) == request.responsePrefixContentHash
    }

    fun configurationKey(
        assistant: Assistant,
        model: Model?,
        conversationSystemPrompt: String?,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
        workspaceCwd: String?,
        policySystemPrompt: String?,
    ): String {
        val value = listOf(
            Json.encodeToString(Assistant.serializer(), assistant),
            model?.let { Json.encodeToString(Model.serializer(), it) }.orEmpty(),
            conversationSystemPrompt.orEmpty(),
            modeInjectionIds.sortedBy { it.toString() }.joinToString(),
            lorebookIds.sortedBy { it.toString() }.joinToString(),
            workspaceCwd.orEmpty(), policySystemPrompt.orEmpty(),
        ).joinToString("\u0000")
        return digest(value)
    }

    fun capture(
        model: Model,
        messages: List<UIMessage>,
        includedMessages: List<UIMessage>,
        transformedMessages: List<UIMessage>,
        configurationKey: String,
        hookDeliveryIds: Set<String> = emptySet(),
        transientHookTokens: Int = 0,
    ): GenerationRequestContext {
        val responsePrefix = messages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }
        val immutableInput = if (responsePrefix != null) includedMessages.dropLast(1) else includedMessages
        return GenerationRequestContext(
            modelId = model.id.toString(),
            startedAtEpochMillis = System.currentTimeMillis(),
            estimatedInputTokens = ContextBudgetPlanner.estimateContextTokens(transformedMessages),
            contextPrefixMessageId = includedMessages.firstOrNull()?.id?.toString(),
            responseMessageId = responsePrefix?.id?.toString(),
            responsePrefixTokens = responsePrefix?.let(ContextBudgetPlanner::estimateMessageTokens) ?: 0,
            includedToolCallIds = includedMessages.flatMap { it.parts }
                .filterIsInstance<UIMessagePart.Tool>().filter { it.isExecuted }
                .mapTo(mutableSetOf()) { it.toolCallId },
            configurationKey = configurationKey,
            hookDeliveryIds = hookDeliveryIds,
            transientHookTokens = transientHookTokens.coerceAtLeast(0),
            inputMessageCount = immutableInput.size,
            inputContentHash = messagesHash(immutableInput),
            responsePrefixPartCount = responsePrefix?.parts?.size,
            responsePrefixLastTextLength = (responsePrefix?.parts?.lastOrNull() as? UIMessagePart.Text)?.text?.length,
            responsePrefixContentHash = responsePrefix?.let { partsHash(it.parts) },
        )
    }
}
