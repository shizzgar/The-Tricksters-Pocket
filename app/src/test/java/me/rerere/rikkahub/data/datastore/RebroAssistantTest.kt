package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.shouldUseExternalWebSearch
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class RebroAssistantTest {
    @Test fun `fresh defaults include one ReBro with the kit and requested tool groups`() {
        val defaults = mergeDefaultAssistants(emptyList(), DEFAULT_AUTO_ENABLED_SKILLS)
        val rebro = defaults.single { it.id == REBRO_ASSISTANT_ID }
        assertEquals("ReBro", rebro.name)
        assertEquals(10, rebro.enabledSkills.size)
        assertEquals(REBRO_SKILLS, rebro.enabledSkills)
        assertEquals(listOf(LocalToolOption.Termux), rebro.localTools)
        assertTrue(shouldUseExternalWebSearch(rebro, Model()))
        assertNull(rebro.chatModelId) // Inherit the configured model; do not add a new provider.
        assertTrue(rebro.disabledLocalTools.isEmpty())
        assertTrue(rebro.useAssistantAvatar)
        assertEquals(defaults.size, defaults.map { it.id }.distinct().size)
    }

    @Test fun `upgrade adds ReBro once and leaves existing profiles untouched`() {
        val original = DEFAULT_ASSISTANTS.first().copy(systemPrompt = "User's prompt", enabledSkills = emptySet())
        val custom = Assistant(name = "ReBro", enabledSkills = setOf("private-skill"))
        val upgraded = mergeDefaultAssistants(listOf(original, custom), DEFAULT_AUTO_ENABLED_SKILLS)
        assertEquals(original, upgraded.single { it.id == original.id })
        assertEquals(custom, upgraded.single { it.id == custom.id })
        assertEquals(REBRO_SKILLS, upgraded.single { it.id == REBRO_ASSISTANT_ID }.enabledSkills)
        assertTrue(upgraded.filter { it.id != REBRO_ASSISTANT_ID }.none { it.enabledSkills.any(REBRO_SKILLS::contains) })
        assertEquals(upgraded, mergeDefaultAssistants(upgraded, emptySet()))
    }

    @Test fun `edited ReBro prompt tools and disabled skills survive reseeding and backup`() {
        val edited = createRebroAssistant().copy(
            name = "My ReBro", systemPrompt = "My operating rules", enabledSkills = emptySet(),
            localTools = emptyList(), enableWebSearch = false, disabledLocalTools = setOf("termux_run_command"),
        )
        val saved = JsonInstant.decodeFromString<Assistant>(JsonInstant.encodeToString(edited))
        val merged = mergeDefaultAssistants(listOf(saved), DEFAULT_AUTO_ENABLED_SKILLS)
        assertEquals(edited, merged.single { it.id == REBRO_ASSISTANT_ID })
    }

    @Test fun `general default skill migration stays scoped to the owning preset`() {
        val old = DEFAULT_ASSISTANTS.first().copy(enabledSkills = setOf("agent-core"))
        val merged = mergeDefaultAssistants(listOf(old, createRebroAssistant()), DEFAULT_AUTO_ENABLED_SKILLS)
        assertEquals(setOf("agent-core") + DEFAULT_AUTO_ENABLED_SKILLS, merged.first { it.id == old.id }.enabledSkills)
        assertEquals(REBRO_SKILLS, merged.first { it.id == REBRO_ASSISTANT_ID }.enabledSkills)
    }

    @Test fun `prompt delegates detailed procedures and dated inventory to skills`() {
        val prompt = createRebroAssistant().systemPrompt
        for (name in REBRO_SKILLS) assertTrue(name, prompt.contains(name))
        for (text in listOf(
            "references/operating-rules.md", "skill_root", "termux_skill_sync", "search_web",
            "operation_id", "stdout_next_cursor", "{{device_info}}", "{{model_name}}",
        )) assertTrue(text, prompt.contains(text))
        assertFalse(prompt.contains("SM-S928B"))
        assertFalse(prompt.contains("c377c4bb2eb42bfc99a89bb68bc65d4581f1fd84057b23459887d1da3da3fd87"))
        assertTrue(prompt.length < legacyPrompt("rebro").length / 2)
    }

    @Test fun `both profiles combine skills and search in the shared evidence policy`() {
        val policy = me.rerere.rikkahub.data.ai.prompts.BRO_EVIDENCE_POLICY
        for (assistant in listOf(createRebroAssistant(), createNetbroAssistant())) {
            assertTrue(assistant.name, assistant.systemPrompt.contains(policy))
            assertTrue(assistant.systemPrompt.contains("normally Russian"))
            assertFalse(Regex("[\\u0400-\\u04ff]").containsMatchIn(assistant.systemPrompt))
        }
        assertTrue(policy.contains("Start from the connected skills"))
        assertTrue(policy.contains("Local search does not replace them"))
        assertTrue(policy.contains("version-matched primary sources"))
        assertTrue(policy.contains("Reuse already loaded skills"))
    }

    @Test fun `exact legacy factory prompts upgrade without changing preferences`() {
        for ((name, preset) in listOf("rebro" to createRebroAssistant(), "netbro" to createNetbroAssistant())) {
            val saved = preset.copy(
                name = "My " + preset.name, systemPrompt = legacyPrompt(name),
                enabledSkills = emptySet(), localTools = emptyList(), enableWebSearch = false,
                disabledLocalTools = setOf("use_skill", "termux_run_command"),
            )
            val restored = JsonInstant.decodeFromString<Assistant>(JsonInstant.encodeToString(saved))
            val upgraded = mergeDefaultAssistants(listOf(restored), DEFAULT_AUTO_ENABLED_SKILLS)
            assertEquals(saved.copy(systemPrompt = preset.systemPrompt), upgraded.single { it.id == saved.id })
            assertEquals(upgraded, mergeDefaultAssistants(upgraded, DEFAULT_AUTO_ENABLED_SKILLS))
        }
    }

    @Test fun `even a small user edit prevents automatic prompt replacement`() {
        for ((name, preset) in listOf("rebro" to createRebroAssistant(), "netbro" to createNetbroAssistant())) {
            for (prompt in listOf(legacyPrompt(name) + "\n", "", "My procedure")) {
                val saved = preset.copy(systemPrompt = prompt)
                val upgraded = mergeDefaultAssistants(listOf(saved), emptySet())
                assertEquals(saved, upgraded.single { it.id == saved.id })
            }
        }
    }

    @Test fun `copied legacy prompts in custom profiles do not migrate by name`() {
        for (name in listOf("rebro", "netbro")) {
            val custom = Assistant(name = name, systemPrompt = legacyPrompt(name))
            assertEquals(custom, mergeDefaultAssistants(listOf(custom), emptySet()).single { it.id == custom.id })
        }
    }

    private fun legacyPrompt(name: String): String = requireNotNull(
        javaClass.getResource("/assistant-prompts/$name-legacy.txt")
    ).readText(Charsets.UTF_8)
}
