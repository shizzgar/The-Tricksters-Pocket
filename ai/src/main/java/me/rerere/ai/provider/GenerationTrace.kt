package me.rerere.ai.provider

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.StreamChunk

/** Normalized provider input/output, not a claim to contain the provider's private wire format.
 * The host stores it privately. Provider credentials and HTTP headers never enter this sink. */
object GenerationTrace {
    @Volatile var sink: (suspend (String, String, JsonObject) -> Unit)? = null

    suspend fun record(session: String?, source: String, payload: JsonObject) {
        val conversation = session?.substringBefore(':') ?: return
        if (runCatching { java.util.UUID.fromString(conversation).toString() == conversation }.getOrDefault(false)) {
            sink?.invoke(conversation, source, payload)
        }
    }

    suspend fun request(id: String, messages: List<UIMessage>, params: TextGenerationParams, stream: Boolean) {
        if (sink == null || params.sessionId == null) return
        record(params.sessionId, "model.request", buildJsonObject {
            put("request_id", id); put("session", params.sessionId); put("model", params.model.modelId)
            put("priority", params.priority.name); put("stream", stream)
            put("input_format", "normalized_provider_messages")
            put("messages", Json.encodeToJsonElement(ListSerializer(UIMessage.serializer()), messages))
            put("tools", buildJsonArray { params.tools.forEach { tool -> add(buildJsonObject {
                put("name", tool.name); put("description", tool.description)
                tool.parameters()?.let { put("parameters", Json.encodeToJsonElement(InputSchema.serializer(), it)) }
            }) } })
            put("reasoning", params.reasoningLevel.name)
            params.maxTokens?.let { put("max_tokens", it) }
            params.temperature?.let { put("temperature", it) }
            params.topP?.let { put("top_p", it) }
            put("custom_body", buildJsonObject { params.customBody.forEach { put(it.key, it.value) } })
        })
    }

    suspend fun chunks(session: String?, id: String, chunks: List<StreamChunk>) = record(session, "model.stream", buildJsonObject {
        put("request_id", id)
        put("chunks", Json.encodeToJsonElement(ListSerializer(StreamChunk.serializer()), chunks))
    })
}
