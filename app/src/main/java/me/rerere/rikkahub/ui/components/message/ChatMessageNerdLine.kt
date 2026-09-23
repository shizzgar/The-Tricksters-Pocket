package me.rerere.rikkahub.ui.components.message

import androidx.annotation.VisibleForTesting
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.pages.chat.progressDuration
import me.rerere.rikkahub.utils.formatNumber
import me.rerere.rikkahub.utils.toFixed
import java.time.Duration

/** Request timing is separate from message/tool lifetime. No inferred TPS for legacy records. */
@Composable
fun ChatMessageNerdLine(
    message: UIMessage,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    progress: GenerationProgress? = null,
    processingStatus: String? = null,
    active: Boolean = false,
) {
    if (message.role != MessageRole.ASSISTANT && progress == null) return
    if (!LocalSettings.current.displaySetting.showTokenUsage && !active) return
    var expanded by remember(message.id) { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.nanoTime() / 1_000_000) }
    LaunchedEffect(progress?.requestId, active) {
        while (active) { now = System.nanoTime() / 1_000_000; delay(1000) }
    }
    val metrics = (message.generationMetrics + listOfNotNull(progress?.takeIf { it.dispatchedAt != null }?.metrics(now)))
        .distinctBy { it.requestId }
    val latest = metrics.lastOrNull()
    val usage = latest?.usage ?: message.usage
    val output = metrics.mapNotNull { it.usage }.takeIf { it.isNotEmpty() }?.sumOf { it.completionTokens.toLong() }
        ?: usage?.completionTokens?.toLong()
    val speed = metrics.generationTokensPerSecond()
    val wallEnd = if (active) java.time.LocalDateTime.now() else message.finishedAt?.toJavaLocalDateTime()
    val wallMs = wallEnd?.let { Duration.between(message.createdAt.toJavaLocalDateTime(), it).toMillis().coerceAtLeast(0) }
    val phase = progress?.let { stringResource(when (it.phase) {
        GenerationPhase.PREPARING -> R.string.generation_progress_preparing
        GenerationPhase.QUEUED -> R.string.generation_progress_queued
        GenerationPhase.WAITING -> R.string.generation_progress_waiting
        GenerationPhase.RECEIVING -> R.string.generation_progress_receiving
        GenerationPhase.COMPLETED -> if (active) R.string.runtime_tools_or_checkpoint else R.string.generation_progress_completed
        GenerationPhase.FAILED -> R.string.generation_progress_failed
        GenerationPhase.CANCELLED -> R.string.generation_progress_cancelled
    }) }
    Column(modifier.fillMaxWidth().animateContentSize().clickable { expanded = !expanded }.padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (active && phase != null) Text("$phase · ${progressDuration((progress.finishedAt ?: now) - progress.startedAt)}",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            usage?.let { Text("↑ ${it.promptTokens.formatNumber()} tokens", style = MaterialTheme.typography.labelSmall, color = color) }
            output?.let { Text("↓ ${java.text.NumberFormat.getIntegerInstance().format(it)} tokens", style = MaterialTheme.typography.labelSmall, color = color) }
            Text(speed?.let { "${it.toFixed(1)} tok/s" } ?: "— tok/s", style = MaterialTheme.typography.labelSmall, color = color)
            wallMs?.let { Text("${stringResource(R.string.runtime_overall)} ${progressDuration(it)}", style = MaterialTheme.typography.labelSmall, color = color) }
            Text(if (expanded) "▴" else "▾", color = color)
        }
        if (expanded) {
            Text(stringResource(R.string.runtime_speed_explanation), style = MaterialTheme.typography.bodySmall, color = color)
            processingStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.runtime_request_count, metrics.size), style = MaterialTheme.typography.bodySmall)
            Text("${stringResource(R.string.runtime_queue)} ${progressDuration(metrics.sumOf { it.queueMs })} · ${stringResource(R.string.runtime_receiving)} ${progressDuration(metrics.sumOf { it.receivingMs ?: 0 })}", style = MaterialTheme.typography.bodySmall)
            latest?.firstContentMs?.let { Text(stringResource(R.string.generation_progress_first_content, progressDuration(it)), style = MaterialTheme.typography.bodySmall) }
            if (progress?.phase == GenerationPhase.RECEIVING && progress.finishedAt == null) {
                Text(stringResource(R.string.generation_progress_idle, progressDuration(now - (progress.lastContentAt ?: now))), style = MaterialTheme.typography.bodySmall)
            }
            latest?.httpStatus?.let { Text("HTTP $it · ${latest.backend.orEmpty()}", style = MaterialTheme.typography.bodySmall) }
            usage?.takeIf { it.cachedTokens > 0 }?.let { Text("${stringResource(R.string.runtime_cached)} ${it.cachedTokens.formatNumber()}", style = MaterialTheme.typography.bodySmall) }
            metrics.mapNotNull { it.usage?.cost }.takeIf { it.isNotEmpty() }?.sum()?.let { Text(formatCost(it), style = MaterialTheme.typography.bodySmall) }
            if (metrics.isEmpty()) Text(stringResource(R.string.runtime_no_measurements), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// Generation cost is often a tiny fraction of a cent, so a fixed decimal count would show
// "$0.0000". Render up to 6 decimals and trim trailing zeros (e.g. "$0.0123", "$0.000045").
// A positive cost smaller than 1e-6 would round to zero at 6dp and read as "$0" (free), which
// is misleading; clamp those to a "<$0.000001" form so a real charge never displays as free.
@VisibleForTesting
internal fun formatCost(cost: Double): String {
    val rounded = java.math.BigDecimal(cost)
        .setScale(6, java.math.RoundingMode.HALF_UP)
    if (cost > 0.0 && rounded.signum() == 0) {
        return "<$0.000001"
    }
    val s = rounded.stripTrailingZeros().toPlainString()
    return "$" + s
}

@Composable
fun StatsItem(
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        icon()
        content()
    }
}

