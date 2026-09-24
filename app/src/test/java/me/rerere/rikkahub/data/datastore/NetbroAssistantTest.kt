package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.shouldUseExternalWebSearch
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class NetbroAssistantTest {
    @Test fun `fresh defaults include one NetBro with its own tools and six skills`() {
        val defaults = mergeDefaultAssistants(emptyList(), DEFAULT_AUTO_ENABLED_SKILLS)
        val netbro = defaults.single { it.id == NETBRO_ASSISTANT_ID }
        assertEquals("NetBro", netbro.name)
        assertEquals(6, netbro.enabledSkills.size)
        assertEquals(NETBRO_SKILLS, netbro.enabledSkills)
        assertEquals(listOf(LocalToolOption.Termux), netbro.localTools)
        assertTrue(shouldUseExternalWebSearch(netbro, Model()))
        assertNull(netbro.chatModelId)
        assertTrue(netbro.useAssistantAvatar)
        assertTrue(netbro.disabledLocalTools.isEmpty())
        assertEquals(defaults.size, defaults.map { it.id }.distinct().size)
        assertTrue(defaults.filter { it.id != NETBRO_ASSISTANT_ID }.none { it.enabledSkills.any(NETBRO_SKILLS::contains) })
    }

    @Test fun `upgrade preserves ReBro and same-named custom profiles without mixing kits`() {
        val rebro = createRebroAssistant().copy(systemPrompt = "My RE rules", enabledSkills = setOf("rebro-frida"))
        val custom = Assistant(name = "NetBro", systemPrompt = "Private profile", enabledSkills = setOf("private-net"))
        val oldDefault = DEFAULT_ASSISTANTS.first().copy(enabledSkills = emptySet())
        val upgraded = mergeDefaultAssistants(listOf(oldDefault, rebro, custom), DEFAULT_AUTO_ENABLED_SKILLS)
        assertEquals(rebro, upgraded.single { it.id == REBRO_ASSISTANT_ID })
        assertEquals(custom, upgraded.single { it.id == custom.id })
        assertEquals(oldDefault, upgraded.single { it.id == oldDefault.id })
        assertEquals(createNetbroAssistant(), upgraded.single { it.id == NETBRO_ASSISTANT_ID })
        assertEquals(upgraded, mergeDefaultAssistants(upgraded, emptySet()))
    }

    @Test fun `saved NetBro edits and an explicitly empty skill selection survive restore`() {
        val edited = createNetbroAssistant().copy(
            name = "My NetBro", systemPrompt = "My network rules", enabledSkills = emptySet(),
            localTools = emptyList(), enableWebSearch = false,
            disabledLocalTools = setOf("termux_run_command", "use_skill"),
        )
        val restored = JsonInstant.decodeFromString<Assistant>(JsonInstant.encodeToString(edited))
        val merged = mergeDefaultAssistants(listOf(restored), DEFAULT_AUTO_ENABLED_SKILLS)
        assertEquals(edited, merged.single { it.id == NETBRO_ASSISTANT_ID })
    }

    @Test fun `partially disabled NetBro kit is not re-enabled by general skill migration`() {
        val edited = createNetbroAssistant().copy(enabledSkills = setOf("netbro-nmap"))
        val merged = mergeDefaultAssistants(listOf(edited), DEFAULT_AUTO_ENABLED_SKILLS)
        assertEquals(edited, merged.single { it.id == NETBRO_ASSISTANT_ID })
        assertEquals(REBRO_SKILLS, merged.single { it.id == REBRO_ASSISTANT_ID }.enabledSkills)
    }

    @Test fun `prompt routes to packaged skills and live environment rather than device pins`() {
        val prompt = createNetbroAssistant().systemPrompt
        for (skill in NETBRO_SKILLS) assertTrue(skill, prompt.contains(skill))
        for (contract in listOf("skill_root", "termux_skill_sync", "search_web", "operation_id", "job_id", "{{device_info}}", "{{model_name}}")) {
            assertTrue(contract, prompt.contains(contract))
        }
        assertFalse(prompt.contains("SM-S928B"))
        assertFalse(prompt.contains("127.0.0.1:27044"))
    }
}
