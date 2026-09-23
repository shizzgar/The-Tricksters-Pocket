package me.rerere.rikkahub.ui.pages.chat

import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.SessionJournal
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import kotlin.uuid.Uuid

class TrajectoryInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = Uuid.random()
    private val conversation = Conversation(id = id, assistantId = Uuid.random(), messageNodes = emptyList())

    private fun fixture(): SessionJournal = runBlocking {
        var timestamp = 1_790_000_000_000L
        val root = File(context.cacheDir, "trace-test-${id}").apply { mkdirs() }
        val journal = SessionJournal(root) { timestamp }
        suspend fun event(ms: Long, source: String, payload: String) {
            timestamp = 1_790_000_000_000L + ms
            journal.append(id.toString(), source, Json.parseToJsonElement(payload).jsonObject)
        }
        event(0, "task.started", """{"run_id":"run-one"}""")
        event(100, "model.request", """{"request_id":"request-one","model":"Qwen 3.8","messages":[{"role":"user","parts":[{"type":"text","text":"Create a report"}]}]}""")
        event(4500, "model.response", """{"request_id":"request-one","elapsed_ms":4400,"first_content_ms":800,"receiving_ms":3500,"prompt_tokens":1200,"completion_tokens":140}""")
        event(4700, "tool.started", """{"tool_call_id":"skill","tool":"use_skill","parent_request_id":"request-one","input":"{\"name\":\"reports\"}"}""")
        event(5700, "tool.result", """{"tool_call_id":"skill","tool":"use_skill","elapsed_ms":1000,"output":[{"type":"text","text":"Skill ready in Termux"}]}""")
        event(6000, "subagent.started", """{"run_id":"research","task":"Inspect source data","child_conversation":"$id"}""")
        event(6500, "tool.started", """{"tool_call_id":"python","tool":"termux_run_command","parent_request_id":"request-one","input":"{\"command\":\"python3 scripts/report.py\"}"}""")
        event(16000, "tool.result", """{"tool_call_id":"python","tool":"termux_run_command","elapsed_ms":9500,"output":[{"type":"text","text":"{\"stdout\":\"Saved report.pdf\",\"stderr\":\"\",\"exit_code\":0}"}]}""")
        event(22000, "subagent.result", """{"run_id":"research","status":"COMPLETED","result":"Reviewed source data"}""")
        event(23000, "model.request", """{"request_id":"request-two","model":"Qwen final answer"}""")
        event(28500, "model.response", """{"request_id":"request-two","elapsed_ms":5500,"first_content_ms":600,"receiving_ms":4800,"prompt_tokens":2400,"completion_tokens":280}""")
        event(29000, "task.checkpoint", """{"run_id":"run-one","reason":"COMPLETED","continuing":false}""")
        journal
    }

    private fun show(russian: Boolean = false, exportUri: Uri? = null) {
        val journal = fixture()
        val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(if (russian) "ru" else "en")) })
        val exportOwner = exportUri?.let { uri -> object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                    assertTrue(input.toString().endsWith(".zip"))
                    dispatchResult(requestCode, uri)
                }
            }
        } }
        compose.setContent {
            val activityOwner = requireNotNull(LocalActivityResultRegistryOwner.current)
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides (exportOwner ?: activityOwner),
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
                LocalResources provides localized.resources,
            ) {
                MaterialTheme(colorScheme = if (russian) darkColorScheme() else lightColorScheme()) {
                    ConversationTrajectoryScreen(conversation, false, {}, {}, journalOverride = journal)
                }
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("use_skill").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun screenshot(name: String) {
        val output = File(context.filesDir, "trajectory-qa").apply { mkdirs() }.resolve("$name.png")
        compose.onNodeWithTag("trajectory").captureToImage().asAndroidBitmap().let { bitmap ->
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun waterfallFlowAndInspectorRemainNavigable() {
        show()
        screenshot("trajectory-waterfall")
        compose.onNodeWithText("Waterfall").performClick()
        compose.onNodeWithText("Flow").assertIsDisplayed()
        screenshot("trajectory-flow")
        compose.onNodeWithText("use_skill").performScrollTo().performClick()
        compose.onNodeWithText("Input").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("reports").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("reports").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Operation context").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Export JSON").assertExists()
        screenshot("trajectory-inspector")
    }

    @Test fun russianDarkTraceFitsPhone() {
        show(russian = true)
        screenshot("trajectory-russian-dark")
        compose.onAllNodesWithText("Инструменты").onFirst().assertExists()
    }

    @Test fun fullTraceExportUsesDocumentDestinationAndIgnoresSearchFilter() {
        val destination = File(context.cacheDir, "trajectory-export-$id.zip")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destination)
        show(exportUri = uri)
        compose.onNodeWithText("Search operations, commands, errors").performTextInput("no-matching-operation")
        compose.onNodeWithTag("trace-export-all").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Trace exported · events: 12 · sessions: 1").fetchSemanticsNodes().isNotEmpty() }
        ZipFile(destination).use { zip ->
            val manifest = Json.parseToJsonElement(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() }).jsonObject
            assertEquals(12L, manifest.getValue("event_count").jsonPrimitive.long)
            assertTrue(manifest.getValue("complete").jsonPrimitive.boolean)
            assertEquals(id.toString(), manifest.getValue("conversation_id").jsonPrimitive.content)
            val index = zip.getInputStream(zip.getEntry("sessions/$id/events.jsonl")).bufferedReader().use { it.readLines() }
            assertEquals(12, index.size)
            assertTrue(index.any { "tool.result" in it })
            assertTrue(index.any { "model.request" in it })
        }
        screenshot("trajectory-full-export")
        destination.delete()
    }
}
