package me.rerere.ai.provider

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.rerere.ai.ui.*
import org.junit.Assert.*
import org.junit.Test

class ScheduledProviderTest {
    private val setting = ProviderSetting.OpenAI()
    private val model = Model(modelId = "test")
    private class FakeProvider : Provider<ProviderSetting.OpenAI> {
        var calls = 0
        var cancelled = false
        val opened = CompletableDeferred<Unit>()
        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI) = emptyList<Model>()
        override suspend fun generateImage(providerSetting: ProviderSetting, params: ImageGenerationParams): Flow<ImageGenerationItem> = emptyFlow()
        override suspend fun generateText(providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams): TextGenerationResult {
            calls++
            return TextGenerationResult("id", "model", UIMessage.user("result"))
        }
        override suspend fun streamText(providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams): Flow<StreamChunk> = flow {
            calls++
            opened.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        }
    }

    @Test fun `stream holds its slot and timed out queued background request never reaches provider`() = runBlocking {
        val fake = FakeProvider()
        val provider = ScheduledProvider(fake, GenerationRequestQueue()) { GenerationRuntimeSettings(parallelRequests = 1) }
        val stream = launch { provider.streamText(setting, emptyList(), TextGenerationParams(model)).collect() }
        withTimeout(5_000) { fake.opened.await() }
        val result = withTimeoutOrNull(100) {
            provider.generateText(setting, emptyList(), TextGenerationParams(model, priority = GenerationPriority.TITLE))
        }
        assertNull(result)
        assertEquals(1, fake.calls)
        stream.cancelAndJoin()
        assertTrue(fake.cancelled)
        withTimeout(5_000) { provider.generateText(setting, emptyList(), TextGenerationParams(model)) }
        assertEquals(2, fake.calls)
    }

    @Test fun `provider deadline covers the whole silent stream`() = runBlocking {
        val fake = FakeProvider()
        val provider = ScheduledProvider(fake, GenerationRequestQueue()) { GenerationRuntimeSettings() }
        try {
            provider.streamText(setting, emptyList(), TextGenerationParams(model, requestTimeoutMillis = 50)).collect()
            fail("Silent stream exceeded its deadline")
        } catch (_: TimeoutCancellationException) {
            assertTrue(fake.cancelled)
        }
    }

    @Test fun `silent stream has a first response deadline independent of its total budget`() = runBlocking {
        val fake = FakeProvider()
        val provider = ScheduledProvider(fake, GenerationRequestQueue()) { GenerationRuntimeSettings() }
        try {
            provider.streamText(setting, emptyList(), TextGenerationParams(model,
                firstResponseTimeoutMillis = 50, requestTimeoutMillis = 5_000)).collect()
            fail("Missing first response did not time out")
        } catch (_: FirstGenerationResponseTimeoutException) {
            assertTrue(fake.cancelled)
        }
    }
}
