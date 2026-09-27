package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

const val TOOL_HOOK_MAX_PROMPT_CHARS = 16_000
const val TOOL_HOOK_MAX_MATCH_CHARS = 1_024
const val TOOL_HOOK_MAX_FIRINGS_PER_TURN = 10

/** User-authored instructions activated by a completed tool observation. No executable hooks. */
@Serializable
data class ToolHook(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val enabled: Boolean = true,
    val priority: Int = 0,
    val scope: ToolHookScope = ToolHookScope(),
    val condition: ToolHookCondition = ToolHookCondition(),
    val action: ToolHookAction = ToolHookAction(),
    val maxFiringsPerTurn: Int = 1,
)

/** Selectors are alternatives; subagent inheritance is opt-in. Empty scope matches nothing. */
@Serializable
data class ToolHookScope(
    val global: Boolean = false,
    val assistantIds: Set<Uuid> = emptySet(),
    val workspaceIds: Set<String> = emptySet(),
    val conversationIds: Set<Uuid> = emptySet(),
    val inheritToSubagents: Boolean = false,
)

@Serializable
data class ToolHookCondition(
    val toolNames: Set<String> = emptySet(),
    val commandExecutable: String = "",
    val commandContains: String = "",
    val outcome: ToolHookOutcome = ToolHookOutcome.NONZERO_EXIT,
    val stdoutContains: String = "",
    val stderrContains: String = "",
    val caseSensitive: Boolean = false,
)

@Serializable
enum class ToolHookOutcome { COMPLETED, NONZERO_EXIT, SUCCESS, TOOL_ERROR, TIMEOUT }

@Serializable
data class ToolHookAction(
    val type: ToolHookActionType = ToolHookActionType.INLINE_PROMPT,
    val prompt: String = "",
    val skillName: String = "",
    val skillPath: String = "SKILL.md",
    val skillSection: String = "",
)

@Serializable
enum class ToolHookActionType { INLINE_PROMPT, SKILL }
