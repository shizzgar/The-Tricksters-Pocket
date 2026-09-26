package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalSettings

@Composable
fun PocketLoadingIndicator(modifier: Modifier = Modifier) {
    if (LocalSettings.current.displaySetting.useAppIconStyleLoadingIndicator) {
        // Compose's animation clock honors the system animation duration scale. The
        // gentle reverse pulse avoids flashes and remains fully visible without motion.
        val transition = rememberInfiniteTransition(label = "pocket-loading")
        val opacity by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.65f,
            animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
            label = "pocket-opacity",
        )
        val tint by transition.animateColor(
            initialValue = MaterialTheme.colorScheme.primary,
            targetValue = MaterialTheme.colorScheme.tertiary,
            animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
            label = "pocket-tint",
        )
        PocketIcon(
            modifier = modifier.alpha(opacity).semantics {
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            },
            contentDescription = stringResource(R.string.accessibility_loading),
            tint = tint,
        )
    } else {
        ContainedLoadingIndicator(modifier = modifier)
    }
}
