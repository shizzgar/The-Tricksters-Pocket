package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.ui.theme.PresetTheme

/** Navy surfaces, blue actions and steel text; error and warning colors retain their meaning. */
val RebroThemePreset by lazy {
    PresetTheme(
        id = "rebro-blue",
        name = { Text("ReBro Blue") },
        standardLight = lightColorScheme(
            primary = Color(0xFF245C98), onPrimary = Color.White,
            primaryContainer = Color(0xFFD5E7FF), onPrimaryContainer = Color(0xFF10365E),
            secondary = Color(0xFF506176), onSecondary = Color.White,
            secondaryContainer = Color(0xFFDCE5F0), onSecondaryContainer = Color(0xFF293E55),
            tertiary = Color(0xFF326779), onTertiary = Color.White,
            tertiaryContainer = Color(0xFFCDEBF5), onTertiaryContainer = Color(0xFF154B5B),
            background = Color(0xFFF3F6FC), onBackground = Color(0xFF172334),
            surface = Color(0xFFF3F6FC), onSurface = Color(0xFF172334),
            surfaceVariant = Color(0xFFDFE6EF), onSurfaceVariant = Color(0xFF445367),
            surfaceDim = Color(0xFFD2DBE8), surfaceBright = Color(0xFFF8FAFF),
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEDF2FA),
            surfaceContainer = Color(0xFFE6EDF7), surfaceContainerHigh = Color(0xFFDFE7F2),
            surfaceContainerHighest = Color(0xFFD7E1EE),
            outline = Color(0xFF687A91), outlineVariant = Color(0xFFBAC9DB),
            inverseSurface = Color(0xFF27364A), inverseOnSurface = Color(0xFFEDF3FF),
            inversePrimary = Color(0xFFA5CCFF), surfaceTint = Color(0xFF245C98),
            error = Color(0xFFBA1A1A), onError = Color.White,
            errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
            scrim = Color.Black,
        ),
        standardDark = darkColorScheme(
            primary = Color(0xFFA5CCFF), onPrimary = Color(0xFF082F57),
            primaryContainer = Color(0xFF234E7E), onPrimaryContainer = Color(0xFFD9E9FF),
            secondary = Color(0xFFB8CADE), onSecondary = Color(0xFF203348),
            secondaryContainer = Color(0xFF354B63), onSecondaryContainer = Color(0xFFDCE8F7),
            tertiary = Color(0xFF99D2E6), onTertiary = Color(0xFF063644),
            tertiaryContainer = Color(0xFF204F60), onTertiaryContainer = Color(0xFFC9EDFA),
            background = Color(0xFF0B1427), onBackground = Color(0xFFE1EBFA),
            surface = Color(0xFF0B1427), onSurface = Color(0xFFE1EBFA),
            surfaceVariant = Color(0xFF30415A), onSurfaceVariant = Color(0xFFB8CADE),
            surfaceDim = Color(0xFF0B1427), surfaceBright = Color(0xFF34445D),
            surfaceContainerLowest = Color(0xFF070F1E), surfaceContainerLow = Color(0xFF111E33),
            surfaceContainer = Color(0xFF17263D), surfaceContainerHigh = Color(0xFF203149),
            surfaceContainerHighest = Color(0xFF293B54),
            outline = Color(0xFF8198B5), outlineVariant = Color(0xFF3B506C),
            inverseSurface = Color(0xFFE1EBFA), inverseOnSurface = Color(0xFF203149),
            inversePrimary = Color(0xFF245C98), surfaceTint = Color(0xFFA5CCFF),
            error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
            scrim = Color.Black,
        ),
    )
}
