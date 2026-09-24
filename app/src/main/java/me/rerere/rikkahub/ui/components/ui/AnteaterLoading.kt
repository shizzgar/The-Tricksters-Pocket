package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalSettings

@Composable
fun AnteaterLoadingIndicator(modifier: Modifier = Modifier) {
    if (LocalSettings.current.displaySetting.useAppIconStyleLoadingIndicator) {
        val transition = rememberInfiniteTransition(label = "anteater-loading")
        val opacity by transition.animateFloat(
            initialValue = 0.45f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "opacity",
        )
        Icon(painterResource(R.mipmap.ic_launcher_monochrome), null,
            modifier.alpha(opacity), tint = MaterialTheme.colorScheme.primary)
    } else {
        ContainedLoadingIndicator(modifier = modifier)
    }
}
