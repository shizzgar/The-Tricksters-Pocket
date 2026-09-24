package me.rerere.rikkahub.ui.pages.chat

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TracePayloadInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private fun shot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "trajectory-qa/$name.png").apply { parentFile!!.mkdirs() }
        compose.onNodeWithTag("trace-payload-fixture").captureToImage().asAndroidBitmap().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun toolNamesAndDescriptionsAreVisibleBeforeExpansionAndSearchReachesLaterItems() {
        val names = listOf("search_web", "scrape_web", "termux_run_command", "termux_job_start", "termux_job_wait", "use_skill", "skill_list_files", "skill_read_file", "skill_write_file", "skill_edit_file", "skill_create", "skill_manage_files", "skill_delete", "termux_skill_sync", "read_file", "write_binary_file", "copy_file", "move_file", "list_files", "get_time", "ask_user", "text_to_speech")
        val tools = buildJsonArray { names.forEach { name -> add(buildJsonObject {
            put("name", name)
            put("description", if (name == "scrape_web") "Read a web page and extract its detailed content." else "Inspect or perform this operation in the current assistant session.")
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("url", buildJsonObject { put("type", "string") }) })
                put("required", buildJsonArray { add(JsonPrimitive("url")) })
            })
        }) } }
        compose.setContent { MaterialTheme(colorScheme = me.rerere.rikkahub.ui.theme.presets.RebroThemePreset.standardDark) { Surface {
            Column(Modifier.fillMaxSize().testTag("trace-payload-fixture").verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Dialogue trajectory", style = MaterialTheme.typography.headlineSmall)
                Text("Model request · 22 tools", style = MaterialTheme.typography.bodyMedium)
                TraceToolDeclarations(tools)
            }
        } } }
        compose.onNodeWithText("▸ Tools").performClick()
        compose.onNodeWithText("scrape_web", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Read a web page and extract its detailed content.").assertIsDisplayed()
        shot("trajectory-tools-compact")
        compose.onNodeWithText("scrape_web", substring = false).performClick()
        compose.onNodeWithText("parameters", substring = false).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("trace-tools-search").performScrollTo().performTextInput("text_to_speech")
        compose.onNode(hasText("text_to_speech", substring = false) and !hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText("scrape_web", substring = false).assertDoesNotExist()
    }
    @Test fun messageRowsShowRoleAndExcerptAndExpandedContentRemainsAvailable() {
        val messages = Json.parseToJsonElement("""[{"role":"system","parts":[{"type":"text","text":"You can inspect the project and its files."}]},{"role":"user","parts":[{"type":"text","text":"Find the latest report and summarize its results."}]},{"role":"assistant","parts":[{"type":"text","text":"I will locate the report, read it and check the results."}]}]""")
        compose.setContent { MaterialTheme { Surface {
            Column(Modifier.fillMaxSize().testTag("trace-payload-fixture").verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Dialogue trajectory", style = MaterialTheme.typography.headlineSmall)
                TraceValue("Messages", messages, expandedInitially = true)
            }
        } } }
        compose.onNodeWithText("user", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Find the latest report and summarize its results.").assertIsDisplayed()
        shot("trajectory-messages-compact")
        compose.onNodeWithText("user", substring = false).performClick()
        compose.onNodeWithText("parts", substring = false).performClick()
        compose.onNodeWithText("text", substring = false).performClick()
        compose.onNodeWithText("Find the latest report and summarize its results.").assertExists()
    }
}
