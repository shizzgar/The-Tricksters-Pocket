package me.rerere.rikkahub.ui.pages.projects

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MemoryLedgerPage(memoryScope: String) {
    val repository: MemoryRepository = koinInject()
    val memories by remember(memoryScope) { repository.ledger(memoryScope) }.collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf<AssistantMemory?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val nav = LocalNavController.current
    fun mutate(action: suspend () -> Unit) { coroutineScope.launch { try { action(); error = null } catch (e: Exception) { error = e.message } } }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.pocket_memory)) }, navigationIcon = { BackButton() }, actions = { TextButton(onClick = { editing = AssistantMemory(0, scope = memoryScope) }) { Text(stringResource(R.string.pocket_new)) } }) }) { padding ->
        LazyColumn(contentPadding = padding + PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.pocket_memory_help), style = MaterialTheme.typography.bodySmall) }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            items(memories, key = { it.id }) { memory ->
                var history by remember(memory.id) { mutableStateOf(false) }
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (memory.deleted) Text(stringResource(R.string.pocket_memory_deleted), color = MaterialTheme.colorScheme.error)
                        Text(memory.content)
                        Text(stringResource(R.string.pocket_memory_revision, memory.revision), style = MaterialTheme.typography.labelSmall)
                        Text(if (memory.updatedAt == 0L) stringResource(R.string.pocket_memory_legacy) else DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(memory.updatedAt)), style = MaterialTheme.typography.bodySmall)
                        memory.sourceConversationId?.let { chat ->
                            TextButton(onClick = { coroutineScope.launch {
                                val conversations: me.rerere.rikkahub.data.repository.ConversationRepository = org.koin.core.context.GlobalContext.get().get()
                                val conversation = runCatching { conversations.getConversationById(kotlin.uuid.Uuid.parse(chat)) }.getOrNull()
                                if (conversation == null) {
                                    error = "Source conversation is no longer available"
                                } else {
                                    val node = memory.sourceMessageId?.let { id -> runCatching { conversation.getMessageNodeByMessageId(kotlin.uuid.Uuid.parse(id)) }.getOrNull() }
                                    nav.navigate(Screen.Chat(chat, nodeId = node?.id?.toString()))
                                }
                            } }) { Text(stringResource(R.string.pocket_memory_source)) }
                        }
                        FlowRow {
                            if (!memory.deleted) {
                                TextButton(onClick = { editing = memory }) { Text(stringResource(R.string.pocket_edit)) }
                                TextButton(onClick = { mutate { repository.deleteMemory(memory.id, memory.revision) } }) { Text(stringResource(R.string.pocket_memory_forget)) }
                            }
                            if (memory.history.isNotEmpty()) TextButton(onClick = { history = !history }) { Text(stringResource(R.string.pocket_memory_history)) }
                        }
                        if (history) memory.history.asReversed().forEach { revision ->
                            HorizontalDivider()
                            Text("r${revision.revision} · ${revision.content}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { mutate { repository.restore(memory.id, revision.revision, memory.revision) } }) { Text(stringResource(R.string.pocket_memory_restore)) }
                        }
                    }
                }
            }
        }
    }
    editing?.let { original ->
        var content by remember(original.id) { mutableStateOf(original.content) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.pocket_memory)) }, text = { Column { OutlinedTextField(content, { content = it }, minLines = 4); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { TextButton(enabled = content.isNotBlank(), onClick = { mutate {
            if (original.id == 0) repository.addMemory(memoryScope, content) else repository.updateContent(original.id, content, original.revision)
            editing = null
        } }) { Text(stringResource(R.string.chat_page_save)) } }, dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
