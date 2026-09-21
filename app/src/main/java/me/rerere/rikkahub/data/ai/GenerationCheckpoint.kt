package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.FlowCollector
import me.rerere.ai.ui.UIMessage

/** flowOn is buffered: emit() does not mean the chat collector has applied or saved a result.
 * Await an explicit receipt before reading the conversation for compaction or the next round.
 * This is opt-in because non-chat consumers do not own a persistent conversation. */
internal suspend fun FlowCollector<GenerationChunk>.emitToolResultCheckpoint(
    messages: List<UIMessage>,
    awaitPersistence: Boolean,
) {
    val receipt = if (awaitPersistence) CompletableDeferred<Unit>() else null
    emit(GenerationChunk.Messages(messages, receipt))
    receipt?.await()
}

/** A failed/cancelled collector must never release the producer as if the result was saved. */
internal suspend fun GenerationChunk.Messages.applyAndAcknowledge(block: suspend () -> Unit) {
    try {
        block()
        persistenceReceipt?.complete(Unit)
    } catch (error: Throwable) {
        persistenceReceipt?.completeExceptionally(error)
        throw error
    }
}
