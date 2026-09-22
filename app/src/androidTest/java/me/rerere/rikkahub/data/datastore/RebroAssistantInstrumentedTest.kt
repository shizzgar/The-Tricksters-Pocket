package me.rerere.rikkahub.data.datastore

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.skills.SkillPackage
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext

class RebroAssistantInstrumentedTest {
    @Test fun bundledProfileInstallsCompleteKitAndExposesTermuxAndLocalSearch() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settingsStore = GlobalContext.get().get<SettingsStore>()
        val manager = GlobalContext.get().get<SkillManager>()
        val factory = GlobalContext.get().get<ChatToolFactory>()
        withTimeout(15_000) { settingsStore.settingsFlow.first { !it.init } }
        manager.seedDefaultSkillsIfNeeded()
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.assistants.single { it.id == REBRO_ASSISTANT_ID }
        assertEquals(createRebroAssistant(), assistant)
        val catalog = context.assets.open("assistant-presets/rebro/catalog.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject.getValue("packages").jsonArray
        }
        assertEquals(REBRO_SKILLS, catalog.map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet())
        val skills = manager.listSkills().associateBy { it.name }
        for (item in catalog) {
            val entry = item.jsonObject
            val name = entry.getValue("name").jsonPrimitive.content
            val skill = requireNotNull(skills[name]) { "Not installed: $name" }
            assertEquals(name, SkillFrontmatterParser.parse(skill.skillFile.readText())["name"])
            val files = SkillPackage.readFiles(skill.skillDir)
            assertEquals(entry.getValue("files").jsonPrimitive.int, files.size)
            files.getValue("MANIFEST.sha256").toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.forEach { line ->
                val fields = line.split(Regex("\\s+"), limit = 2)
                val path = fields[1].removePrefix("*")
                assertEquals("$name/$path", fields[0], SkillPackage.digest(files.getValue(path)))
            }
        }
        val names = factory.createTools(settings, assistant, Model(modelId = "rebro-fixture")).map { it.name }
        assertTrue(names.containsAll(listOf("search_web", "termux_run_command", "termux_job_start", "use_skill", "termux_skill_sync")))
        assertFalse(names.any { it in setOf("text_to_speech", "whisper_status", "transcribe_audio_file", "skill_create") })
    }
}
