package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.Locale

class ToolAccessInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun runtimeRespectsGroupsIndividualExclusionsAndCallingAssistant() = runBlocking {
        val settings = GlobalContext.get().get<SettingsStore>()
        val manager = GlobalContext.get().get<SkillManager>()
        val factory = GlobalContext.get().get<LocalTools>()
        withTimeout(15_000) { settings.settingsFlow.first { !it.init } }
        val name = "tool-access-fixture"
        val createdName = "tool-created-fixture"
        manager.deleteSkill(createdName)
        check(manager.saveSkill(name, "---\nname: $name\ndescription: Tools test\n---\nHello") != null)
        val allGroups = listOf(LocalToolOption.Termux, LocalToolOption.SkillManagement, LocalToolOption.SkillImport, LocalToolOption.JsSkills)
        var assistant = Assistant(name = "Tool access test", localTools = allGroups)
        val id = assistant.id
        suspend fun save() {
            settings.update { it.copy(assistants = it.assistants.filterNot { a -> a.id == id } + assistant) }
            withTimeout(15_000) { settings.settingsFlow.first { it.assistants.any { a -> a == assistant } } }
        }
        fun tools() = factory.getTools(assistant.localTools, ToolInvocationContext(callerAssistantId = id.toString(), callerConversationId = id.toString()))
        try {
            save()
            var names = tools().map { it.name }
            assertTrue("termux_run_command" in names && "conversation_history_read" in names)
            assertTrue(factory.getTools(assistant.localTools, ToolInvocationContext(callerAssistantId = id.toString()), includeDisabled = true).any { it.name == "conversation_history_read" })
            assertFalse(names.any(::isSkillTool))
            assertFalse("text_to_speech" in names || "whisper_status" in names || "transcribe_audio_file" in names)
            assistant = assistant.copy(enabledSkills = setOf(name), localTools = allGroups + LocalToolOption.Tts + LocalToolOption.Whisper)
            save()
            val createResult = tools().first { it.name == "skill_create" }.execute(buildJsonObject {
                put("name", createdName); put("description", "Created through an agent tool"); put("instructions", "Newly created instructions")
            })
            assertTrue((createResult.single() as UIMessagePart.Text).text.contains("\"ok\":true"))
            assertTrue(createdName in settings.settingsFlow.value.assistants.first { it.id == id }.enabledSkills)
            val loaded = tools().first { it.name == "use_skill" }.execute(buildJsonObject { put("name", createdName) })
            assertTrue((loaded.first() as UIMessagePart.Text).text.contains("Newly created instructions"))
            assistant = settings.settingsFlow.value.assistants.first { it.id == id }
            val enabledTools = tools()
            names = enabledTools.map { it.name }
            assertTrue(names.containsAll(listOf("use_skill", "skill_create", "skill_write_file", "text_to_speech", "whisper_status", "transcribe_audio_file")))
            assertTrue(factory.getTools(assistant.localTools).none { isSkillTool(it.name) }) // unknown caller cannot inherit another profile
            assistant = assistant.copy(disabledLocalTools = setOf("text_to_speech", "whisper_status", "skill_delete", "termux_skill_sync", "conversation_history_read"))
            save()
            names = tools().map { it.name }
            assertFalse("text_to_speech" in names || "whisper_status" in names || "skill_delete" in names || "conversation_history_read" in names)
            assertTrue("transcribe_audio_file" in names && "termux_run_command" in names && "skill_write_file" in names)
            // An already-constructed tool cannot bypass a later toggle or approval pause.
            val staleResult = enabledTools.first { it.name == "text_to_speech" }.execute(buildJsonObject { put("text", "Must not speak") })
            assertTrue((staleResult.single() as UIMessagePart.Text).text.contains("tool_disabled"))
            // use_skill remains available, but a stale closure must not auto-sync after sync was disabled.
            val withoutSync = enabledTools.first { it.name == "use_skill" }.execute(buildJsonObject { put("name", name) })
            assertEquals(1, withoutSync.size)
            assertTrue((withoutSync.single() as UIMessagePart.Text).text.contains("Hello"))
            assistant = assistant.copy(disabledLocalTools = setOf("use_skill"))
            save()
            assertFalse(tools().any { isSkillTool(it.name) })
            assistant = assistant.copy(disabledLocalTools = emptySet(), enabledSkills = emptySet())
            save()
            assertFalse(tools().any { isSkillTool(it.name) })
        } finally {
            manager.deleteSkill(createdName)
            manager.deleteSkill(name)
            settings.update { it.copy(assistants = it.assistants.filterNot { a -> a.id == id }) }
        }
    }

    @Test fun russianToolPickerSearchAndIndependentToggles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("ru")) })
        val tools = listOf(
            Tool("text_to_speech", "Озвучить текст с помощью настроенного синтеза речи.", execute = { emptyList() }),
            Tool("transcribe_audio_file", "Расшифровать аудиофайл через Whisper в Termux.", execute = { emptyList() }),
            Tool("whisper_status", "Проверить готовность Whisper и наличие модели.", execute = { emptyList() }),
            Tool("skill_write_file", "Создать или изменить файл подключённого навыка с проверкой ревизии.", execute = { emptyList() }),
        )
        val disabled = mutableStateOf(emptySet<String>())
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration, LocalResources provides localized.resources) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface {
                        ToolAccessList(tools, disabled.value, onToggle = { name, enabled -> disabled.value = if (enabled) disabled.value - name else disabled.value + name })
                    }
                }
            }
        }
        compose.onNodeWithTag("tool-toggle-text_to_speech").performClick().assertIsOff()
        compose.onNodeWithTag("tool-toggle-transcribe_audio_file").assertIsOn()
        compose.onNodeWithTag("tool-access-search").performTextInput("whisper")
        compose.onNodeWithTag("tool-toggle-whisper_status").performClick().assertIsOff()
        compose.onNodeWithTag("tool-toggle-transcribe_audio_file").assertIsOn()
        compose.onNodeWithTag("tool-access-search").performTextClearance()
        val output = File(context.filesDir, "trajectory-qa/tool-access-russian.png").apply { parentFile!!.mkdirs() }
        compose.onNodeWithTag("tool-access-list").captureToImage().asAndroidBitmap().let { bitmap -> output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        assertEquals(setOf("text_to_speech", "whisper_status"), disabled.value)
    }
}
