package me.rerere.rikkahub.ui.pages.extensions.skills

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import me.rerere.rikkahub.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.FilePen
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import me.rerere.rikkahub.skills.SkillTestRunner
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SkillDetailPage(skillName: String) {
    val vm = koinViewModel<SkillDetailVM>()
    val nav = me.rerere.rikkahub.ui.context.LocalNavController.current
    LaunchedEffect(skillName) { vm.init(skillName) }
    var tester by rememberSaveable { mutableStateOf(false) }
    SkillWorkbenchScreen(vm, onBack = { nav.popBackStack() }, onTest = { tester = true })
    if (tester) SkillTesterSheet(skillName) { tester = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillTesterSheet(skillName: String, onDismiss: () -> Unit) {
    val runner = koinInject<SkillTestRunner>()
    val manager = koinInject<me.rerere.rikkahub.data.files.SkillManager>()
    var previousPrompt by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(skillName) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { previousPrompt = manager.testHistory(skillName).read().lastOrNull()?.prompt } }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var prompt by rememberSaveable { mutableStateOf("") }
    val stateFlow = remember { MutableStateFlow<SkillTestRunner.TestRunState>(SkillTestRunner.TestRunState.Idle) }
    val state by stateFlow.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.skill_tester_title, skillName),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Lucide.X, contentDescription = stringResource(R.string.cancel))
                }
            }

            Text(stringResource(R.string.pocket_skill_test_disclaimer), style = MaterialTheme.typography.bodySmall)
            previousPrompt?.let { previous -> TextButton(onClick = { prompt = previous }) { Text(stringResource(R.string.pocket_skill_test_repeat)) } }
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text(stringResource(R.string.skill_tester_prompt_placeholder)) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            // Disable across the whole "in flight" window — from the moment the user
            // taps Run until the runner emits Done/Error — so a fast double-tap can't
            // spawn a second concurrent run before state transitions to Running.
            var isEnqueued by remember { mutableStateOf(false) }
            LaunchedEffect(state) {
                if (state is SkillTestRunner.TestRunState.Done
                    || state is SkillTestRunner.TestRunState.Error
                    || state is SkillTestRunner.TestRunState.Idle
                ) {
                    isEnqueued = false
                }
            }
            FilledTonalButton(
                onClick = {
                    val current = prompt
                    isEnqueued = true
                    scope.launch {
                        runner.runOnce(skillName, current).collect { stateFlow.value = it }
                    }
                },
                enabled = prompt.isNotBlank() && !isEnqueued
                    && state !is SkillTestRunner.TestRunState.Running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.skill_tester_run))
            }

            // Result area — exactly one of these is visible at a time.
            when (val s = state) {
                is SkillTestRunner.TestRunState.Idle -> Unit
                is SkillTestRunner.TestRunState.Running -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.skill_tester_running),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                is SkillTestRunner.TestRunState.Done -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = s.text.ifBlank { stringResource(R.string.skill_tester_done_no_text) },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (s.imageUrls.isNotEmpty()) {
                            Text(
                                text = "[${s.imageUrls.size} image part(s)]",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                is SkillTestRunner.TestRunState.Error -> {
                    val msg = if (s.error == "tester_timeout") {
                        stringResource(R.string.skill_tester_timeout_error)
                    } else {
                        "${s.error}${s.detail?.let { ": $it" }.orEmpty()}"
                    }
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
