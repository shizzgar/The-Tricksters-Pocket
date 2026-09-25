package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.subagent.SubAgentRegistry
import me.rerere.rikkahub.ui.components.message.tools.childRunStatuses
import me.rerere.rikkahub.ui.components.message.tools.ChildChatButton
import me.rerere.rikkahub.ui.components.message.tools.ChildChatCard
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.compose.koinInject

@Composable
internal fun SubAgentChatBar(conversation: Conversation) {
    val repository = koinInject<ConversationRepository>()
    val navigator = LocalNavController.current
    val children by remember(conversation.id) { repository.observeChildConversations(conversation.id) }.collectAsState(emptyList())
    val statuses = childRunStatuses()
    val parent by produceState<Conversation?>(null, conversation.parentConversationId) {
        value = conversation.parentConversationId?.let { repository.getConversationById(it) }
    }
    var showChildren by remember(conversation.id) { mutableStateOf(false) }
    if (conversation.parentConversationId != null || children.isNotEmpty()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                if (conversation.parentConversationId != null) {
                    TextButton(enabled = parent != null, modifier = Modifier.weight(1f), onClick = {
                        parent?.let { target ->
                            navigator.returnToChat(target.id.toString())
                        }
                    }) { Text(if (parent == null) stringResource(R.string.pocket_chat_missing) else stringResource(R.string.pocket_parent_chat, parent!!.title), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (children.isNotEmpty()) TextButton(onClick = { showChildren = true }) {
                    val active = children.count { statuses[it.subAgentRunId]?.uppercase() in setOf("PENDING", "QUEUED", "RUNNING") }
                    Text(stringResource(R.string.pocket_child_count, children.size, active))
                }
            }
        }
    }
    if (showChildren) ModalBottomSheet(onDismissRequest = { showChildren = false }) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.75f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.pocket_child_chats), style = MaterialTheme.typography.titleLarge) }
            item { Text(stringResource(R.string.pocket_child_chat_hint)) }
            items(children, key = { it.id.toString() }) { child ->
                ChildChatCard(child, statuses[child.subAgentRunId], LocalSettings.current.getAssistantById(child.assistantId)) { ChildChatButton(child) { showChildren = false } }
            }
        }
    }
}
