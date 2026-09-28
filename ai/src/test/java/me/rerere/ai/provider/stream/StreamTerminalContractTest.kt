package me.rerere.ai.provider.stream

import java.io.IOException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.GenerationFinishKind
import me.rerere.ai.provider.classifyGenerationFinish
import me.rerere.ai.provider.providers.claude.ClaudeStreamDecoder
import me.rerere.ai.provider.providers.google.GoogleStreamDecoder
import me.rerere.ai.provider.providers.openai.ChatCompletionsStreamDecoder
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.hasMeaningfulModelOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTerminalContractTest {
    private val factories: List<() -> StreamChunkDecoder> = listOf(
        { ChatCompletionsStreamDecoder() }, { ClaudeStreamDecoder() }, { GoogleStreamDecoder("id", "model") },
    )

    @Test fun `empty protocol close emits no fake finish and reaches transport retry`() = runBlocking {
        for (factory in factories) {
            var attempts = 0
            val emitted = mutableListOf<StreamChunk>()
            val failure = runCatching {
                flow {
                    attempts++
                    factory().onClosed().forEach { emit(it) }
                }.retryWhen { cause, attempt -> cause is IOException && attempt < 2 }
                    .collect { emitted += it }
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(3, attempts)
            assertTrue(emitted.isEmpty())
        }
    }

    @Test fun `partial text close preserves text but never claims successful finish`() {
        val events = listOf(
            SseEvent(data = """{"choices":[{"delta":{"content":"partial"}}]}"""),
            SseEvent(event = "content_block_start", data = """{"index":0,"content_block":{"type":"text"}}"""),
            SseEvent(data = """{"candidates":[{"content":{"parts":[{"text":"partial"}]}}]}"""),
        )
        for ((index, factory) in factories.withIndex()) {
            val decoder = factory()
            val chunks = decoder.accept(events[index]).chunks + if (index == 1) {
                decoder.accept(SseEvent(event = "content_block_delta", data =
                    """{"index":0,"delta":{"type":"text_delta","text":"partial"}}""")).chunks
            } else emptyList()
            val handler = StreamChunkHandler()
            val messages = chunks.fold(listOf(UIMessage.user("question"))) { history, chunk -> handler.handle(history, chunk) }
            assertEquals("partial", messages.last().toText())
            assertTrue(chunks.none { it is StreamChunk.Finish })
            assertThrows(IOException::class.java) { decoder.onClosed() }
            assertEquals(null, messages.last().finishedAt)
        }
    }

    @Test fun `name only tool proposal materializes and prevents abandoned response replay`() = runBlocking {
        val scenarios = listOf(
            { ChatCompletionsStreamDecoder() as StreamChunkDecoder } to SseEvent(data =
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call","function":{"name":"stop_media","arguments":""}}]}}]}"""),
            { ClaudeStreamDecoder() as StreamChunkDecoder } to SseEvent(event = "content_block_start", data =
                """{"index":0,"content_block":{"type":"tool_use","id":"call","name":"stop_media","input":{}}}"""),
        )
        for ((factory, event) in scenarios) {
            var attempts = 0
            var meaningful = false
            var messages = listOf(UIMessage.user("play music"))
            val handler = StreamChunkHandler()
            val failure = runCatching {
                flow {
                    attempts++
                    factory().accept(event).chunks.forEach { emit(it) }
                    throw IOException("connection reset before arguments")
                }.retryWhen { _, attempt -> !meaningful && attempt < 2 }.collect { chunk ->
                    meaningful = meaningful || hasMeaningfulModelOutput(chunk)
                    messages = handler.handle(messages, chunk)
                }
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(1, attempts)
            assertEquals("stop_media", messages.last().getTools().single().toolName)
            assertTrue(messages.last().getTools().single().input.isEmpty())
        }
    }

    @Test fun `real terminal reasons allow close and share output limit meaning`() {
        val openai = ChatCompletionsStreamDecoder()
        openai.accept(SseEvent(data = """{"choices":[{"delta":{"content":"partial"},"finish_reason":"length"}]}"""))
        val google = GoogleStreamDecoder("id", "model")
        google.accept(SseEvent(data = """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"partial"}]}}]}"""))
        for (decoder in listOf(openai, google)) {
            val reason = decoder.onClosed().filterIsInstance<StreamChunk.Finish>().single().finishReason
            assertEquals(GenerationFinishKind.OUTPUT_LIMIT, classifyGenerationFinish(reason))
            assertTrue(decoder.onClosed().isEmpty())
        }
        val claude = ClaudeStreamDecoder()
        val terminal = claude.accept(SseEvent(event = "message_stop", data = "{}"))
        assertTrue(terminal.completed)
        assertFalse(terminal.chunks.isEmpty())
        assertTrue(claude.onClosed().isEmpty())
    }
}
