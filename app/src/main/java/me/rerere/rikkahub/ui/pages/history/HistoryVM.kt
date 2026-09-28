package me.rerere.rikkahub.ui.pages.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

private const val TAG = "HistoryVM"

class HistoryVM(
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val pendingDeletionIds = MutableStateFlow<Set<Uuid>>(emptySet())
    val assistant = settingsStore.settingsFlow
        .map { it.getCurrentAssistant() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val conversations = assistant.flatMapLatest { assistant ->
        conversationRepo.getConversationsOfAssistant(assistant?.id ?: Uuid.random())
    }.catch {
        Log.e(TAG, "Error: ${it.message}")
    }.combine(pendingDeletionIds) { rows, hidden -> rows.filterNot { it.id in hidden } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The snackbar hides the row; irreversible cleanup starts only after Undo expires. */
    suspend fun deleteConversation(conversation: Conversation, awaitUndo: suspend () -> Boolean) {
        if (conversation.id in pendingDeletionIds.value) return
        pendingDeletionIds.update { it + conversation.id }
        var undo = false
        try {
            undo = awaitUndo()
        } finally {
            // Navigating away dismisses the snackbar and completes deletion. A process
            // death before this point leaves the intact original chat, never a writable
            // recreation stripped of its review policy or task/hook metadata.
            withContext(NonCancellable) {
                try {
                    if (!undo) conversationRepo.deleteConversation(conversation)
                } finally {
                    pendingDeletionIds.update { it - conversation.id }
                }
            }
        }
    }

    fun deleteAllConversations() {
        val assistant = assistant.value ?: return
        viewModelScope.launch {
            conversationRepo.deleteConversationOfAssistant(assistant.id)
        }
    }

    fun togglePinStatus(conversationId: Uuid) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversationId)
        }
    }

    fun getPinnedConversations(): Flow<List<Conversation>> =
        conversationRepo.getPinnedConversations()

}
