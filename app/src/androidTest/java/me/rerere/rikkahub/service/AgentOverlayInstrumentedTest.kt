package me.rerere.rikkahub.service

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.ai.ContextUsageSnapshot
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AgentOverlayInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun themedOverlayShowsLiveContextAndUnknownCapacityAccessibly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = context.readStringPreference("colorMode", ColorMode.SYSTEM.name)
        val custom = CustomTheme(name = "Overlay QA", primaryColorArgb = 0xff3a7d65)
        val settings = Settings(themeId = custom.id, customThemes = listOf(custom))
        val state = mutableStateOf(AgentOverlayState(AgentOverlaySession("qa", "ThinkBro", AgentOverlayPhase.RECEIVING, null,
            ContextUsageSnapshot(860, 1000, 800, 100, 800, true, false, true, true, false)), 2))
        context.writeStringPreference("colorMode", ColorMode.LIGHT.name)
        val palette = mutableStateOf(agentOverlayPalette(context, settings))
        var card: AgentOverlayCard? = null
        try {
            compose.setContent { RikkahubTheme { Surface { Box(Modifier.padding(16.dp)) {
                AndroidView(factory = { AgentOverlayCard(it).also { created -> card = created } },
                    update = { it.bind(state.value, palette.value) })
            } } } }
            compose.runOnIdle {
                assertTrue(card!!.contentDescription.contains("86%"))
                assertTrue(card!!.contentDescription.contains("ThinkBro"))
                assertEquals(custom.generateColorScheme(false).primary.toArgb(), palette.value.primary)
            }
            capture("pocket-agent-overlay-light")
            compose.runOnIdle {
                context.writeStringPreference("colorMode", ColorMode.DARK.name)
                palette.value = agentOverlayPalette(context, settings)
                state.value = state.value.copy(session = state.value.session.copy(
                    processingStatus = "Compacting context", context = state.value.session.context.copy(
                        usedTokens = 100, latestPromptTokens = null, providerAnchored = false, compacted = true)))
            }
            compose.runOnIdle {
                assertTrue(card!!.contentDescription.contains("10%"))
                assertEquals(custom.generateColorScheme(true).primary.toArgb(), palette.value.primary)
            }
            capture("pocket-agent-overlay-dark-compacted")
            compose.runOnIdle {
                state.value = state.value.copy(activeCount = 1, session = state.value.session.copy(processingStatus = null,
                    context = state.value.session.context.copy(contextLimit = null)))
            }
            compose.runOnIdle { assertFalse(card!!.contentDescription.contains("10%")) }
            capture("pocket-agent-overlay-unknown")
        } finally {
            context.writeStringPreference("colorMode", previous ?: ColorMode.SYSTEM.name)
        }
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "trajectory-qa/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
