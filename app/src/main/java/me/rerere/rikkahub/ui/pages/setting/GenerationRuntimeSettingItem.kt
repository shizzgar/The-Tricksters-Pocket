package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.ai.provider.GenerationRuntimeSettings
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ui.CardGroup

@Composable
internal fun GenerationRuntimeSettingItem(settings: Settings, vm: SettingVM) {
    val config = settings.networkSetting.generationRuntime.normalized()
    fun update(change: (GenerationRuntimeSettings) -> GenerationRuntimeSettings) {
        vm.updateSettings { current -> current.copy(networkSetting = current.networkSetting.copy(
            generationRuntime = change(current.networkSetting.generationRuntime).normalized(),
        )) }
    }
    CardGroup {
        item(headlineContent = { Text(stringResource(R.string.generation_runtime_title)) },
            supportingContent = { Text(stringResource(R.string.generation_runtime_description)) })
        item(headlineContent = {
            CompactionIntegerField(stringResource(R.string.generation_connect_timeout),
                stringResource(R.string.generation_connect_description), config.connectTimeoutSeconds, 5..120,
                { value -> update { it.copy(connectTimeoutSeconds = value) } })
        })
        item(headlineContent = {
            CompactionIntegerField(stringResource(R.string.generation_first_response_timeout),
                stringResource(R.string.generation_first_response_description), config.firstResponseTimeoutMinutes, 1..120,
                { value -> update { it.copy(firstResponseTimeoutMinutes = value) } })
        })
        item(headlineContent = {
            CompactionIntegerField(stringResource(R.string.generation_read_timeout),
                stringResource(R.string.generation_read_description), config.readTimeoutMinutes, 1..120,
                { value -> update { it.copy(readTimeoutMinutes = value) } })
        })
        item(headlineContent = {
            CompactionIntegerField(stringResource(R.string.generation_request_timeout),
                stringResource(R.string.generation_request_description), config.requestTimeoutMinutes, 1..240,
                { value -> update { it.copy(requestTimeoutMinutes = value) } })
        })
        item(headlineContent = {
            CompactionIntegerField(stringResource(R.string.generation_parallel_requests),
                stringResource(R.string.generation_parallel_description), config.parallelRequests, 1..8,
                { value -> update { it.copy(parallelRequests = value) } })
        })
    }
}
