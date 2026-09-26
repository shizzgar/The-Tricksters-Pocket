package me.rerere.rikkahub.ui.components.ai

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.PocketLoadingIndicator
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.findPresetTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class PocketIconInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private fun verifyAndCapture(dark: Boolean) {
        val assistant = Assistant(name = "PocketBro", avatar = Avatar.Emoji("🛠"))
        val settings = Settings(assistants = listOf(assistant), assistantId = assistant.id)
        val emptyModel = ModelListState(null, emptyList(), ModelType.CHAT)
        val model = Model(modelId = "local-workbench", displayName = "Local model")
        val unknownModel = ModelListState(
            model.id, listOf(ProviderSetting.OpenAI(models = listOf(model))), ModelType.CHAT,
        )
        compose.setContent {
            CompositionLocalProvider(LocalSettings provides settings) {
                MaterialTheme(colorScheme = findPresetTheme("rebro-blue").getColorScheme(dark)) {
                    Surface {
                        Column(
                            Modifier.width(340.dp).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text("The Trickster's Pocket", style = MaterialTheme.typography.titleLarge)
                            AssistantPicker(settings, {}, onClickSetting = {})
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ModelSelectorButton(emptyModel, Modifier.testTag("empty-model"), onlyIcon = true)
                                Text("Choose a model")
                            }
                            ModelSelectorButton(unknownModel, Modifier.testTag("unknown-model"))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                AutoAIIcon("openai", Modifier.size(32.dp))
                                Text("Recognized provider")
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                AutoAIIcon("auto", Modifier.size(32.dp))
                                Text("Automatic model")
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                PocketLoadingIndicator(Modifier.size(28.dp).testTag("pocket-generating"))
                                Text("Working on your request…")
                            }
                        }
                    }
                }
            }
        }
        // The assistant's chosen avatar stays intact beside the shared brand icon.
        compose.onNodeWithText("🛠").assertIsDisplayed()
        compose.onNodeWithTag("empty-model").assertHasClickAction().performClick()
        compose.runOnIdle { assertTrue(emptyModel.visible) }
        compose.onNodeWithTag("unknown-model").assertHasClickAction().performClick()
        compose.runOnIdle { assertTrue(unknownModel.visible) }
        compose.onNodeWithContentDescription("local-workbench", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("openai", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("auto", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("pocket-generating").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithContentDescription(context.getString(R.string.accessibility_loading)).assertIsDisplayed()
        val name = if (dark) "pocket-icons-dark" else "pocket-icons-light"
        File(context.filesDir, "trajectory-qa/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun lightIconsPreserveSelectionAndAccessibleActivity() = verifyAndCapture(dark = false)
    @Test fun darkIconsPreserveSelectionAndAccessibleActivity() = verifyAndCapture(dark = true)
}
