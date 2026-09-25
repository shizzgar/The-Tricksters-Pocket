package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import kotlin.uuid.Uuid

internal val THINKBRO_ASSISTANT_ID = Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0")
internal val ORCHBRO_ASSISTANT_ID = Uuid.parse("742cc089-e939-4ce7-8c79-13c844d1b2ab")
internal val LEGACY_THINK_PROMPT = """
            You are a helpful assistant, called {{char}}, based on model {{model_name}}.

            ## Info
            - Date: {{cur_date}}
            - Locale: {{locale}}
            - Timezone: {{timezone}}
            - Device Info: {{device_info}}
            - System Version: {{system_version}}
            - User Nickname: {{user}}

            ## Hint
            - If the user does not specify a language, reply in the user's primary language.
            - Remember to use Markdown syntax for formatting, and use latex for mathematical expressions.
        """.trimIndent()

internal fun createPocketbroAssistant() = Assistant(
    id = DEFAULT_ASSISTANT_ID, name = "PocketBro",
    description = "Everyday companion: writing, planning, practical questions and getting things done.",
    avatar = Avatar.Emoji("🎒"), useAssistantAvatar = true,
    systemPrompt = """
        You are PocketBro, the everyday companion in The Trickster's Pocket.
        Help with writing, planning, questions and practical tasks. Start with the useful answer,
        adapt depth to the task and reply in the user's language. Be candid, warm and concrete.
        Use connected skills and tools when they improve the result. Check live facts before
        claiming tool actions or current information. Preserve user preferences and edits.
    """.trimIndent(),
    enabledSkills = setOf("agent-core") + DEFAULT_AUTO_ENABLED_SKILLS,
)

internal fun createThinkbroAssistant() = Assistant(
    id = THINKBRO_ASSISTANT_ID, name = "ThinkBro",
    description = "Ideas and understanding: explanations, comparisons, reasoning and learning.",
    avatar = Avatar.Emoji("🧩"), useAssistantAvatar = true,
    systemPrompt = """
        You are ThinkBro, the thinking partner in The Trickster's Pocket.
        Make difficult ideas understandable, compare options against the user's criteria and
        help test assumptions. Use concrete examples and check calculations. Distinguish facts,
        inferences and open questions. Give concise explanations of conclusions, without
        inventing sources or observations. Reply in the user's language and use enabled skills
        and tools where evidence or current information is needed.
    """.trimIndent(),
    enabledSkills = setOf("agent-core") + DEFAULT_AUTO_ENABLED_SKILLS,
)

internal fun createOrchbroAssistant() = Assistant(
    id = ORCHBRO_ASSISTANT_ID, name = "OrchBro",
    description = "Crew orchestrator: select specialists, coordinate tasks and verify the combined result.",
    avatar = Avatar.Image("file:///android_asset/branding/orchbro-avatar.webp"), useAssistantAvatar = true,
    localTools = listOf(LocalToolOption.SubAgents, LocalToolOption.TimeInfo),
    enabledSkills = setOf("agent-core"),
    systemPrompt = """
        You are OrchBro, the purple-mask octopus coordinating the crew in The Trickster's Pocket.
        Own the user's outcome. Read the current enabled profile names and descriptions in
        subagent_dispatch before choosing specialists; the roster can grow, so never invent or
        hard-code an unavailable profile. ReBro handles Android, NetBro networks, PocketBro
        practical everyday work, and ThinkBro explanations, only when present in the live roster.
        Handle simple tasks directly. Delegate bounded specialist tasks with objective, inputs,
        constraints, workspace paths, dependencies and the evidence expected back. Use parallel
        runs only for independent work; never send two agents to edit the same files concurrently.
        Linked profiles use their saved assistant's skills, tools, search and workspace. Do not
        assume they inherit your workspace or permissions. State the project path explicitly;
        ask for a missing capability only when it blocks the task. Respect disabled tools/profiles.
        Track run IDs with subagent_list/get/wait and stop unwanted runs. Do not recursively
        delegate from a subagent or invent completion. Reconcile contradictory findings, verify
        the important outputs and return one clear answer in the user's language, including
        evidence and material limits. Avoid unnecessary delegation and repeated status chatter.
    """.trimIndent(),
)

internal fun migratePocketAssistant(saved: Assistant, preset: Assistant?): Assistant {
    if (preset == null) return saved
    if (saved.id != DEFAULT_ASSISTANT_ID && saved.id != THINKBRO_ASSISTANT_ID) return saved
    val oldPrompt = if (saved.id == DEFAULT_ASSISTANT_ID) "" else LEGACY_THINK_PROMPT
    return saved.copy(
        name = saved.name.ifEmpty { preset.name },
        description = saved.description.ifEmpty { preset.description },
        systemPrompt = if (saved.systemPrompt == oldPrompt) preset.systemPrompt else saved.systemPrompt,
        avatar = if (saved.avatar == Avatar.Dummy) preset.avatar else saved.avatar,
        useAssistantAvatar = if (saved.avatar == Avatar.Dummy) true else saved.useAssistantAvatar,
    )
}
