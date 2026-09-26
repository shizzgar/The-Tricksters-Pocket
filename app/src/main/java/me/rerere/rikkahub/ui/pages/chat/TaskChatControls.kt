package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ProjectRepository
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.task.TaskArtifactStore
import me.rerere.rikkahub.data.task.TaskBrief
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

/** A task is an explicit user choice. Tool activity and child chats never pin this card. */
@Composable
internal fun TaskChatControls(conversation: Conversation, showEditor: Boolean, onDismissEditor: () -> Unit) {
    val context = LocalContext.current
    val projects = koinInject<ProjectRepository>()
    val repository = koinInject<ConversationRepository>()
    val service = koinInject<ChatService>()
    val navigator = LocalNavController.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { TaskArtifactStore.at(context.filesDir) }
    val revision by store.revision.collectAsState()
    var rootId by remember(conversation.id) { mutableStateOf(conversation.id) }
    var brief by remember(conversation.id) { mutableStateOf<TaskBrief?>(null) }
    var loadError by remember(conversation.id) { mutableStateOf<String?>(null) }
    var confirmClose by remember(conversation.id) { mutableStateOf(false) }
    var busy by remember(conversation.id) { mutableStateOf(false) }
    LaunchedEffect(conversation.id, conversation.parentConversationId, revision) {
        try {
            rootId = projects.taskRoot(conversation.id)
            brief = store.brief(rootId.toString())
            loadError = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { loadError = e.message ?: context.getString(R.string.task_load_error) }
    }
    fun report(e: Exception) {
        if (e is CancellationException) throw e
        toaster.show(e.message ?: context.getString(R.string.task_load_error), type = ToastType.Error)
    }
    brief?.takeIf { it.isActive }?.let { active ->
        TaskChatCard(
            brief = active,
            onOpen = { navigator.navigate(Screen.TaskDashboard(rootId.toString())) },
            onClose = { confirmClose = true },
        )
    }
    if (showEditor) {
        val initial = brief
        if (initial != null) TaskBriefDialog(
            initial = initial,
            busy = busy,
            onDismiss = onDismissEditor,
            onOpenResults = {
                onDismissEditor()
                navigator.navigate(Screen.TaskDashboard(rootId.toString()))
            },
            onSave = { goal, criteria ->
                scope.launch {
                    busy = true
                    try {
                        // New empty chats normally are not persisted; an explicit task gives this one a title.
                        if (!repository.existsConversationById(rootId)) {
                            check(rootId == conversation.id) { context.getString(R.string.task_missing) }
                            service.saveConversation(rootId, conversation.copy(title = conversation.title.ifBlank { goal.take(100) }))
                            check(repository.existsConversationById(rootId)) { context.getString(R.string.task_missing) }
                        }
                        store.saveBrief(rootId.toString(), initial.copy(goal = goal.trim(), acceptanceCriteria = criteria.trim(), active = true))
                        onDismissEditor()
                    } catch (e: Exception) { report(e) }
                    finally { busy = false }
                }
            },
        ) else AlertDialog(
            onDismissRequest = onDismissEditor,
            title = { Text(stringResource(R.string.task_dashboard)) },
            text = { if (loadError != null) Text(loadError!!, color = MaterialTheme.colorScheme.error) else LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = onDismissEditor) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmClose) AlertDialog(
        onDismissRequest = { if (!busy) confirmClose = false },
        title = { Text(stringResource(R.string.task_close)) },
        text = { Text(stringResource(R.string.task_close_hint)) },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirmClose = false }) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(modifier = Modifier.testTag("task-close-confirm"), enabled = !busy, onClick = { scope.launch {
            busy = true
            try { store.closeBrief(rootId.toString()); confirmClose = false }
            catch (e: Exception) { report(e) }
            finally { busy = false }
        } }) { Text(stringResource(R.string.task_close)) } },
    )
}

@Composable
internal fun TaskChatCard(brief: TaskBrief, onOpen: () -> Unit, onClose: () -> Unit) {
    if (!brief.isActive) return
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag("chat-task-card"),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.task_dashboard), style = MaterialTheme.typography.labelSmall)
                Text(brief.goal.ifBlank { brief.acceptanceCriteria }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onClose) { Icon(HugeIcons.Cancel01, stringResource(R.string.task_close)) }
        }
    }
}

@Composable
private fun TaskBriefDialog(initial: TaskBrief, busy: Boolean, onDismiss: () -> Unit, onOpenResults: () -> Unit, onSave: (String, String) -> Unit) {
    var goal by rememberSaveable { mutableStateOf(initial.goal) }
    var criteria by rememberSaveable { mutableStateOf(initial.acceptanceCriteria) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.task_define)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.task_define_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(goal, { goal = it }, label = { Text(stringResource(R.string.task_goal)) }, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("task-goal-input"), enabled = !busy)
                OutlinedTextField(criteria, { criteria = it }, label = { Text(stringResource(R.string.task_criteria)) }, minLines = 2, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                TextButton(enabled = !busy, onClick = onOpenResults) { Text(stringResource(R.string.task_open_results)) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(modifier = Modifier.testTag("task-save-button"), enabled = !busy && goal.isNotBlank(), onClick = { onSave(goal, criteria) }) { Text(stringResource(R.string.task_set)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
