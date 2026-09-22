package me.rerere.ai.provider

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.*
import org.junit.Assert.*
import org.junit.Test

class GenerationProgressTest {
    @Test fun `request identities survive a newer attempt on the same tracker`() {
        val tracker = GenerationProgressTracker()
        val first = tracker.begin()
        val original = first.requestId
        val second = tracker.begin()
        assertEquals(original, first.requestId)
        assertNotEquals(first.requestId, second.requestId)
        assertEquals(second.requestId, tracker.state.value?.requestId)
    }

    @Test fun `control events are not first content and completed observations are frozen`() {
        var clock = 10L
        val tracker = GenerationProgressTracker { clock }
        val observer = tracker.begin()
        clock = 20
        observer.dispatched()
        observer.headers(200, "primary")
        observer.chunk(StreamChunk.TextStart("a"))
        observer.chunk(StreamChunk.TextDelta("a", ""))
        observer.chunk(StreamChunk.Finish())
        assertNull(tracker.state.value!!.firstContentAt)
        assertEquals(GenerationPhase.WAITING, tracker.state.value!!.phase)
        clock = 30
        observer.chunk(StreamChunk.ReasoningDelta("a", "thought"))
        clock = 40
        observer.chunk(StreamChunk.ToolCallStart("b", "tool"))
        assertEquals(30L, tracker.state.value!!.firstContentAt)
        assertEquals(40L, tracker.state.value!!.lastContentAt)
        observer.finish(GenerationPhase.COMPLETED)
        val finished = tracker.state.value
        clock = 90
        observer.headers(503, "secondary")
        observer.content()
        assertEquals(finished, tracker.state.value)
    }

    @Test fun `late callbacks cannot overwrite a new attempt or preparation`() {
        val tracker = GenerationProgressTracker { 0 }
        val old = tracker.begin()
        tracker.prepare()
        old.finish(GenerationPhase.CANCELLED)
        assertEquals(GenerationPhase.PREPARING, tracker.state.value!!.phase)
        val next = tracker.begin()
        next.headers(200, "not-an-allowed-backend")
        old.content()
        old.headers(200, "primary")
        assertNull(tracker.state.value!!.backend)
        assertNull(tracker.state.value!!.firstContentAt)
    }

    @Test fun `runtime observers never enter serialized generation parameters`() {
        val tracker = GenerationProgressTracker()
        val params = TextGenerationParams(Model(modelId = "test"), progressTracker = tracker, requestObserver = tracker.begin())
        val encoded = Json { encodeDefaults = true }.encodeToString(params)
        assertFalse(encoded.contains("progressTracker"))
        assertFalse(encoded.contains("requestObserver"))
    }

    private class SilentProvider : Provider<ProviderSetting.OpenAI> {
        val opened = CompletableDeferred<Unit>()
        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI) = emptyList<Model>()
        override suspend fun generateImage(providerSetting: ProviderSetting, params: ImageGenerationParams): Flow<ImageGenerationItem> = emptyFlow()
        override suspend fun generateText(providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams): TextGenerationResult = error("Must not dispatch queued request")
        override suspend fun streamText(providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams): Flow<StreamChunk> = flow {
            params.requestObserver?.headers(200, "secondary")
            emit(StreamChunk.TextStart("x"))
            opened.complete(Unit)
            awaitCancellation()
        }
    }

    @Test fun `queue wait and dispatched wait remain distinguishable through cancellation`() = runBlocking {
        val delegate = SilentProvider()
        val provider = ScheduledProvider(delegate, GenerationRequestQueue()) { GenerationRuntimeSettings(parallelRequests = 1) }
        val first = GenerationProgressTracker()
        val second = GenerationProgressTracker()
        val setting = ProviderSetting.OpenAI()
        val model = Model(modelId = "test")
        val active = launch { provider.streamText(setting, emptyList(), TextGenerationParams(model, progressTracker = first)).collect() }
        withTimeout(5000) { delegate.opened.await() }
        val waiting = launch { provider.generateText(setting, emptyList(), TextGenerationParams(model, progressTracker = second)) }
        yield()
        assertEquals(GenerationPhase.WAITING, first.state.value!!.phase)
        assertEquals("secondary", first.state.value!!.backend)
        assertNull(first.state.value!!.firstContentAt)
        assertEquals(GenerationPhase.QUEUED, second.state.value!!.phase)
        assertNull(second.state.value!!.dispatchedAt)
        waiting.cancelAndJoin()
        active.cancelAndJoin()
        assertEquals(GenerationPhase.CANCELLED, first.state.value!!.phase)
        assertEquals(GenerationPhase.CANCELLED, second.state.value!!.phase)
    }
}
