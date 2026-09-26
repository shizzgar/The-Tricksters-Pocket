package me.rerere.rikkahub.ui.pages.extensions.skills

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.files.*
import me.rerere.rikkahub.skills.*
import org.koin.compose.koinInject
import java.text.DateFormat
import java.util.Date

private data class PackageReadiness(val source: String, val version: String?, val revision: String,
    val missingTools: List<String>, val missingSkills: List<String>, val environment: List<String>,
    val connected: Boolean, val model: Boolean, val history: List<SkillTestRecord>, val declaredTools: List<String>, val declaredSkills: List<String>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkillPackageInfo(name: String, onDismiss: () -> Unit) {
    val manager = koinInject<SkillManager>()
    val store = koinInject<SettingsStore>()
    val factory = koinInject<ChatToolFactory>()
    val settings by store.settingsFlow.collectAsStateWithLifecycle()
    val assistant = settings.getCurrentAssistant()
    var info by remember(name) { mutableStateOf<PackageReadiness?>(null) }
    var error by remember(name) { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(name, settings) {
        withContext(Dispatchers.IO) {
            runCatching {
                val root = requireNotNull(manager.getSkillDir(name))
                val content = requireNotNull(manager.readSkillContent(name))
                val requirements = SkillRequirements.parse(content)
                val meta = SkillFrontmatterParser.parse(content)
                val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
                val tools = model?.let { factory.createTools(settings, assistant, it).map { tool -> tool.name }.toSet() }.orEmpty()
                val origin = runCatching { Json.decodeFromString(SkillOrigin.serializer(), root.resolve(".pocket-origin.json").readText()).source }.getOrNull()
                PackageReadiness(origin ?: meta["source-url"] ?: if (root.resolve(".seeded").exists()) "bundled:$name" else "local",
                    meta.scalar("version"), manager.workspace(name).snapshot().revision,
                    requirements.missingTools(tools), requirements.skills.filterNot { needed -> needed in assistant.enabledSkills && manager.listSkills().any { it.name == needed } },
                    requirements.environment, name in assistant.enabledSkills, model != null, manager.testHistory(name).read(),
                    requirements.tools + if (requirements.anyTools.isEmpty()) emptyList() else listOf(requirements.anyTools.joinToString(" / ")), requirements.skills)
            }.onSuccess { info = it; error = null }.onFailure { error = it.message }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.85f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.pocket_skill_package_info), style = MaterialTheme.typography.titleLarge) }
            item { Text(stringResource(R.string.pocket_skill_assistant, assistant.name), style = MaterialTheme.typography.titleMedium) }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (info == null && error == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            info?.let { data ->
                item {
                    val missing = !data.model || !data.connected || data.missingTools.isNotEmpty() || data.missingSkills.isNotEmpty()
                    Text(stringResource(if (missing) R.string.pocket_skill_not_ready else if (data.environment.isNotEmpty()) R.string.pocket_skill_env_pending else R.string.pocket_skill_config_ready), color = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                if (data.declaredTools.isNotEmpty()) item { Text(stringResource(R.string.pocket_skill_required_tools, data.declaredTools.joinToString(", ")), style = MaterialTheme.typography.bodySmall) }
                if (data.declaredSkills.isNotEmpty()) item { Text(stringResource(R.string.pocket_skill_required_skills, data.declaredSkills.joinToString(", ")), style = MaterialTheme.typography.bodySmall) }
                if (!data.model) item { Text(stringResource(R.string.pocket_skill_need_model)) }
                if (!data.connected) item { Text(stringResource(R.string.pocket_skill_not_connected)) }
                if (data.missingTools.isNotEmpty()) item { Text(stringResource(R.string.pocket_skill_missing_tools, data.missingTools.joinToString(", "))) }
                if (data.missingSkills.isNotEmpty()) item { Text(stringResource(R.string.pocket_skill_missing_skills, data.missingSkills.joinToString(", "))) }
                if (data.environment.isNotEmpty()) item { Text(stringResource(R.string.pocket_skill_environment, data.environment.joinToString(", "))) }
                item { Text(stringResource(R.string.pocket_skill_readiness_hint), style = MaterialTheme.typography.bodySmall) }
                item { HorizontalDivider() }
                item { SelectionContainer { Text(stringResource(R.string.pocket_skill_source, data.source), style = MaterialTheme.typography.bodySmall) } }
                data.version?.let { item { Text(stringResource(R.string.pocket_skill_version, it)) } }
                item { SelectionContainer { Text(stringResource(R.string.pocket_skill_revision, data.revision), style = MaterialTheme.typography.bodySmall) } }
                item { Text(stringResource(R.string.pocket_skill_tests), style = MaterialTheme.typography.titleMedium) }
                item { Text(stringResource(R.string.pocket_skill_test_disclaimer), style = MaterialTheme.typography.bodySmall) }
                if (data.history.isEmpty()) item { Text(stringResource(R.string.pocket_skill_no_tests)) }
                items(data.history.asReversed()) { record ->
                    OutlinedCard(onClick = { expanded = if (expanded == record.timestamp) null else record.timestamp }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(DateFormat.getDateTimeInstance().format(Date(record.timestamp)), style = MaterialTheme.typography.labelMedium)
                            Text(record.outcome, style = MaterialTheme.typography.labelLarge)
                            Text(stringResource(if (record.revision == data.revision) R.string.pocket_skill_test_current else R.string.pocket_skill_test_outdated), color = MaterialTheme.colorScheme.primary)
                            if (expanded == record.timestamp) SelectionContainer { Text("${record.assistantId}\n${record.revision}\n\n${record.prompt}\n\n${record.output}", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}
