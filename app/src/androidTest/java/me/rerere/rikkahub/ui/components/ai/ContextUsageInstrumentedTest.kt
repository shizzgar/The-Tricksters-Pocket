package me.rerere.rikkahub.ui.components.ai

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.ContextUsageSnapshot
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.message.ChatMessageNerdLine
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ContextUsageInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val snapshot = ContextUsageSnapshot(860, 1000, 800, 100, 800, true, false, true, false, true)
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "trajectory-qa/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun ringKeepsOneFullSizeSendTargetAndLongPressBehavior() {
        var sent = 0
        var longSent = 0
        compose.setContent { RikkahubTheme { Surface { Column(Modifier.width(340.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SendButton(false, false, { sent++ }, { longSent++ }, contextUsage = snapshot)
            ContextUsageDetails(snapshot)
        } } } }
        compose.onNodeWithTag("chat_send_button").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).assertHasClickAction()
        compose.onNodeWithTag("chat_send_button").performClick()
        compose.onNodeWithTag("chat_send_button").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, sent); assertEquals(1, longSent) }
        val state = compose.onNodeWithTag("chat_send_button").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertTrue(state.contains("86%"))
        compose.onNodeWithTag("context-usage-details").assertIsDisplayed()
        capture("pocket-context-ring-and-details")
    }

    @Test fun latestExpandedNerdLineShowsUnknownLimitAndUpdatesAfterCompaction() {
        val state = mutableStateOf(snapshot.copy(contextLimit = null, latestPromptTokens = null, providerAnchored = false))
        // A real chat keeps message identity while context usage updates.
        val message = UIMessage.assistant("result")
        compose.setContent { CompositionLocalProvider(LocalSettings provides Settings()) { RikkahubTheme { Surface {
            Column(Modifier.width(340.dp).padding(12.dp)) {
                SendButton(true, true, {}, {}, contextUsage = state.value)
                ChatMessageNerdLine(message, active = true, contextUsage = state.value)
            }
        } } } }
        compose.onNodeWithTag("context-usage-details", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("chat-message-nerd-line").performClick()
        // The clickable statistics container merges descendant semantics in the default tree.
        compose.onNodeWithTag("context-usage-details", useUnmergedTree = true).assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.context_usage_unknown_accessible, "860")).assertIsDisplayed()
        compose.runOnIdle { state.value = snapshot.copy(usedTokens = 100, latestPromptTokens = null, providerAnchored = false) }
        compose.onNodeWithText(context.getString(R.string.context_usage_accessible, "100", java.text.NumberFormat.getIntegerInstance().format(1000), 10)).assertIsDisplayed()
        capture("pocket-context-after-compaction")
    }
}
