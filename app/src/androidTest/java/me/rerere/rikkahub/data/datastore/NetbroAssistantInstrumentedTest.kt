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

class NetbroAssistantInstrumentedTest {
    @Test fun bundledProfileInstallsCompletePackagesAndExposesRequestedTools() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settingsStore = GlobalContext.get().get<SettingsStore>()
        val manager = GlobalContext.get().get<SkillManager>()
        val factory = GlobalContext.get().get<ChatToolFactory>()
        withTimeout(15_000) { settingsStore.settingsFlow.first { !it.init } }
        manager.seedDefaultSkillsIfNeeded()
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.assistants.single { it.id == NETBRO_ASSISTANT_ID }
        assertEquals(createNetbroAssistant(), assistant)
        val catalog = context.assets.open("assistant-presets/netbro/catalog.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject.getValue("packages").jsonArray
        }
        assertEquals(NETBRO_SKILLS, catalog.map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet())
        val installed = manager.listSkills().associateBy { it.name }
        for (item in catalog) {
            val entry = item.jsonObject
            val name = entry.getValue("name").jsonPrimitive.content
            val skill = requireNotNull(installed[name]) { "Not installed: $name" }
            assertEquals(name, SkillFrontmatterParser.parse(skill.skillFile.readText())["name"])
            val files = SkillPackage.readFiles(skill.skillDir)
            val manifestBytes = files.getValue("MANIFEST.sha256")
            assertEquals(entry.getValue("manifest_sha256").jsonPrimitive.content, SkillPackage.digest(manifestBytes))
            val manifest = manifestBytes.toString(Charsets.UTF_8).lineSequence()
                .filter { it.isNotBlank() }.map { it.split(Regex("\\s+"), limit = 2) }.toList()
            assertEquals(manifest.map { it[1] }.toSet() + "MANIFEST.sha256", files.keys)
            assertEquals(entry.getValue("files").jsonPrimitive.int, files.size)
            assertEquals(entry.getValue("bytes").jsonPrimitive.long, files.values.sumOf { it.size.toLong() })
            manifest.forEach { assertEquals("$name/${it[1]}", it[0], SkillPackage.digest(files.getValue(it[1]))) }
        }
        val names = factory.createTools(settings, assistant, Model(modelId = "netbro-fixture")).map { it.name }
        assertTrue(names.containsAll(listOf("search_web", "termux_run_command", "termux_job_start", "use_skill", "termux_skill_sync")))
        assertFalse(names.any { it in setOf("text_to_speech", "whisper_status", "transcribe_audio_file", "skill_create") })
    }

    @Test fun disablingNetbroSkillsAndToolGroupsRemovesThemFromTheActualToolSet() = runBlocking {
        val store = GlobalContext.get().get<SettingsStore>()
        val factory = GlobalContext.get().get<ChatToolFactory>()
        val before = withTimeout(15_000) { store.settingsFlow.first { !it.init } }
        val disabled = before.assistants.single { it.id == NETBRO_ASSISTANT_ID }.copy(
            enabledSkills = emptySet(), localTools = emptyList(), enableWebSearch = false,
        )
        try {
            store.update { it.copy(assistants = it.assistants.map { a -> if (a.id == disabled.id) disabled else a }) }
            val updated = withTimeout(15_000) {
                store.settingsFlow.first { it.assistants.single { a -> a.id == disabled.id } == disabled }
            }
            val names = factory.createTools(updated, disabled, Model(modelId = "netbro-fixture")).map { it.name }
            assertFalse(names.any { it == "search_web" || it == "use_skill" || it.startsWith("termux_") || it.startsWith("skill_") })
        } finally {
            store.update { before }
            withTimeout(15_000) { store.settingsFlow.first { it.assistants == before.assistants } }
        }
    }
}
