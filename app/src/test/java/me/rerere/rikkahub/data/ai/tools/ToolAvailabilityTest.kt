package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class ToolAvailabilityTest {
    @Test fun `no connected or resolvable skills means no skill groups or prompts`() {
        val all = listOf(LocalToolOption.SkillManagement, LocalToolOption.SkillImport, LocalToolOption.JsSkills, LocalToolOption.Termux)
        assertEquals(listOf(LocalToolOption.Termux), availableLocalOptions(all, emptySet(), setOf("installed")))
        assertEquals(listOf(LocalToolOption.Termux), availableLocalOptions(all, setOf("deleted"), setOf("installed")))
        assertTrue(createSkillTools(setOf("deleted"), emptyList()).isEmpty())
        assertEquals(all, availableLocalOptions(all, setOf("installed"), setOf("installed")))
    }
    @Test fun `whisper is an explicit opt in and depends on Termux`() {
        assertEquals(listOf(LocalToolOption.Termux), availableLocalOptions(listOf(LocalToolOption.Termux), emptySet(), emptySet()))
        assertTrue(availableLocalOptions(listOf(LocalToolOption.Whisper), emptySet(), emptySet()).isEmpty())
        assertEquals(2, availableLocalOptions(listOf(LocalToolOption.Termux, LocalToolOption.Whisper), emptySet(), emptySet()).size)
    }
    @Test fun `individual exclusions remove schemas and use skill exclusion removes the whole surface`() {
        val tools = listOf("text_to_speech", "whisper_status", "transcribe_audio_file", "termux_run_command", "use_skill", "skill_create", "skill_read_file", "run_js", "termux_skill_sync").map { Tool(it, "test", execute = { emptyList() }) }
        val filtered = filterLocalTools(tools, setOf("text_to_speech", "whisper_status", "use_skill")).map { it.name }
        assertEquals(listOf("transcribe_audio_file", "termux_run_command"), filtered)
    }
    @Test fun `empty saved skill selection stays empty and tool exclusions survive backup`() {
        val assistant = Assistant(localTools = listOf(LocalToolOption.SkillManagement, LocalToolOption.Whisper), disabledLocalTools = setOf("text_to_speech", "skill_delete"))
        assertTrue(seedDefaultAssistantSkills(assistant, setOf("agent-core", "new-builtin")).enabledSkills.isEmpty())
        assertEquals(setOf("chosen", "new-builtin"), seedDefaultAssistantSkills(assistant.copy(enabledSkills = setOf("chosen")), setOf("new-builtin")).enabledSkills)
        assertEquals(assistant, JsonInstant.decodeFromString<Assistant>(JsonInstant.encodeToString(assistant)))
        assertTrue(Assistant().localTools.none { it == LocalToolOption.Whisper || it == LocalToolOption.SkillManagement || it == LocalToolOption.Tts })
    }
}
