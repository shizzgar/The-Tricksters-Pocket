package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.subagent.seedPocketSubAgents
import me.rerere.rikkahub.subagent.SubAgentProfile
import org.junit.Assert.*
import org.junit.Test

class PocketAssistantsTest {
    @Test fun `generic profiles migrate without resetting user capabilities`() {
        val old = Assistant(id = DEFAULT_ASSISTANT_ID, localTools = emptyList(), enabledSkills = emptySet())
        val updated = mergeDefaultAssistants(listOf(old), emptySet()).first { it.id == old.id }
        assertEquals("PocketBro", updated.name)
        assertEquals(old.localTools, updated.localTools)
        assertEquals(old.enabledSkills, updated.enabledSkills)
        val edited = updated.copy(name = "Mine", systemPrompt = "My rules", description = "My purpose", avatar = Avatar.Emoji("X"))
        assertEquals(edited, mergeDefaultAssistants(listOf(edited), emptySet()).first { it.id == edited.id })
        val think = Assistant(id = THINKBRO_ASSISTANT_ID, systemPrompt = LEGACY_THINK_PROMPT)
        assertEquals(createThinkbroAssistant().systemPrompt, mergeDefaultAssistants(listOf(think), emptySet()).first { it.id == think.id }.systemPrompt)
    }
    @Test fun `orchestrator uses live roster and specialist tools remain with specialists`() {
        val orch = createOrchbroAssistant()
        assertTrue(LocalToolOption.SubAgents in orch.localTools)
        assertFalse(LocalToolOption.Termux in orch.localTools)
        assertTrue(orch.systemPrompt.contains("current enabled profile"))
        assertTrue(orch.description.isNotBlank())
    }
    @Test fun `new profile seed is idempotent and preserves edited names and disabled profiles`() {
        val seeded = seedPocketSubAgents(emptyList())
        assertEquals(seeded, seedPocketSubAgents(seeded))
        val disabled = seeded.first().copy(name = "My Pocket", enabled = false)
        val custom = SubAgentProfile(name = "thinkbro", systemPrompt = "Mine", enabled = false)
        assertEquals(listOf(disabled, custom), seedPocketSubAgents(listOf(disabled, custom)))
    }
    @Test fun `legacy bundled avatar migrates and custom avatar survives`() {
        val old = createNetbroAssistant().copy(avatar = Avatar.Image("file:///android_asset/branding/netbro-avatar.webp"))
        assertEquals(createNetbroAssistant(), mergeDefaultAssistants(listOf(old), emptySet()).first { it.id == old.id })
        val custom = old.copy(avatar = Avatar.Image("file:///custom.png"))
        assertEquals(custom, mergeDefaultAssistants(listOf(custom), emptySet()).first { it.id == old.id })
    }
}
