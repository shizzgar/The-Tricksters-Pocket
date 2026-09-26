package me.rerere.rikkahub.subagent

import me.rerere.rikkahub.data.datastore.REBRO_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.NETBRO_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.THINKBRO_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.DEVBRO_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.OPSBRO_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.VERIFYBRO_ASSISTANT_ID
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

internal fun defaultBroSubAgents() = listOf(
    SubAgentProfile(id = Uuid.parse("a5a9fd79-b5d7-4a2e-80d4-1359ad9abe71"), name = "ReBro",
        description = "Android analysis and development with ReBro skills, Termux and Local search.",
        assistantId = REBRO_ASSISTANT_ID),
    SubAgentProfile(id = Uuid.parse("c7b00951-38e6-4386-93bc-941b3c7496d1"), name = "NetBro",
        description = "Network investigation with NetBro skills, Termux and Local search.",
        assistantId = NETBRO_ASSISTANT_ID),
)

/** Seed once without replacing custom profiles or introducing an ambiguous dispatch name. */
internal fun seedBroSubAgents(saved: List<SubAgentProfile>): List<SubAgentProfile> =
    saved + defaultBroSubAgents().filter { preset ->
        saved.none { it.id == preset.id || it.name.equals(preset.name, ignoreCase = true) }
    }

internal fun resolveSubAgentAssistant(
    parentId: Uuid, profile: SubAgentProfile?, assistants: List<Assistant>,
): Assistant = assistants.firstOrNull { it.id == (profile?.assistantId ?: parentId) }
    ?: error("The assistant linked to this sub-agent profile is missing. Select an existing assistant in Settings > Sub-agents.")

internal fun seedPocketSubAgents(saved: List<SubAgentProfile>): List<SubAgentProfile> {
    val additions = listOf(
        SubAgentProfile(id = Uuid.parse("cb3f9297-d8fd-41c5-9867-e327c1876553"), name = "PocketBro",
            description = "Everyday writing, planning and practical tasks.", assistantId = DEFAULT_ASSISTANT_ID),
        SubAgentProfile(id = Uuid.parse("5445d039-4b1d-4429-af30-956473f59ee4"), name = "ThinkBro",
            description = "Explanations, comparisons, learning and testing ideas.", assistantId = THINKBRO_ASSISTANT_ID),
    )
    return saved + additions.filter { preset -> saved.none { it.id == preset.id || it.name.equals(preset.name, true) } }
}

internal fun seedTechnicalSubAgents(saved: List<SubAgentProfile>, assistants: List<Assistant>): List<SubAgentProfile> {
    val additions = listOf(
        SubAgentProfile(id = Uuid.parse("e254baad-c796-4406-9915-52f123e9aba1"), name = "DevBro",
            description = "Source-code development: repository discovery, bug fixes, tests and builds.", assistantId = DEVBRO_ASSISTANT_ID),
        SubAgentProfile(id = Uuid.parse("b8d372a0-b9ee-42d5-9b18-70ff2c791627"), name = "OpsBro",
            description = "Environment operations: Termux/Linux/SSH diagnosis, configuration and recovery.", assistantId = OPSBRO_ASSISTANT_ID),
        SubAgentProfile(id = Uuid.parse("73e6d5a8-b9f2-4f9a-91c4-452976954759"), name = "VerifyBro",
            description = "Read-only independent verification: acceptance criteria, evidence, findings and unverified limits.", assistantId = VERIFYBRO_ASSISTANT_ID),
    )
    return saved + additions.filter { preset ->
        assistants.any { it.id == preset.assistantId } &&
            saved.none { it.id == preset.id || it.name.equals(preset.name, ignoreCase = true) }
    }
}
