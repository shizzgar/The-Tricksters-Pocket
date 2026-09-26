package me.rerere.rikkahub.ui.components.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import me.rerere.rikkahub.R

/** The same pocket-and-ears silhouette used by the app's notification icon. */
@Composable
fun PocketIcon(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = LocalContentColor.current,
) {
    Icon(
        painter = painterResource(R.drawable.small_icon),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
    )
}
