package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureSessionHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class CompactionRequestContractTest {
    @Test fun `Qwen background request emits low effort without unsupported budget or runtime metadata`() {
        val provider = ProviderSetting.OpenAI(baseUrl = "http://aitower:8000/v1")
        val model = Model(modelId = "Qwen/Qwen3.8-27B-AWQ-INT4", abilities = listOf(ModelAbility.REASONING))
        val params = TextGenerationParams(model, reasoningLevel = ReasoningLevel.OFF,
            sessionId = "conversation:compaction:operation:1", requestTimeoutMillis = 1_800_000,
            priority = GenerationPriority.COMPACTION, isCompaction = true,
            customBody = listOf(CustomBody("thinking_token_budget", JsonPrimitive(8192)),
                CustomBody("reasoning_effort", JsonPrimitive("xhigh"))))
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildChatCompletionRequest",
            List::class.java, TextGenerationParams::class.java, ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        val body = method.invoke(api, listOf(UIMessage.user("Summarize this conversation")), params, provider, false) as JsonObject
        assertEquals("low", body["reasoning_effort"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("thinking_token_budget"))
        assertFalse(body.containsKey("requestTimeoutMillis"))
        assertFalse(body.containsKey("priority"))
        assertFalse(body.containsKey("sessionId"))
        assertFalse(body.containsKey("isCompaction"))
        val chatBody = method.invoke(api, listOf(UIMessage.user("Hello")), params.copy(isCompaction = false), provider, false) as JsonObject
        assertEquals("xhigh", chatBody["reasoning_effort"]?.jsonPrimitive?.content)
        val request = Request.Builder().url(provider.baseUrl)
            .configureSessionHeaders(provider.baseUrl, params.sessionId).build()
        assertEquals("conversation:compaction:operation:1", request.header("X-Session-ID"))
    }
}
