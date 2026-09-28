package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.repository.EffectiveChatEnvironment

/** A read-only inspection of the same environment and declarations used for the next chat turn. */
data class ChatEnvironmentSnapshot(
    val environment: EffectiveChatEnvironment,
    val readiness: AssistantReadiness,
    val workingDirectory: String?,
    val availableTools: List<String>,
    val excludedTools: List<String>,
    val policyBlockedTools: List<String>,
    val enabledSkills: List<String>,
    val skillPromptAvailable: Boolean,
)
