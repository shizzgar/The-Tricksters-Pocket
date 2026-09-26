package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.localFileUrls
import kotlin.uuid.Uuid

data class QueuedMessage(
    val id: Uuid = Uuid.random(),
    val parts: List<UIMessagePart>,
    val answer: Boolean = true,
    val isEditing: Boolean = false,
    val steerActiveTask: Boolean = false,
    val isApplying: Boolean = false,
    // Optional in-memory observer; null result means the queued message was withdrawn.
    val reply: CompletableDeferred<String?>? = null,
)

data class MessageQueueState(
    val messages: List<QueuedMessage> = emptyList(),
    val paused: Boolean = false,
)

internal fun unreferencedQueuedAttachmentUrls(
    previous: QueuedMessage,
    conversations: List<Conversation>,
    pendingMessages: List<QueuedMessage>,
): Set<String> {
    val retainedParts = conversations.flatMap { conversation ->
        conversation.messageNodes.flatMap { node -> node.messages.flatMap { it.parts } }
    } + pendingMessages.flatMap { it.parts }
    return previous.parts.localFileUrls() - retainedParts.localFileUrls()
}

/** Pending input is kept outside conversation history until dispatched. */
class MessageQueuePausedException : IllegalStateException()

class MessageQueue {
    private val mutableState = MutableStateFlow(MessageQueueState())
    val state = mutableState.asStateFlow()

    @Synchronized
    fun enqueue(parts: List<UIMessagePart>, answer: Boolean = true, reply: CompletableDeferred<String?>? = null, steerActiveTask: Boolean = false, id: Uuid = Uuid.random()) {
        if (parts.isEmptyInputMessage()) {
            reply?.complete(null)
            return
        }
        if (state.value.messages.any { it.id == id }) return
        mutableState.value = state.value.copy(
            messages = state.value.messages + QueuedMessage(
                id = id,
                parts = parts.toList(),
                answer = answer,
                reply = reply,
                steerActiveTask = steerActiveTask && answer && reply == null,
            ),
        )
    }

    @Synchronized
    fun takeNext(): QueuedMessage? {
        val current = state.value
        if (current.paused) return null
        val next = current.messages.firstOrNull()?.takeUnless { it.isEditing || it.isApplying } ?: return null
        mutableState.value = current.copy(messages = current.messages.drop(1))
        return next
    }

    /** Reserve a ready prefix until its messages are saved. Edits and Stop cannot lose input. */
    @Synchronized
    fun claimSteering(): List<QueuedMessage> {
        val current = state.value
        if (current.paused) return emptyList()
        val ready = current.messages.takeWhile { it.steerActiveTask && !it.isEditing && !it.isApplying }
        val ids = ready.map { it.id }.toSet()
        mutableState.value = current.copy(messages = current.messages.map {
            if (it.id in ids) it.copy(isApplying = true) else it
        })
        return ready
    }

    @Synchronized
    fun finishSteering(ids: Set<Uuid>, accepted: Boolean) {
        mutableState.value = state.value.copy(messages = state.value.messages.mapNotNull {
            if (it.id !in ids || !it.isApplying) it
            else if (accepted) null else it.copy(isApplying = false)
        })
    }

    @Synchronized
    fun remove(id: Uuid): QueuedMessage? {
        val removed = state.value.messages.find { it.id == id && !it.isApplying } ?: return null
        mutableState.value =
            state.value.copy(messages = state.value.messages.filterNot { it.id == id })
        removed.reply?.complete(null)
        return removed
    }

    @Synchronized
    fun beginEdit(id: Uuid): QueuedMessage? {
        val message = state.value.messages.find { it.id == id && !it.isEditing && !it.isApplying } ?: return null
        mutableState.value = state.value.copy(
            messages = state.value.messages.map { if (it.id == id) it.copy(isEditing = true) else it },
        )
        return message
    }

    @Synchronized
    fun finishEdit(id: Uuid, parts: List<UIMessagePart>? = null): QueuedMessage? {
        if (parts != null && parts.isEmptyInputMessage()) return null
        val previous = state.value.messages.find { it.id == id && !it.isApplying } ?: return null
        mutableState.value = state.value.copy(
            messages = state.value.messages.map {
                if (it.id == id) it.copy(
                    parts = parts?.toList() ?: it.parts,
                    isEditing = false
                ) else it
            },
        )
        // 取消编辑只释放占位，不能清理原附件。
        return previous.takeIf { parts != null }
    }

    @Synchronized
    fun pause() {
        mutableState.value = state.value.copy(paused = true)
        state.value.messages.forEach { it.reply?.completeExceptionally(MessageQueuePausedException()) }
    }

    fun failReplyWaiters(message: String) {
        state.value.messages.forEach {
            it.reply?.completeExceptionally(IllegalStateException(message))
        }
    }

    @Synchronized
    fun resume() {
        mutableState.value = state.value.copy(paused = false)
    }
}
