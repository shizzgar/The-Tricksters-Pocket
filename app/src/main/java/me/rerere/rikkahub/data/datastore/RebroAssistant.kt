package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.ai.prompts.REBRO_SYSTEM_PROMPT
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.seedDefaultAssistantSkills
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
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

/** Add missing built-ins without replacing user-edited profiles or mixing their skill sets. */
internal fun mergeDefaultAssistants(
    saved: List<Assistant>,
    skillsToSeed: Set<String>,
): List<Assistant> {
    val defaults = DEFAULT_ASSISTANTS.associateBy { it.id }
    val present = saved.map { it.id }.toSet()
    return (saved + DEFAULT_ASSISTANTS.filter { it.id !in present }).map { assistant ->
        val additions = skillsToSeed intersect defaults[assistant.id]?.enabledSkills.orEmpty()
        seedDefaultAssistantSkills(assistant, additions)
    }
}
