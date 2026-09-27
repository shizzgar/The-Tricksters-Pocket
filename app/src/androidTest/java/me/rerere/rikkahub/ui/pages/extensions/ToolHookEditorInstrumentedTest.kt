package me.rerere.rikkahub.ui.pages.extensions

import android.graphics.Bitmap
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.model.ToolHook
import me.rerere.rikkahub.data.model.ToolHookAction
import me.rerere.rikkahub.data.model.ToolHookCondition
import me.rerere.rikkahub.data.model.ToolHookScope
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ToolHookEditorInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private fun capture(tag: String, filename: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "trajectory-qa/$filename.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun editorRequiresAnInstructionBeforeSaveAndKeepsPreviewReadOnly() {
        val draft = mutableStateOf(ToolHook(scope = ToolHookScope(global = true)))
        var saved = 0
        var previewed: ToolHook? = null
        compose.setContent { RikkahubTheme { Surface {
            ToolHookEditSheet(draft.value, { draft.value = it }, { saved++ }, {}, { previewed = it })
        } } }
        compose.onNodeWithTag("hook-save").assertIsNotEnabled()
        compose.onNodeWithTag("hook-name").performTextReplacement("JADX repair")
        compose.onNodeWithTag("hook-prompt").performScrollTo().performTextReplacement("Read the installed jadx --help before adjusting arguments.")
        compose.onNodeWithTag("hook-save").assertIsEnabled()
        compose.onNodeWithTag("hook-preview").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(draft.value, previewed); assertEquals(0, saved) }
        compose.onNodeWithTag("hook-save").performClick()
        compose.runOnIdle { assertEquals(1, saved) }
    }

    @Test fun disabledJadxRuleCanBeEditedWithoutEnablingOrSavingIt() {
        val draft = mutableStateOf(ToolHook(name = "JADX repair", enabled = false, scope = ToolHookScope(global = true), condition = ToolHookCondition(toolNames = setOf("termux_run_command"), commandExecutable = "jadx"), action = ToolHookAction(prompt = "Read jadx --help and correct invalid arguments before retrying.")))
        var saved = 0
        compose.setContent { RikkahubTheme { Surface {
            ToolHookEditSheet(draft.value, { draft.value = it }, { saved++ }, {}, {})
        } } }
        compose.onNodeWithTag("hook-save").assertIsEnabled()
        capture("hook-editor", "pocket-hook-editor")
        compose.onNodeWithTag("hook-cap").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("hook-save").assertIsNotEnabled()
        compose.onNodeWithTag("hook-cap").performTextReplacement("2")
        compose.onNodeWithTag("hook-save").assertIsEnabled()
        compose.runOnIdle { assertFalse(draft.value.enabled); assertEquals(2, draft.value.maxFiringsPerTurn); assertEquals(0, saved) }
    }

    @Test fun emptyLibraryOffersBothNewRuleAndDisabledStarter() {
        var created = 0
        var starter = 0
        compose.setContent { RikkahubTheme { Surface(Modifier.width(360.dp)) {
            ToolHooksList(emptyList(), { created++ }, { starter++ }, {}, { _, _ -> }, {})
        } } }
        compose.onNodeWithTag("hook-add").assertHasClickAction().performClick()
        compose.onNodeWithTag("hook-starter").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, created); assertEquals(1, starter) }
        capture("hooks-list", "pocket-hooks-library")
    }
}
