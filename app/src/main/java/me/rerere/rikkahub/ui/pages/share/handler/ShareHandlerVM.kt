package me.rerere.rikkahub.ui.pages.share.handler

import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.ProjectRepository
import kotlin.uuid.Uuid

class ShareHandlerVM(
    text: String,
    streams: List<String>,
    private val settingsStore: SettingsStore,
    private val filesManager: FilesManager,
    private val conversations: ConversationRepository,
    val projectRepository: ProjectRepository,
) : ViewModel() {
    val shareText = text
    val settings = settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())
    val recentChats = conversations.searchConversations("").stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val imported = MutableStateFlow<List<Uri>>(emptyList())
    val files = imported.asStateFlow()
    private val importFailures = MutableStateFlow<List<String>>(emptyList())
    val failures = importFailures.asStateFlow()
    private val importing = MutableStateFlow(streams.isNotEmpty())
    val isImporting = importing.asStateFlow()

    init {
        // Copy immediately while the receiving Activity still owns the temporary URI grant.
        viewModelScope.launch {
            try {
                streams.distinct().forEach { stream ->
                    try {
                        val saved = filesManager.saveManagedFromUri(FileFolders.UPLOAD, stream.toUri())
                        imported.value += filesManager.getFile(saved).toUri()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        importFailures.value += "${stream.toUri().lastPathSegment}: ${error.message}"
                    }
                }
            } finally { importing.value = false }
        }
    }

    suspend fun newChat(assistantId: Uuid, projectId: String?): Uuid {
        val chat = Conversation(assistantId = assistantId, messageNodes = emptyList())
        conversations.insertConversation(chat)
        if (projectId != null) projectRepository.bindConversation(projectId, chat.id)
        return chat.id
    }
}
