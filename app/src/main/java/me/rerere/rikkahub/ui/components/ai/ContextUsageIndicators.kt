package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.ContextUsageLevel
import me.rerere.rikkahub.data.ai.ContextUsageSnapshot
import java.text.NumberFormat

@Composable
internal fun ContextUsageSnapshot.accessibilityText(): String {
    val used = NumberFormat.getIntegerInstance().format(usedTokens)
    return if (contextLimit != null) stringResource(R.string.context_usage_accessible, used,
        NumberFormat.getIntegerInstance().format(contextLimit), percent ?: 0)
    else stringResource(R.string.context_usage_unknown_accessible, used)
}

@Composable
private fun ContextUsageSnapshot.indicatorColor(): Color = when (level) {
    ContextUsageLevel.FULL, ContextUsageLevel.THRESHOLD_REACHED -> MaterialTheme.colorScheme.error
    ContextUsageLevel.NEAR_THRESHOLD -> MaterialTheme.colorScheme.tertiary
    ContextUsageLevel.NORMAL -> MaterialTheme.colorScheme.primary
    ContextUsageLevel.UNKNOWN -> MaterialTheme.colorScheme.outline
}

/** Decorative only; the enclosing Send/Stop button keeps one accessible 48dp click target. */
@Composable
internal fun ContextUsageRing(snapshot: ContextUsageSnapshot?, modifier: Modifier = Modifier) {
    val color = snapshot?.indicatorColor() ?: MaterialTheme.colorScheme.outline
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.testTag("context-usage-ring")) {
        val stroke = 2.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        snapshot?.fraction?.let {
            if (it > 0f) drawArc(color, -90f, 360f * it, false, Offset(inset, inset), arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round))
        } ?: run {
            // A dashed unknown ring does not pretend the context is empty or still loading.
            repeat(8) { drawArc(color, it * 45f, 16f, false, Offset(inset, inset), arcSize, style = Stroke(stroke)) }
        }
    }
}

/** Short enough to stay in the chat; explanations live in the separate diagnostic sheet. */
@Composable
fun ContextUsageDetails(snapshot: ContextUsageSnapshot, modifier: Modifier = Modifier) {
    val description = snapshot.accessibilityText()
    val color = snapshot.indicatorColor()
    val track = MaterialTheme.colorScheme.surfaceVariant
    val format = NumberFormat.getIntegerInstance()
    Column(modifier.testTag("context-usage-details"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(description, style = MaterialTheme.typography.labelMedium)
        Canvas(Modifier.fillMaxWidth().height(6.dp).semantics { contentDescription = description }) {
            drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            snapshot.fraction?.let { fraction ->
                if (fraction > 0f) drawRoundRect(color, size = Size(size.width * fraction, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
                snapshot.compactionTrigger?.takeIf { snapshot.autoCompactionEnabled && snapshot.contextLimit != null }?.let { trigger ->
                    val x = (trigger.toFloat() / snapshot.contextLimit!!).coerceIn(0f, 1f) * size.width
                    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                }
            } ?: repeat(10) { index ->
                drawLine(color.copy(alpha = .45f), Offset(size.width * index / 10f, size.height / 2),
                    Offset(size.width * (index + .35f) / 10f, size.height / 2), strokeWidth = size.height)
            }
        }
        Text(stringResource(if (snapshot.streaming) R.string.pocket_context_stream_source else R.string.pocket_context_compact_source),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ContextMetric(stringResource(R.string.pocket_context_reserve_short), snapshot.outputReserve?.let(format::format) ?: "—")
            ContextMetric(stringResource(R.string.pocket_context_free_short), snapshot.availableInput?.let { "≈" + format.format(it) } ?: "—")
        }
        ContextMetric(stringResource(R.string.pocket_context_compact_short), when {
            !snapshot.autoCompactionEnabled -> stringResource(R.string.pocket_context_off_short)
            snapshot.compactionTrigger != null -> stringResource(R.string.pocket_context_threshold_short, format.format(snapshot.compactionTrigger))
            else -> stringResource(R.string.pocket_context_unknown_short)
        })
        when (snapshot.level) {
            ContextUsageLevel.NEAR_THRESHOLD -> R.string.context_usage_near
            ContextUsageLevel.THRESHOLD_REACHED -> R.string.context_usage_threshold
            ContextUsageLevel.FULL -> R.string.context_usage_full
            else -> null
        }?.let { Text(stringResource(it), style = MaterialTheme.typography.labelSmall, color = color) }
    }
}

@Composable
private fun ContextMetric(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun ContextUsageExplanation(snapshot: ContextUsageSnapshot) {
    val format = NumberFormat.getIntegerInstance()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        snapshot.latestPromptTokens?.let {
            Text(stringResource(R.string.context_usage_latest_prompt, format.format(it)), style = MaterialTheme.typography.bodySmall)
        } ?: Text(stringResource(R.string.context_usage_no_measurement), style = MaterialTheme.typography.bodySmall)
        if (snapshot.outputReserve == null) Text(stringResource(R.string.context_usage_reserve_unknown), style = MaterialTheme.typography.bodySmall)
        if (snapshot.autoCompactionEnabled && snapshot.configuredCompactionTrigger != null && snapshot.configuredCompactionTrigger != snapshot.compactionTrigger) {
            Text(stringResource(R.string.pocket_context_configured_threshold, format.format(snapshot.configuredCompactionTrigger)), style = MaterialTheme.typography.bodySmall)
        }
        if (snapshot.autoCompactionEnabled) Text(stringResource(R.string.pocket_context_trigger_timing), style = MaterialTheme.typography.bodySmall)
        if (snapshot.userLimit) Text(stringResource(R.string.context_usage_user_limit), style = MaterialTheme.typography.bodySmall)
        if (snapshot.compacted) Text(stringResource(R.string.context_usage_compacted), style = MaterialTheme.typography.bodySmall)
        if (!snapshot.providerAnchored) Text(stringResource(R.string.context_usage_overhead_unknown), style = MaterialTheme.typography.bodySmall)
    }
}
