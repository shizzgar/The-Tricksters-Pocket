package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
                snapshot.compactionTrigger?.takeIf { snapshot.contextLimit != null }?.let { trigger ->
                    val x = (trigger.toFloat() / snapshot.contextLimit!!).coerceIn(0f, 1f) * size.width
                    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                }
            } ?: repeat(10) { index ->
                drawLine(color.copy(alpha = .45f), Offset(size.width * index / 10f, size.height / 2),
                    Offset(size.width * (index + .35f) / 10f, size.height / 2), strokeWidth = size.height)
            }
        }
        when (snapshot.level) {
            ContextUsageLevel.NEAR_THRESHOLD -> R.string.context_usage_near
            ContextUsageLevel.THRESHOLD_REACHED -> R.string.context_usage_threshold
            ContextUsageLevel.FULL -> R.string.context_usage_full
            else -> null
        }?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = color) }
        Text(stringResource(if (snapshot.streaming) R.string.context_usage_streaming else R.string.context_usage_estimate),
            style = MaterialTheme.typography.bodySmall)
        snapshot.latestPromptTokens?.let {
            Text(stringResource(R.string.context_usage_latest_prompt, format.format(it)), style = MaterialTheme.typography.bodySmall)
        } ?: Text(stringResource(R.string.context_usage_no_measurement), style = MaterialTheme.typography.bodySmall)
        Text(if (snapshot.outputReserve != null) stringResource(R.string.context_usage_reserve, format.format(snapshot.outputReserve),
            snapshot.availableInput?.let(format::format) ?: "—") else stringResource(R.string.context_usage_reserve_unknown),
            style = MaterialTheme.typography.bodySmall)
        Text(when {
            !snapshot.autoCompactionEnabled -> stringResource(R.string.context_usage_compaction_off)
            snapshot.compactionTrigger != null -> stringResource(R.string.context_usage_compaction_trigger, format.format(snapshot.compactionTrigger))
            else -> stringResource(R.string.context_usage_compaction_unknown)
        }, style = MaterialTheme.typography.bodySmall)
        if (snapshot.userLimit) Text(stringResource(R.string.context_usage_user_limit), style = MaterialTheme.typography.bodySmall)
        if (snapshot.compacted) Text(stringResource(R.string.context_usage_compacted), style = MaterialTheme.typography.bodySmall)
        if (!snapshot.providerAnchored) Text(stringResource(R.string.context_usage_overhead_unknown), style = MaterialTheme.typography.bodySmall)
    }
}
