package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.ai.prompts.REBRO_SYSTEM_PROMPT
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.seedDefaultAssistantSkills
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import java.security.MessageDigest
import kotlin.uuid.Uuid

internal val REBRO_ASSISTANT_ID = Uuid.parse("3484eb32-762e-5567-bb0f-b181a6b4fcce")
internal val REBRO_SKILLS = setOf(
    "rebro-workflow", "rebro-environment", "rebro-acquire", "rebro-analyze",
    "rebro-patch", "rebro-build", "rebro-sign", "rebro-install", "rebro-verify", "rebro-frida",
)

internal fun createRebroAssistant() = Assistant(
    id = REBRO_ASSISTANT_ID,
    name = "ReBro",
    avatar = Avatar.Image("file:///android_asset/branding/rebro-avatar.webp"),
    useAssistantAvatar = true,
    systemPrompt = REBRO_SYSTEM_PROMPT,
    localTools = listOf(LocalToolOption.Termux),
    enableWebSearch = true,
    enabledSkills = REBRO_SKILLS,
)

/** Only exact shipped prompts migrate; every other profile field remains user-owned. */
private fun migrateBundledBroPrompt(assistant: Assistant, default: Assistant?): Assistant {
    val legacyHash = when (assistant.id) {
        REBRO_ASSISTANT_ID -> "62052bf6d2c2ed8928bcfd65014ee0bab1d7b22db980ea6283376f057c154af9"
        NETBRO_ASSISTANT_ID -> "08311f51d83cee51f11204bd0e54f00661bea1bead4c74a12cb3f76a8200ca3a"
        else -> return assistant
    }
    if (default == null || assistant.systemPrompt == default.systemPrompt) return assistant
    val hash = MessageDigest.getInstance("SHA-256")
        .digest(assistant.systemPrompt.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return if (hash == legacyHash) assistant.copy(systemPrompt = default.systemPrompt) else assistant
}

/** Add missing built-ins without replacing user edits or mixing their skill sets. */
internal fun mergeDefaultAssistants(
    saved: List<Assistant>,
    skillsToSeed: Set<String>,
): List<Assistant> {
    val defaults = DEFAULT_ASSISTANTS.associateBy { it.id }
    val present = saved.map { it.id }.toSet()
    return (saved + DEFAULT_ASSISTANTS.filter { it.id !in present }).map { assistant ->
        val additions = skillsToSeed intersect defaults[assistant.id]?.enabledSkills.orEmpty()
        val branded = if (assistant.id == NETBRO_ASSISTANT_ID && assistant.avatar == Avatar.Emoji("🛰️")) {
            assistant.copy(avatar = createNetbroAssistant().avatar)
        } else assistant
        seedDefaultAssistantSkills(migrateBundledBroPrompt(branded, defaults[assistant.id]), additions)
    }
}
