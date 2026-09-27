package me.rerere.rikkahub.data.ai.hooks

import me.rerere.rikkahub.data.model.TOOL_HOOK_MAX_FIRINGS_PER_TURN
import me.rerere.rikkahub.data.model.TOOL_HOOK_MAX_MATCH_CHARS
import me.rerere.rikkahub.data.model.ToolHook
import me.rerere.rikkahub.data.model.ToolHookOutcome
import me.rerere.rikkahub.data.model.ToolHookScope
import kotlin.uuid.Uuid

data class ToolHookScopeContext(
    val assistantId: Uuid,
    val workspaceId: String? = null,
    val conversationId: Uuid,
    val isSubagent: Boolean = false,
    val parentAssistantIds: Set<Uuid> = emptySet(),
    val parentWorkspaceIds: Set<String> = emptySet(),
    val ancestorConversationIds: Set<Uuid> = emptySet(),
)

data class ToolHookEvaluation(val matched: Boolean, val reasons: List<String>)

/** All condition fields are ANDed; each tool-name or scope selector set is an OR. */
object ToolHookMatcher {
    fun evaluate(rule: ToolHook, event: ToolHookEvent, scope: ToolHookScopeContext): ToolHookEvaluation {
        val reasons = mutableListOf<String>()
        var matched = true
        fun check(value: Boolean, success: String, failure: String) {
            reasons += if (value) success else failure
            if (!value) matched = false
        }
        check(rule.enabled, "Rule is enabled", "Rule is disabled")
        check(matchesScope(rule.scope, scope), "Scope matches", "Outside the selected scope")
        val condition = rule.condition
        val valid = rule.maxFiringsPerTurn in 1..TOOL_HOOK_MAX_FIRINGS_PER_TURN &&
            condition.toolNames.size <= 32 && condition.toolNames.all { it.isNotBlank() && it.length <= 128 } &&
            condition.commandExecutable.length <= 256 &&
            listOf(condition.commandContains, condition.stdoutContains, condition.stderrContains).all {
                it.length <= TOOL_HOOK_MAX_MATCH_CHARS
            }
        check(valid, "Condition limits are valid", "Condition exceeds supported limits")
        if (!valid) return ToolHookEvaluation(false, reasons)
        check(condition.toolNames.isEmpty() || event.toolName in condition.toolNames || event.originToolName in condition.toolNames,
            "Tool name matches", "Tool name does not match")
        val executable = condition.commandExecutable.trim()
        if (executable.isNotEmpty()) check(event.executableNames.any { it.equals(executable, !condition.caseSensitive) },
            "Executable matches: $executable", "Executable does not match: $executable")
        fun contains(needle: String, haystack: String, label: String) {
            if (needle.isNotEmpty()) check(haystack.contains(needle, ignoreCase = !condition.caseSensitive),
                "$label contains the configured text", "$label does not contain the configured text")
        }
        contains(condition.commandContains, event.command, "Command")
        val outcomeMatches = when (condition.outcome) {
            ToolHookOutcome.COMPLETED -> event.completed
            ToolHookOutcome.NONZERO_EXIT -> event.completed && !event.toolError && !event.timedOut && event.exitCode != null && event.exitCode != 0
            ToolHookOutcome.SUCCESS -> event.successful
            ToolHookOutcome.TOOL_ERROR -> event.toolError
            ToolHookOutcome.TIMEOUT -> event.timedOut
        }
        check(outcomeMatches, "Outcome matches: ${condition.outcome}", "Outcome does not match: ${condition.outcome}")
        contains(condition.stdoutContains, event.stdout, "Standard output")
        contains(condition.stderrContains, event.stderr, "Standard error")
        return ToolHookEvaluation(matched, reasons)
    }

    fun matchesScope(scope: ToolHookScope, context: ToolHookScopeContext): Boolean {
        if (scope.global && (!context.isSubagent || scope.inheritToSubagents)) return true
        if (context.assistantId in scope.assistantIds || context.workspaceId in scope.workspaceIds ||
            context.conversationId in scope.conversationIds) return true
        if (!context.isSubagent || !scope.inheritToSubagents) return false
        return scope.assistantIds.any { it in context.parentAssistantIds } ||
            scope.workspaceIds.any { it in context.parentWorkspaceIds } ||
            scope.conversationIds.any { it in context.ancestorConversationIds }
    }
}
