package me.rerere.rikkahub.subagent

import me.rerere.rikkahub.data.datastore.REBRO_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.NETBRO_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.THINKBRO_ASSISTANT_ID
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
