package me.rerere.rikkahub.subagent

import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class BroSubAgentProfilesTest {
    @Test fun `built-ins resolve their complete saved assistants`() {
        val rebro = createRebroAssistant().copy(systemPrompt = "My rules", enabledSkills = setOf("my-skill"), enableWebSearch = false)
        val netbro = createNetbroAssistant().copy(localTools = emptyList())
        val parent = Assistant(name = "Parent")
        val assistants = listOf(parent, rebro, netbro)
        val profiles = defaultBroSubAgents()
        assertSame(rebro, resolveSubAgentAssistant(parent.id, profiles[0], assistants))
        assertSame(netbro, resolveSubAgentAssistant(parent.id, profiles[1], assistants))
        assertSame(parent, resolveSubAgentAssistant(parent.id, SubAgentProfile(), assistants))
    }

    @Test fun `profile links round trip and legacy profiles inherit parent`() {
        val profile = defaultBroSubAgents()[1]
        assertEquals(profile, JsonInstant.decodeFromString<SubAgentProfile>(JsonInstant.encodeToString(profile)))
        assertNull(JsonInstant.decodeFromString<SubAgentProfile>("{\"name\":\"legacy\"}").assistantId)
    }

    @Test(expected = IllegalStateException::class) fun `missing link never falls back to parent capabilities`() {
        val parent = Assistant()
        resolveSubAgentAssistant(parent.id, defaultBroSubAgents()[0], listOf(parent))
    }

    @Test fun `seeding preserves edits disabled profiles and custom same-name profiles`() {
        val custom = SubAgentProfile(name = "netbro", systemPrompt = "custom", enabled = false)
        val rebro = defaultBroSubAgents()[0].copy(name = "My ReBro", enabled = false)
        assertEquals(listOf(custom, rebro), seedBroSubAgents(listOf(custom, rebro)))
        assertEquals(defaultBroSubAgents(), seedBroSubAgents(emptyList()))
        assertEquals(defaultBroSubAgents(), seedBroSubAgents(defaultBroSubAgents()))
    }

    @Test fun `avatar upgrade changes only original NetBro satellite`() {
        val old = createNetbroAssistant().copy(avatar = Avatar.Emoji("🛰️"), systemPrompt = "custom")
        val updated = mergeDefaultAssistants(listOf(old), emptySet()).single { it.id == old.id }
        assertEquals(old.copy(avatar = createNetbroAssistant().avatar), updated)
        val custom = old.copy(avatar = Avatar.Image("file:///my-avatar.png"))
        assertEquals(custom, mergeDefaultAssistants(listOf(custom), emptySet()).single { it.id == custom.id })
    }
}
