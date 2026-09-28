package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.ChatEnvironmentSnapshot
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ProjectRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.WorkspaceSource
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.chat.ChatVM
import org.koin.compose.koinInject

@Composable
internal fun ChatEnvironmentSheet(vm: ChatVM, settings: Settings, conversation: Conversation, onDismiss: () -> Unit) {
    val projects: ProjectRepository = koinInject()
    val workspaces: WorkspaceRepository = koinInject()
    val projectState by projects.projects.collectAsStateWithLifecycle()
    val workspaceState by workspaces.listFlow().collectAsStateWithLifecycle(emptyList())
    val mcpState by vm.mcpManager.syncingStatus.collectAsStateWithLifecycle()
    var refresh by remember { mutableStateOf(0) }
    val snapshot by produceState<Result<ChatEnvironmentSnapshot>?>(null,
        settings, conversation.id, conversation.workspaceCwd, conversation.chatModelId, projectState, workspaceState, mcpState, refresh,
    ) {
        value = null
        value = withContext(Dispatchers.IO) {
            try { Result.success(vm.inspectEnvironment()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { Result.failure(failure) }
        }
    }
    val navController = LocalNavController.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.chat_environment_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.crew_configuration_note), style = MaterialTheme.typography.bodySmall)
            when {
                snapshot == null -> CircularProgressIndicator()
                snapshot?.isFailure == true -> Text(
                    stringResource(R.string.chat_environment_error, snapshot?.exceptionOrNull()?.message.orEmpty()),
                    color = MaterialTheme.colorScheme.error,
                )
                else -> ChatEnvironmentDetails(snapshot!!.getOrThrow())
            }
            TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.chat_environment_refresh)) }
            TextButton(onClick = { onDismiss(); navController.navigate(Screen.SettingDoctor) }) {
                Text(stringResource(R.string.crew_open_doctor))
            }
        }
    }
}

@Composable
internal fun ChatEnvironmentDetails(snapshot: ChatEnvironmentSnapshot) {
    val context = LocalContext.current
    val readiness = snapshot.readiness
    val environment = snapshot.environment
    Text(readiness.summary(context), style = MaterialTheme.typography.bodyMedium)
    if (!readiness.configured) Text(readiness.issueText(context), color = MaterialTheme.colorScheme.error)
    val source = stringResource(when (environment.workspaceSource) {
        WorkspaceSource.NONE -> R.string.chat_environment_source_none
        WorkspaceSource.SCOPED_POLICY -> R.string.chat_environment_source_scope
        WorkspaceSource.PROJECT -> R.string.chat_environment_source_project
        WorkspaceSource.ASSISTANT -> R.string.chat_environment_source_assistant
        WorkspaceSource.PARENT_ASSISTANT -> R.string.chat_environment_source_parent
    })
    Text(stringResource(R.string.chat_environment_source, source))
    environment.project?.let { Text(stringResource(R.string.chat_environment_project, it.name)) }
    snapshot.workingDirectory?.let { cwd ->
        SelectionContainer { Text(stringResource(R.string.chat_environment_cwd, cwd), style = MaterialTheme.typography.bodySmall) }
    }
    HorizontalDivider()
    EnvironmentNames(stringResource(R.string.chat_environment_tools, snapshot.availableTools.size), snapshot.availableTools)
    if (snapshot.excludedTools.isNotEmpty()) EnvironmentNames(
        stringResource(R.string.chat_environment_excluded), snapshot.excludedTools,
    )
    if (snapshot.policyBlockedTools.isNotEmpty()) EnvironmentNames(
        stringResource(R.string.chat_environment_policy), snapshot.policyBlockedTools,
    )
    EnvironmentNames(stringResource(R.string.chat_environment_skills, snapshot.enabledSkills.size), snapshot.enabledSkills)
    if (snapshot.enabledSkills.isNotEmpty() && !snapshot.skillPromptAvailable) Text(
        stringResource(R.string.chat_environment_skills_blocked), style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun EnvironmentNames(title: String, names: List<String>) {
    var expanded by remember(title) { mutableStateOf(false) }
    if (names.isEmpty()) {
        Text(title, style = MaterialTheme.typography.titleSmall)
    } else {
        TextButton(onClick = { expanded = !expanded }) {
            Text(title + " · " + stringResource(if (expanded) R.string.chat_environment_hide else R.string.chat_environment_show))
        }
        if (expanded) SelectionContainer {
            Text(names.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
        }
    }
}
