package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.subagent.SubAgentProfile
import me.rerere.rikkahub.subagent.seedTechnicalSubAgents
import org.junit.Assert.*
import org.junit.Test

class TechnicalAssistantsTest {
    @Test fun `crew has distinct procedures and verification denies execution by default`() {
        val presets = DEFAULT_TECHNICAL_ASSISTANTS
        assertEquals(3, presets.map { it.id }.distinct().size)
        assertEquals(setOf("devbro-workflow", "opsbro-workflow", "verifybro-workflow"), presets.flatMap { it.enabledSkills }.toSet())
        assertTrue(createVerifybroAssistant().readOnlyTools)
        assertFalse(LocalToolOption.Termux in createVerifybroAssistant().localTools)
        assertFalse(LocalToolOption.SubAgents in createVerifybroAssistant().localTools)
        assertTrue(LocalToolOption.Ssh in createOpsbroAssistant().localTools)
    }

    @Test fun `technical seed preserves exact edits and does not reintroduce deleted assistants`() {
        val custom = createDevbroAssistant().copy(name = "My developer", systemPrompt = "My rules", enabledSkills = emptySet(), localTools = emptyList())
        val seeded = seedTechnicalAssistants(listOf(custom), false)
        assertEquals(custom, seeded.first { it.id == custom.id })
        assertEquals(3, seeded.size)
        val deleted = seeded.filterNot { it.id == OPSBRO_ASSISTANT_ID }
        assertEquals(deleted, seedTechnicalAssistants(deleted, true))
        assertEquals(seeded, seedTechnicalAssistants(seeded, false))
    }

    @Test fun `custom case insensitive name prevents ambiguous assistant and dispatch duplicates`() {
        val custom = Assistant(name = "devbro", systemPrompt = "User-owned")
        val assistants = seedTechnicalAssistants(listOf(custom), false)
        assertEquals(custom, assistants.single { it.name.equals("devbro", true) })
        val disabled = SubAgentProfile(name = "opsbro", enabled = false, systemPrompt = "Mine")
        val profiles = seedTechnicalSubAgents(listOf(disabled), assistants)
        assertEquals(disabled, profiles.single { it.name.equals("opsbro", true) })
        assertFalse(profiles.any { it.assistantId == DEVBRO_ASSISTANT_ID })
        assertEquals(profiles, seedTechnicalSubAgents(profiles, assistants))
    }

    @Test fun `orchestrator stock migration preserves capability opt outs and customized instructions`() {
        val current = createOrchbroAssistant()
        val original = current.systemPrompt.replace(
            "practical everyday work, ThinkBro explanations, DevBro source-code development, OpsBro\nenvironments and services, and VerifyBro read-only independent review, only when present\nin the live roster. VerifyBro cannot execute tests in read-only mode; request an explicitly\nexecution-enabled specialist for fresh runs and label unverified checks honestly.",
            "practical everyday work, and ThinkBro explanations, only when present in the live roster."
        )
        val old = current.copy(systemPrompt = original, localTools = emptyList(), enabledSkills = emptySet())
        val migrated = migratePocketAssistant(old, current)
        assertEquals(current.systemPrompt, migrated.systemPrompt)
        assertTrue(migrated.localTools.isEmpty())
        assertTrue(migrated.enabledSkills.isEmpty())
        val edited = old.copy(systemPrompt = original + "\nMy own requirement")
        assertEquals(edited, migratePocketAssistant(edited, current))
    }
}
