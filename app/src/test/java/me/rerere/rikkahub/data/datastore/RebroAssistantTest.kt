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

    @Test fun `prompt preserves operating rules and pins while reflecting the supplied kit inventory`() {
        val prompt = createRebroAssistant().systemPrompt
        assertEquals(21, Regex("(?m)^## [0-9]+\\.").findAll(prompt).count())
        for (name in REBRO_SKILLS) assertTrue(name, prompt.contains(name))
        for (text in listOf(
            "SM-S928B", "Android **16 / API 36**", "127.0.0.1:27044", "excp.rikkahub.debug",
            "ripgrep 15.2.0", "apksigner 37.0.0", "skill_root", "termux_skill_sync", "search_web",
            "c377c4bb2eb42bfc99a89bb68bc65d4581f1fd84057b23459887d1da3da3fd87",
            "6be272a9e37d5c8e230a922e95a3ac3f803053ea11236fe0bff0e251b00429ad",
            "d3550a89c0cdf32717417f3fdf116e1b462e7c1feb434fbd61ac7f7747eec61a",
            "{{device_info}}", "{{model_name}}", "\$HOME", "\$REBRO_APK",
        )) assertTrue(text, prompt.contains(text))
        assertFalse(prompt.contains("Future skill integration is not assumed"))
        assertFalse(prompt.contains("No skill, helper repository"))
    }
}
