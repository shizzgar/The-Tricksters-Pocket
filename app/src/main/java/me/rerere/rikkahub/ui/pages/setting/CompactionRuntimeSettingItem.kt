package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.CompactionRuntimeLimits
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.compactionRuntimeLimits
import me.rerere.rikkahub.ui.components.ui.CardGroup

/** Intentionally outside the auto-compaction switch: manual compression uses the same limits. */
@Composable
internal fun CompactionRuntimeSettingItem(settings: Settings, vm: SettingVM) {
    val limits = settings.compactionRuntimeLimits()
    val requestMinutes = (limits.requestTimeoutMs / 60_000).toInt()
    val totalMinutes = (limits.totalTimeoutMs / 60_000).toInt()
    CardGroup {
        item(
            headlineContent = { Text(stringResource(R.string.compaction_runtime_title)) },
            supportingContent = { Text(stringResource(R.string.compaction_runtime_description)) },
        )
        item(headlineContent = {
            CompactionIntegerField(
                label = stringResource(R.string.compaction_request_timeout),
                description = stringResource(R.string.compaction_request_timeout_description),
                value = requestMinutes,
                range = 1..CompactionRuntimeLimits.MAX_REQUEST_MINUTES,
                onChange = { minutes ->
                    vm.updateSettings { current -> current.copy(
                        compactionRequestTimeoutMinutes = minutes,
                        compactionTotalTimeoutMinutes = maxOf(current.compactionTotalTimeoutMinutes, minutes),
                    ) }
                },
            )
        })
        item(headlineContent = {
            CompactionIntegerField(
                label = stringResource(R.string.compaction_total_timeout),
                description = stringResource(R.string.compaction_total_timeout_description),
                value = totalMinutes,
                range = requestMinutes..CompactionRuntimeLimits.MAX_TOTAL_MINUTES,
                onChange = { minutes ->
                    vm.updateSettings { current -> current.copy(compactionTotalTimeoutMinutes = minutes) }
                },
            )
        })
        item(headlineContent = {
            CompactionIntegerField(
                label = stringResource(R.string.compaction_parallel_requests),
                description = stringResource(R.string.compaction_parallel_requests_description),
                value = limits.parallelRequests,
                range = 1..CompactionRuntimeLimits.MAX_PARALLEL_REQUESTS,
                onChange = { count ->
                    vm.updateSettings { current -> current.copy(compactionParallelRequests = count) }
                },
            )
        })
    }
}

@Composable
internal fun CompactionIntegerField(
    label: String,
    description: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter(Char::isDigit).take(3)
            text.toIntOrNull()?.takeIf { it in range && it != value }?.let(onChange)
        },
        label = { Text(label) },
        supportingText = { Text(description) },
        isError = text.isNotEmpty() && text.toIntOrNull()?.let { it in range } != true,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
            if (!state.isFocused) {
                val normalized = text.toIntOrNull()?.coerceIn(range) ?: value
                text = normalized.toString()
                if (normalized != value) onChange(normalized)
            }
        },
    )
}
