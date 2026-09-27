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
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
        compose.onNodeWithTag("message-stats-toggle").performClick()
        // The clickable statistics container merges descendant semantics in the default tree.
        compose.onNodeWithTag("context-usage-details", useUnmergedTree = true).assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.context_usage_unknown_accessible, "860")).assertIsDisplayed()
        compose.runOnIdle { state.value = snapshot.copy(usedTokens = 100, latestPromptTokens = null, providerAnchored = false) }
        compose.onNodeWithText(context.getString(R.string.context_usage_accessible, "100", java.text.NumberFormat.getIntegerInstance().format(1000), 10)).assertIsDisplayed()
        capture("pocket-context-after-compaction")
    }
    @Test fun compactStatisticsSeparateLastRequestFromReplyTotals() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val message = UIMessage.assistant("Complete").copy(generationMetrics = listOf(
            me.rerere.ai.provider.GenerationRequestMetrics("first", "COMPLETED", 2000, 50, receivingMs = 1000,
                usage = me.rerere.ai.core.TokenUsage(promptTokens = 100, completionTokens = 20)),
            me.rerere.ai.provider.GenerationRequestMetrics("latest", "COMPLETED", 3000, 75, firstContentMs = 500, receivingMs = 2000,
                usage = me.rerere.ai.core.TokenUsage(promptTokens = 800, completionTokens = 30), httpStatus = 200, backend = "primary"),
        ))
        val settings = Settings().let { it.copy(displaySetting = it.displaySetting.copy(showTokenUsage = true)) }
        compose.setContent { CompositionLocalProvider(LocalSettings provides settings) { RikkahubTheme { Surface {
            Column(Modifier.width(340.dp).padding(12.dp)) {
                ChatMessageNerdLine(message, contextUsage = snapshot.copy(usedTokens = 950, contextLimit = 2000,
                    latestPromptTokens = 800, outputReserve = 200, compactionTrigger = 1600, configuredCompactionTrigger = 1600, compacted = false))
            }
        } } } }
        compose.onNodeWithTag("message-stats-toggle").performClick()
        compose.onNodeWithTag("latest-request-usage").assertIsDisplayed()
        compose.onNodeWithText("800").assertIsDisplayed()
        compose.onNodeWithText("30").assertIsDisplayed()
        val scopeNote = context.getString(R.string.pocket_stats_context_scope)
        compose.onNodeWithText(scopeNote).assertDoesNotExist()
        capture("pocket-message-stats-compact")
        compose.onNodeWithTag("message-stats-diagnostics").performClick()
        compose.onNodeWithTag("whole-reply-usage").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("900").assertIsDisplayed()
        compose.onNodeWithText("50").assertIsDisplayed()
        compose.onNodeWithText("HTTP 200 · primary").performScrollTo().assertIsDisplayed()
        val file = File(context.filesDir, "trajectory-qa/pocket-message-stats-diagnostics.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use {
            compose.onNodeWithTag("message-stats-diagnostics-sheet").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

}
