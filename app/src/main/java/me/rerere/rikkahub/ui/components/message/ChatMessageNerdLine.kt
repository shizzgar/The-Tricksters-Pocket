package me.rerere.rikkahub.ui.components.message

import androidx.annotation.VisibleForTesting
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.ContextUsageSnapshot
import me.rerere.rikkahub.ui.components.ai.ContextUsageDetails
import me.rerere.rikkahub.ui.components.ai.ContextUsageExplanation
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.pages.chat.progressDuration
import me.rerere.rikkahub.utils.toFixed
import java.text.NumberFormat
import java.time.Duration

/** Compact live status; request usage, reply totals and context each retain their own scope. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatMessageNerdLine(
    message: UIMessage,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    progress: GenerationProgress? = null,
    processingStatus: String? = null,
    active: Boolean = false,
    contextUsage: ContextUsageSnapshot? = null,
) {
    if (message.role != MessageRole.ASSISTANT && progress == null && contextUsage == null) return
    if (!LocalSettings.current.displaySetting.showTokenUsage && !active) return
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    var diagnostics by rememberSaveable(message.id) { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.nanoTime() / 1_000_000) }
    LaunchedEffect(progress?.requestId, active) {
        while (active) { now = System.nanoTime() / 1_000_000; delay(1000) }
    }
    val summary = MessageMetricsSummary.from(message, progress, now)
    val latest = summary.latest
    val format = NumberFormat.getIntegerInstance()
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
    val status = processingStatus?.takeIf { it.isNotBlank() } ?: phase
    Column(modifier.testTag("chat-message-nerd-line").fillMaxWidth().animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (active && status != null) Text(status, modifier = Modifier.padding(horizontal = 6.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("message-stats-toggle")
            .clickable(onClickLabel = stringResource(if (expanded) R.string.pocket_stats_collapse else R.string.pocket_stats_expand)) { expanded = !expanded }
            .padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                contextUsage?.let { Text(stringResource(R.string.pocket_stats_context, it.percent?.let { percent -> "$percent%" } ?: "—"),
                    style = MaterialTheme.typography.labelSmall, color = color) }
                summary.outputTokens?.let { Text(stringResource(R.string.pocket_stats_output, (if (summary.partial) "≥" else "") + format.format(it)),
                    style = MaterialTheme.typography.labelSmall, color = color) }
                summary.speed?.let { Text(stringResource(R.string.pocket_stats_speed, it.toFixed(1)), style = MaterialTheme.typography.labelSmall, color = color) }
                wallMs?.let { Text(stringResource(R.string.pocket_stats_elapsed, progressDuration(it)), style = MaterialTheme.typography.labelSmall, color = color) }
                if (contextUsage == null && summary.outputTokens == null && wallMs == null) Text(stringResource(R.string.pocket_stats_expand),
                    style = MaterialTheme.typography.labelSmall, color = color)
            }
            Icon(if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
        }
        if (expanded) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().testTag("message-stats-panel")) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                contextUsage?.let { ContextUsageDetails(it) }
                if (contextUsage != null && (latest != null || summary.legacyUsage != null)) HorizontalDivider()
                if (latest != null) {
                    Text(stringResource(if (summary.latestUsage?.aggregatedRequests == true) R.string.pocket_stats_latest_cycle else R.string.pocket_stats_latest), style = MaterialTheme.typography.labelMedium)
                    summary.latestUsage?.let { usage ->
                        UsagePair(usage.promptTokens.toLong(), usage.completionTokens.toLong(), "latest-request-usage")
                    } ?: Text(stringResource(R.string.pocket_stats_missing_usage), style = MaterialTheme.typography.bodySmall, color = color)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${stringResource(R.string.pocket_stats_queue)} ${progressDuration(latest.queueMs)}", style = MaterialTheme.typography.labelSmall)
                        latest.firstContentMs?.let { Text("${stringResource(R.string.pocket_stats_first_content)} ${progressDuration(it)}", style = MaterialTheme.typography.labelSmall) }
                    }
                } else if (summary.legacyUsage != null) {
                    Text(stringResource(R.string.pocket_stats_legacy), style = MaterialTheme.typography.labelMedium)
                    UsagePair(summary.inputTokens, summary.outputTokens, "legacy-recorded-usage")
                }
                TextButton(onClick = { diagnostics = true }, modifier = Modifier.testTag("message-stats-diagnostics"), contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.pocket_stats_diagnostics))
                }
            }
        }
    }
    if (diagnostics) ModalBottomSheet(onDismissRequest = { diagnostics = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)
            .testTag("message-stats-diagnostics-sheet"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.pocket_stats_diagnostics_title), style = MaterialTheme.typography.titleLarge)
            contextUsage?.let {
                ContextUsageDetails(it)
                ContextUsageExplanation(it)
                Text(stringResource(R.string.pocket_stats_context_scope), style = MaterialTheme.typography.bodySmall, color = color)
                HorizontalDivider()
            }
            Text(stringResource(if (summary.requests.isEmpty()) R.string.pocket_stats_legacy else R.string.pocket_stats_reply, summary.requests.size),
                style = MaterialTheme.typography.titleSmall)
            UsagePair(summary.inputTokens, summary.outputTokens, "whole-reply-usage", summary.partial)
            if (summary.partial) Text(stringResource(R.string.pocket_stats_partial, summary.measuredRequests, summary.requests.size),
                style = MaterialTheme.typography.bodySmall, color = color)
            if (summary.requests.isEmpty()) Text(stringResource(R.string.pocket_stats_legacy_scope), style = MaterialTheme.typography.bodySmall, color = color)
            Text(stringResource(R.string.pocket_stats_time_scope), style = MaterialTheme.typography.bodySmall, color = color)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MetricField(stringResource(R.string.pocket_stats_queue), summary.requests.takeIf { it.isNotEmpty() }?.sumOf { it.queueMs }?.let(::progressDuration) ?: "—")
                MetricField(stringResource(R.string.pocket_stats_receiving), summary.requests.mapNotNull { it.receivingMs }.takeIf { it.isNotEmpty() }?.sum()?.let(::progressDuration) ?: "—")
                summary.speed?.let { MetricField(stringResource(R.string.pocket_stats_speed, it.toFixed(1)), "") }
            }
            summary.cost?.let { MetricField(stringResource(R.string.pocket_stats_cost), formatCost(it)) }
            latest?.let { request ->
                HorizontalDivider()
                Text(stringResource(if (summary.latestUsage?.aggregatedRequests == true) R.string.pocket_stats_latest_cycle else R.string.pocket_stats_latest), style = MaterialTheme.typography.titleSmall)
                summary.latestUsage?.let { UsagePair(it.promptTokens.toLong(), it.completionTokens.toLong(), "diagnostic-latest-usage") }
                    ?: Text(stringResource(R.string.pocket_stats_missing_usage), style = MaterialTheme.typography.bodySmall)
                if (summary.latestUsage?.aggregatedRequests == true) Text(stringResource(R.string.pocket_stats_aggregate_scope), style = MaterialTheme.typography.bodySmall, color = color)
                request.httpStatus?.let { MetricField(stringResource(R.string.pocket_stats_http), "HTTP $it" + request.backend?.let { backend -> " · $backend" }.orEmpty()) }
                request.firstContentMs?.let { MetricField(stringResource(R.string.pocket_stats_first_content), progressDuration(it)) }
                summary.latestUsage?.cachedTokens?.takeIf { it > 0 }?.let { MetricField(stringResource(R.string.pocket_stats_cached), format.format(it)) }
            }
            if (active && progress?.phase == GenerationPhase.RECEIVING && progress.finishedAt == null) {
                progress.lastContentAt?.let { MetricField(stringResource(R.string.pocket_stats_idle), progressDuration((now - it).coerceAtLeast(0))) }
            }
            if (active && status != null) Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun UsagePair(input: Long?, output: Long?, tag: String, partial: Boolean = false) {
    val format = NumberFormat.getIntegerInstance()
    val prefix = if (partial) "≥" else ""
    Row(Modifier.fillMaxWidth().testTag(tag), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MetricField(stringResource(R.string.pocket_stats_input), input?.let { prefix + format.format(it) } ?: "—", Modifier.weight(1f))
        MetricField(stringResource(R.string.pocket_stats_generated), output?.let { prefix + format.format(it) } ?: "—", Modifier.weight(1f))
    }
}

@Composable
private fun MetricField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (value.isNotEmpty()) Text(value, style = MaterialTheme.typography.bodyMedium)
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
