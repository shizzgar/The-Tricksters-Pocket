package me.rerere.rikkahub.ui.pages.setting.termux

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.CardGroup

/** One editor for one shared preferences store; workspace cwd and approvals stay local. */
@Composable
internal fun TermuxRuntimeSettings(vm: SettingTermuxViewModel, workspaceDirectory: String? = null) {
    val config by vm.config.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.termux_shared_settings_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.termux_shared_settings_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The same persisted defaults power standalone and workspace Termux tools.
        CardGroup(
            title = { Text(stringResource(R.string.setting_termux_section_timeouts)) },
        ) {
            item(
                headlineContent = { Text(stringResource(R.string.setting_termux_command_timeout)) },
                supportingContent = { Text(stringResource(R.string.termux_shared_timeout_description)) },
                trailingContent = {
                    TimeoutInput(
                        currentValue = config.commandTimeoutMs / 1_000L,
                        label = stringResource(R.string.setting_termux_command_timeout),
                        minimum = 5, maximum = 600,
                        tag = "termux-command-timeout",
                        unitLabel = stringResource(R.string.setting_termux_unit_seconds),
                        onCommit = vm::setCommandTimeoutSeconds,
                    )
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.setting_termux_turn_budget)) },
                supportingContent = { Text(stringResource(R.string.termux_shared_turn_budget_description)) },
                trailingContent = {
                    TimeoutInput(
                        currentValue = config.turnBudgetMs / 60_000L,
                        label = stringResource(R.string.setting_termux_turn_budget),
                        minimum = 1, maximum = 60,
                        unitLabel = stringResource(R.string.setting_termux_unit_minutes),
                        onCommit = vm::setTurnBudgetMinutes,
                    )
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.setting_termux_max_tool_steps)) },
                supportingContent = { Text(stringResource(R.string.termux_shared_steps_description)) },
                trailingContent = {
                    TimeoutInput(
                        currentValue = config.maxToolSteps.toLong(),
                        label = stringResource(R.string.setting_termux_max_tool_steps),
                        minimum = 1, maximum = 500,
                        unitLabel = stringResource(R.string.setting_termux_unit_steps),
                        onCommit = vm::setMaxToolSteps,
                    )
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.setting_termux_verify_timeout)) },
                supportingContent = { Text(stringResource(R.string.setting_termux_verify_timeout_desc)) },
                trailingContent = {
                    TimeoutInput(
                        currentValue = config.verifyTimeoutMs / 1_000L,
                        label = stringResource(R.string.setting_termux_verify_timeout),
                        minimum = 3, maximum = 30,
                        unitLabel = stringResource(R.string.setting_termux_unit_seconds),
                        onCommit = vm::setVerifyTimeoutSeconds,
                    )
                },
            )
        }

        CardGroup(
            title = { Text(stringResource(R.string.setting_termux_section_defaults)) },
        ) {
            if (workspaceDirectory == null) item(
                headlineContent = { Text(stringResource(R.string.setting_termux_working_dir)) },
                supportingContent = {
                    WorkingDirInput(
                        currentValue = config.defaultWorkingDir,
                        onCommit = vm::setDefaultWorkingDir,
                    )
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.setting_termux_max_stdout)) },
                supportingContent = { Text(stringResource(R.string.termux_shared_stdout_description)) },
                trailingContent = {
                    TimeoutInput(
                        currentValue = config.maxStdoutBytes.toLong(),
                        label = stringResource(R.string.setting_termux_max_stdout),
                        minimum = 1000, maximum = 64000,
                        unitLabel = stringResource(R.string.setting_termux_unit_bytes),
                        onCommit = { vm.setMaxStdoutBytes(it.toInt()) },
                    )
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.setting_termux_max_stderr)) },
                supportingContent = { Text(stringResource(R.string.termux_shared_stderr_description)) },
                trailingContent = {
                    TimeoutInput(
                        currentValue = config.maxStderrBytes.toLong(),
                        label = stringResource(R.string.setting_termux_max_stderr),
                        minimum = 500, maximum = 16000,
                        unitLabel = stringResource(R.string.setting_termux_unit_bytes),
                        onCommit = { vm.setMaxStderrBytes(it.toInt()) },
                    )
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.setting_termux_apt_wrap)) },
                supportingContent = { Text(stringResource(R.string.termux_shared_apt_description)) },
                trailingContent = {
                    Switch(
                        checked = config.aptWrapEnabled,
                        onCheckedChange = vm::setAptWrapEnabled,
                    )
                },
            )
        }

    }
}

/**
 * Compact numeric input for a timeout/cap row's trailing slot. Mirrors [TimeoutInput] in
 * [me.rerere.rikkahub.ui.pages.setting.browser.SettingBrowserPage] in shape, but uses
 * .take(6) instead of .take(4) to accommodate the 5-digit stdout/stderr byte caps.
 */
@Composable
private fun TimeoutInput(
    currentValue: Long,
    label: String,
    minimum: Long,
    maximum: Long,
    tag: String = "",
    unitLabel: String,
    onCommit: (Long) -> Unit,
) {
    var text by remember(currentValue) { mutableStateOf(currentValue.toString()) }

    val focusManager = LocalFocusManager.current
    val commit = {
        val value = text.toLongOrNull()?.coerceIn(minimum, maximum) ?: currentValue
        text = value.toString()
        if (value != currentValue) onCommit(value)
    }

    OutlinedTextField(
        value = text,
        onValueChange = { new -> text = new.filter { it.isDigit() }.take(6) },
        singleLine = true,
        suffix = { Text(unitLabel, style = MaterialTheme.typography.bodySmall) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit(); focusManager.clearFocus() }),
        modifier = Modifier
            .width(132.dp)
            .testTag(tag)
            .semantics { contentDescription = label }
            .onFocusChanged { focus ->
                if (!focus.isFocused) {
                    commit()
                }
            },
    )
}

/**
 * Single-line text field for the working directory setting. Commits on focus loss, same
 * pattern as [TimeoutInput]. An empty submit restores the current value.
 */
@Composable
private fun WorkingDirInput(
    currentValue: String,
    onCommit: (String) -> Unit,
) {
    var text by remember(currentValue) { mutableStateOf(currentValue) }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .onFocusChanged { focus ->
                if (!focus.isFocused) {
                    if (text != currentValue && text.isNotBlank()) {
                        onCommit(text)
                    } else {
                        text = currentValue
                    }
                }
            },
    )
}
