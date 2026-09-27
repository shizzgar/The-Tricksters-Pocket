package me.rerere.ai.provider

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.*
import org.junit.Assert.*
import org.junit.Test

class GenerationProgressTest {
    @Test fun `request context survives provider attempts and persists in completed metrics`() {
        val tracker = GenerationProgressTracker { 10L }
        val context = GenerationRequestContext("model", 1, 500, "first", "response", 100, setOf("old-tool"))
        tracker.prepare(context)
        val first = tracker.begin()
        first.finish(GenerationPhase.FAILED)
        val second = tracker.begin()
        second.usage(me.rerere.ai.core.TokenUsage(600, 20))
        second.finish(GenerationPhase.COMPLETED)
        val metrics = tracker.completedMetrics()
        assertEquals(2, metrics.size)
        assertEquals("model", metrics.last().context?.modelId)
        assertEquals(setOf("old-tool"), metrics.last().context?.includedToolCallIds)
        assertTrue(metrics.last().context!!.startedAtEpochMillis > 1)
        val restored = Json.decodeFromString<GenerationRequestMetrics>(Json.encodeToString(metrics.last()))
        assertEquals(metrics.last(), restored)
        tracker.prepare()
        tracker.begin()
        assertNull(tracker.state.value?.context)
    }

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

    @Test fun `dispatch hooks are bound to the attempt and preparation does not consume`() {
        val events = mutableListOf<String>()
        val tracker = GenerationProgressTracker()
        tracker.prepare(onDispatched = { events += "dispatch:$it" }, onFirstContent = { events += "content:$it" },
            onFinished = { events += "finish:${it.phase}" })
        val queued = tracker.begin()
        assertTrue(events.isEmpty())
        queued.finish(GenerationPhase.CANCELLED)
        assertEquals(listOf("finish:CANCELLED"), events)
        events.clear()
        val actual = tracker.begin()
        actual.dispatched()
        actual.dispatched()
        actual.content()
        actual.content()
        actual.finish(GenerationPhase.COMPLETED)
        assertEquals(listOf("dispatch:${actual.requestId}", "content:${actual.requestId}", "finish:COMPLETED"), events)
        tracker.prepare()
        actual.dispatched()
        tracker.begin().dispatched()
        assertEquals(3, events.size)
    }

    @Test fun `failed durable dispatch checkpoint cannot mark request as dispatched`() {
        val tracker = GenerationProgressTracker()
        tracker.prepare(onDispatched = { throw java.io.IOException("checkpoint unavailable") })
        val observer = tracker.begin()
        assertThrows(java.io.IOException::class.java) { observer.dispatched() }
        assertNull(tracker.state.value!!.dispatchedAt)
        assertEquals(GenerationPhase.QUEUED, tracker.state.value!!.phase)
    }

    @Test fun `post-dispatch bookkeeping failure cannot discard content or mask cancellation`() {
        val tracker = GenerationProgressTracker()
        tracker.prepare(onFirstContent = { throw java.io.IOException("disk full") },
            onFinished = { throw java.io.IOException("disk full") })
        val observer = tracker.begin()
        observer.dispatched()
        observer.content()
        assertNotNull(tracker.state.value!!.firstContentAt)
        observer.finish(GenerationPhase.CANCELLED)
        assertEquals(GenerationPhase.CANCELLED, tracker.state.value!!.phase)
        assertEquals(1, tracker.completedMetrics().size)
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
