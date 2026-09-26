package me.rerere.rikkahub.ui.pages.share.handler

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ShareHandlerPage(text: String, streams: List<String>) {
    val vm: ShareHandlerVM = koinViewModel(parameters = { parametersOf(text, streams) })
    val settings by vm.settings.collectAsStateWithLifecycle()
    val files by vm.files.collectAsStateWithLifecycle()
    val failures by vm.failures.collectAsStateWithLifecycle()
    val importing by vm.isImporting.collectAsStateWithLifecycle()
    val recent by vm.recentChats.collectAsStateWithLifecycle()
    val projects by vm.projectRepository.projects.collectAsStateWithLifecycle()
    var projectId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var opening by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val nav = LocalNavController.current
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.share_handler_page_title)) }, navigationIcon = { BackButton() }) }) { padding ->
        LazyColumn(contentPadding = padding + PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (text.isNotBlank()) Text(text, maxLines = 5, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.pocket_share_files, files.size, streams.size))
                        files.forEach { Text(it.lastPathSegment.orEmpty(), style = MaterialTheme.typography.bodySmall) }
                        if (importing || opening) LinearProgressIndicator(Modifier.fillMaxWidth())
                        failures.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            if (projects.isNotEmpty()) item {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { expanded = true }) { Text(projects.firstOrNull { it.id == projectId }?.name ?: stringResource(R.string.pocket_no_project)) }
                    DropdownMenu(expanded, { expanded = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.pocket_no_project)) }, onClick = { projectId = null; expanded = false })
                        projects.forEach { project -> DropdownMenuItem(text = { Text(project.name) }, onClick = { projectId = project.id; expanded = false }) }
                    }
                }
            }
            item { Text(stringResource(R.string.pocket_share_new), style = MaterialTheme.typography.titleMedium) }
            items(settings.assistants, key = { "assistant:${it.id}" }) { assistant ->
                Surface(onClick = {
                    scope.launch {
                        opening = true
                        try {
                            val id = vm.newChat(assistant.id, projectId)
                            navigateToChatPage(nav, chatId = id, initText = text.base64Encode(), initFiles = files)
                        } catch (failure: Exception) { error = failure.message } finally { opening = false }
                    }
                }, enabled = !importing && !opening, shape = MaterialTheme.shapes.medium, tonalElevation = 3.dp) {
                    ListItem(headlineContent = { Text(assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }) })
                }
            }
            item { Text(stringResource(R.string.pocket_share_existing), style = MaterialTheme.typography.titleMedium) }
            items(recent.sortedByDescending { it.updateAt }.take(40), key = { "chat:${it.id}" }) { chat ->
                Surface(onClick = { navigateToChatPage(nav, chatId = chat.id, initText = text.base64Encode(), initFiles = files) }, enabled = !importing && !opening, shape = MaterialTheme.shapes.medium) {
                    ListItem(headlineContent = { Text(chat.title.ifBlank { stringResource(R.string.search_page_untitled) }) }, supportingContent = { Text(settings.assistants.firstOrNull { it.id == chat.assistantId }?.name.orEmpty()) })
                }
            }
        }
    }
}
