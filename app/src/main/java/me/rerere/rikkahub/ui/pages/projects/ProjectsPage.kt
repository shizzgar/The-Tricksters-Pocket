package me.rerere.rikkahub.ui.pages.projects

import androidx.compose.foundation.layout.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import me.rerere.rikkahub.data.repository.*
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun ProjectsPage(conversationId: String? = null) {
    val repository: ProjectRepository = koinInject()
    val workspaceRepository: WorkspaceRepository = koinInject()
    val projects by repository.projects.collectAsStateWithLifecycle()
    val loadError by repository.loadError.collectAsStateWithLifecycle()
    val workspaces by workspaceRepository.listFlow().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val filesManager: me.rerere.rikkahub.data.files.FilesManager = koinInject()
    var attachingTo by remember { mutableStateOf<String?>(null) }
    val nav = LocalNavController.current
    var editing by remember { mutableStateOf<PocketProject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedProject by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(conversationId, projects) { selectedProject = conversationId?.let { repository.projectForConversation(Uuid.parse(it))?.id } }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val id = attachingTo ?: return@rememberLauncherForActivityResult
        scope.launch { try {
            uris.forEach { uri ->
                val file = filesManager.saveManagedFromUri(me.rerere.rikkahub.data.files.FileFolders.UPLOAD, uri)
                repository.addFile(id, ProjectReferenceFile(file.displayName, file.relativePath, file.mimeType))
            }
        } catch (e: Exception) { error = e.message } }
    }
    fun save(project: PocketProject) { scope.launch { try { repository.save(project); editing = null } catch (e: Exception) { error = e.message } } }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.pocket_projects)) }, navigationIcon = { BackButton() }, actions = { TextButton(onClick = { editing = PocketProject(name = "") }) { Text(stringResource(R.string.pocket_new)) } }) }) { padding ->
        LazyColumn(contentPadding = padding + PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.pocket_project_help), style = MaterialTheme.typography.bodyMedium) }
            (error ?: loadError)?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (conversationId != null) item {
                TextButton(onClick = { scope.launch { repository.bindConversation(null, Uuid.parse(conversationId)) } }, enabled = selectedProject != null) { Text(stringResource(R.string.pocket_project_unlink)) }
            }
            items(projects, key = { it.id }) { project ->
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(project.name, style = MaterialTheme.typography.titleMedium)
                        Text(workspaces.firstOrNull { it.id == project.workspaceId }?.name ?: stringResource(R.string.pocket_project_no_workspace), style = MaterialTheme.typography.bodySmall)
                        if (project.instructions.isNotBlank()) Text(project.instructions, maxLines = 3)
                        if (project.knowledge.isNotBlank()) Text(project.knowledge, maxLines = 3, style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editing = project }) { Text(stringResource(R.string.pocket_edit)) }
                            if (conversationId != null) TextButton(onClick = { scope.launch { repository.bindConversation(project.id, Uuid.parse(conversationId)) } }, enabled = selectedProject != project.id) { Text(stringResource(if (selectedProject == project.id) R.string.pocket_project_linked else R.string.pocket_project_link)) }
                            TextButton(onClick = { nav.navigate(Screen.ProjectMemory(project.id)) }) { Text(stringResource(R.string.pocket_memory)) }
                        }
                        TextButton(onClick = { attachingTo = project.id; pickFiles.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.pocket_project_add_files)) }
                        project.files.forEach { file -> Text(file.name, style = MaterialTheme.typography.bodySmall) }
                        project.conversationIds.forEach { id ->
                            ProjectChatLink(id)
                        }
                    }
                }
            }
        }
    }
    editing?.let { project ->
        var name by remember(project.id) { mutableStateOf(project.name) }
        var instructions by remember(project.id) { mutableStateOf(project.instructions) }
        var knowledge by remember(project.id) { mutableStateOf(project.knowledge) }
        var workspace by remember(project.id) { mutableStateOf(project.workspaceId) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.pocket_project_edit)) }, text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.pocket_name)) }, singleLine = true) }
                item {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { expanded = true }) { Text(workspaces.firstOrNull { it.id == workspace }?.name ?: stringResource(R.string.pocket_project_no_workspace)) }
                        DropdownMenu(expanded, { expanded = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.pocket_project_no_workspace)) }, onClick = { workspace = null; expanded = false })
                            workspaces.forEach { item -> DropdownMenuItem(text = { Text(item.name) }, onClick = { workspace = item.id; expanded = false }) }
                        }
                    }
                }
                item { OutlinedTextField(instructions, { instructions = it }, label = { Text(stringResource(R.string.pocket_instructions)) }, minLines = 3) }
                item { OutlinedTextField(knowledge, { knowledge = it }, label = { Text(stringResource(R.string.pocket_knowledge)) }, minLines = 3) }
            }
        }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { save(project.copy(name = name.trim(), workspaceId = workspace, instructions = instructions, knowledge = knowledge)) }) { Text(stringResource(R.string.chat_page_save)) } }, dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun ProjectChatLink(id: String) {
    val conversations: ConversationRepository = koinInject()
    val nav = LocalNavController.current
    var title by remember(id) { mutableStateOf(id) }
    LaunchedEffect(id) { title = conversations.getConversationById(Uuid.parse(id))?.title?.ifBlank { id } ?: id }
    TextButton(onClick = { nav.navigate(Screen.Chat(id)) }) { Text(title, maxLines = 1) }
}
