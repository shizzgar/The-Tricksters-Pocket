package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.ai.prompts.NETBRO_SYSTEM_PROMPT
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import kotlin.uuid.Uuid

internal val NETBRO_ASSISTANT_ID = Uuid.parse("9ff4aded-07df-51e0-8049-19c4937f63a2")
internal val NETBRO_SKILLS = setOf(
    "netbro-workflow", "netbro-environment", "netbro-bbot",
    "netbro-nmap", "netbro-nuclei", "netbro-legba",
)

internal fun createNetbroAssistant() = Assistant(
    id = NETBRO_ASSISTANT_ID,
    name = "NetBro",
    avatar = Avatar.Emoji("🛰️"),
    useAssistantAvatar = true,
    systemPrompt = NETBRO_SYSTEM_PROMPT,
    localTools = listOf(LocalToolOption.Termux),
    enableWebSearch = true,
    enabledSkills = NETBRO_SKILLS,
)
