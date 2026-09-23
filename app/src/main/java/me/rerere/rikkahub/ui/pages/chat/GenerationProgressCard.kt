package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rerere.ai.provider.GenerationPhase
import me.rerere.ai.provider.GenerationProgress
import me.rerere.rikkahub.R

internal fun progressDuration(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
internal fun GenerationProgressCard(progress: GenerationProgress?, processingStatus: String?) {
    if (progress == null) return
    var now by remember(progress.attempt) { mutableLongStateOf(System.nanoTime() / 1_000_000) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(progress.attempt, progress.finishedAt) {
        while (progress.finishedAt == null) { now = System.nanoTime() / 1_000_000; delay(1000) }
    }
    val end = progress.finishedAt ?: now
    val phase = stringResource(when (progress.phase) {
        GenerationPhase.PREPARING -> R.string.generation_progress_preparing
        GenerationPhase.QUEUED -> R.string.generation_progress_queued
        GenerationPhase.WAITING -> R.string.generation_progress_waiting
        GenerationPhase.RECEIVING -> R.string.generation_progress_receiving
        GenerationPhase.COMPLETED -> R.string.generation_progress_completed
        GenerationPhase.FAILED -> R.string.generation_progress_failed
        GenerationPhase.CANCELLED -> R.string.generation_progress_cancelled
    })
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$phase · ${progressDuration(end - progress.startedAt)}", style = MaterialTheme.typography.labelLarge)
            processingStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (progress.phase == GenerationPhase.RECEIVING) {
                Text(stringResource(R.string.generation_progress_idle, progressDuration(end - (progress.lastContentAt ?: end))), style = MaterialTheme.typography.bodySmall)
            }
            if (expanded) {
                progress.dispatchedAt?.let { Text(stringResource(R.string.generation_progress_queue_time, progressDuration(it - progress.startedAt)), style = MaterialTheme.typography.bodySmall) }
                progress.firstContentAt?.let { Text(stringResource(R.string.generation_progress_first_content, progressDuration(it - (progress.dispatchedAt ?: progress.startedAt))), style = MaterialTheme.typography.bodySmall) }
                progress.httpStatus?.let { Text("HTTP $it" + (progress.backend?.let { b -> " · $b" } ?: ""), style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.generation_progress_waiting_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
