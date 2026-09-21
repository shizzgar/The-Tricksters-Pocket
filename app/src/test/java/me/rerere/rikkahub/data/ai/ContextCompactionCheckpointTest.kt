package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import kotlin.uuid.Uuid

class ContextCompactionCheckpointTest {
    private fun source() = Conversation(assistantId = Uuid.random(), messageNodes = listOf(
        MessageNode(messages = listOf(UIMessage.user("Investigate the selected target"))),
        MessageNode(messages = listOf(UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Tool("call", "termux_run_command", "{}", executionStartedAt = 10),
        )))),
    ))

    private fun completed(source: Conversation): List<UIMessage> = source.currentMessages.dropLast(1) +
        source.currentMessages.last().copy(parts = listOf(
            UIMessagePart.Tool("call", "termux_run_command", "{}", executionStartedAt = 10,
                output = listOf(UIMessagePart.Text("exit_code=0; result now recorded"))),
        ))

    @Test fun `old buffered delivery reproduces a false source-change conflict`() = runBlocking {
        withTimeout(5000) {
            val state = MutableStateFlow(source())
            val result = completed(state.value)
            val snapshot = CompletableDeferred<Conversation>()
            val collectorEntered = CompletableDeferred<Unit>()
            val releaseCollector = CompletableDeferred<Unit>()
            val job = launch {
                flow<GenerationChunk> {
                    emitToolResultCheckpoint(result, awaitPersistence = false)
                    snapshot.complete(state.value) // The old onAfterToolExecution path.
                }.buffer(64).flowOn(Dispatchers.IO).collect { chunk ->
                    collectorEntered.complete(Unit)
                    releaseCollector.await()
                    state.value = state.value.updateCurrentMessages((chunk as GenerationChunk.Messages).messages)
                }
            }
            collectorEntered.await()
            val oldSource = snapshot.await()
            releaseCollector.complete(Unit)
            job.join()
            assertFalse(ContextCompactionPresentation.sourcePrefixUnchanged(oldSource, state.value, 2))
        }
    }

    @Test fun `compaction waits for applied and persisted result even with buffered flowOn`() = runBlocking {
        withTimeout(5000) {
            val state = MutableStateFlow(source())
            val disk = MutableStateFlow(state.value)
            val result = completed(state.value)
            val snapshot = CompletableDeferred<Conversation>()
            val applied = CompletableDeferred<Unit>()
            val allowSave = CompletableDeferred<Unit>()
            val job = launch {
                flow<GenerationChunk> {
                    emitToolResultCheckpoint(result, awaitPersistence = true)
                    snapshot.complete(state.value)
                }.buffer(64).flowOn(Dispatchers.IO).collect { chunk ->
                    (chunk as GenerationChunk.Messages).applyAndAcknowledge {
                        state.value = state.value.updateCurrentMessages(chunk.messages)
                        applied.complete(Unit)
                        allowSave.await()
                        disk.value = state.value
                    }
                }
            }
            applied.await()
            assertFalse(snapshot.isCompleted)
            assertFalse(ContextCompactionPresentation.sourcePrefixUnchanged(disk.value, state.value, 2))
            allowSave.complete(Unit)
            val actualSource = snapshot.await()
            job.join()
            assertEquals(result, disk.value.currentMessages)
            val card = ContextCompactionPresentation.startTool(true, 100, 100, 200_000, 20_000)
            val duringSaving = ContextCompactionPresentation.attachToMessage(state.value, result.last().id, card)
            assertTrue(ContextCompactionPresentation.sourcePrefixUnchanged(actualSource, duringSaving, 2))
        }
    }

    @Test fun `persistence failure never starts compaction or reports an acknowledged result`() = runBlocking {
        withTimeout(5000) {
            val reachedCompaction = CompletableDeferred<Unit>()
            val failure = runCatching {
                flow<GenerationChunk> {
                    emitToolResultCheckpoint(completed(source()), awaitPersistence = true)
                    reachedCompaction.complete(Unit)
                }.buffer(64).flowOn(Dispatchers.IO).collect { chunk ->
                    (chunk as GenerationChunk.Messages).applyAndAcknowledge { throw IOException("disk full") }
                }
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertFalse(reachedCompaction.isCompleted)
        }
    }

    @Test fun `cancellation while awaiting persistence releases the flow without continuing`() = runBlocking {
        withTimeout(5000) {
            val entered = CompletableDeferred<Unit>()
            val continued = CompletableDeferred<Unit>()
            val job = launch {
                flow<GenerationChunk> {
                    emitToolResultCheckpoint(completed(source()), awaitPersistence = true)
                    continued.complete(Unit)
                }.buffer(64).flowOn(Dispatchers.IO).collect { chunk ->
                    (chunk as GenerationChunk.Messages).applyAndAcknowledge {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            }
            entered.await()
            job.cancelAndJoin()
            assertFalse(continued.isCompleted)
        }
    }

    @Test fun `execution itself waits for its started breadcrumb to reach storage`() = runBlocking {
        withTimeout(5000) {
            val saved = CompletableDeferred<List<UIMessage>>()
            val execute = CompletableDeferred<Unit>()
            flow<GenerationChunk> {
                val marked = source().currentMessages
                emitToolResultCheckpoint(marked, awaitPersistence = true)
                assertEquals(marked, saved.await())
                execute.complete(Unit)
            }.buffer(64).flowOn(Dispatchers.IO).collect { chunk ->
                (chunk as GenerationChunk.Messages).applyAndAcknowledge {
                    assertFalse(execute.isCompleted)
                    saved.complete(chunk.messages)
                }
            }
            assertTrue(execute.isCompleted)
        }
    }
}
