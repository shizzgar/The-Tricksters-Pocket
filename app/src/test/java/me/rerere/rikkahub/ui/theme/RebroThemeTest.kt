package me.rerere.rikkahub.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import me.rerere.rikkahub.ui.theme.presets.RebroThemePreset
import org.junit.Assert.assertTrue
import org.junit.Test

class RebroThemeTest {
    private fun contrast(a: Color, b: Color): Float {
        val light = maxOf(a.luminance(), b.luminance())
        val dark = minOf(a.luminance(), b.luminance())
        return (light + .05f) / (dark + .05f)
    }

    @Test fun `both palettes keep normal text readable on their surfaces`() {
        for (dark in listOf(false, true)) {
            val c = RebroThemePreset.getColorScheme(dark)
            for ((text, bg) in listOf(
                c.onBackground to c.background, c.onSurface to c.surface,
                c.onSurface to c.surfaceContainerHighest, c.onSurfaceVariant to c.surfaceContainerHighest,
                c.onPrimary to c.primary, c.onPrimaryContainer to c.primaryContainer,
                c.onSecondaryContainer to c.secondaryContainer, c.onTertiaryContainer to c.tertiaryContainer,
                c.onError to c.error, c.onErrorContainer to c.errorContainer,
            )) assertTrue("dark=$dark text=$text background=$bg", contrast(text, bg) >= 4.5f)
            assertTrue(contrast(c.primary, c.surfaceContainerHighest) >= 3f)
            assertTrue(contrast(c.outline, c.surface) >= 3f)
        }
    }
}
