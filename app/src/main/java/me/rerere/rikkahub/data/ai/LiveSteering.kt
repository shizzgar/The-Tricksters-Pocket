package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** An in-flight or approval-blocked call must finish before the history can accept input. */
internal fun canSteerAtBoundary(messages: List<UIMessage>): Boolean = messages.none { message ->
    message.getTools().any { !it.isExecuted && (it.isPending || it.executionStartedAt != null) }
}

/** Close announced but unstarted calls so the next request has a result for every tool_call. */
internal fun supersedeUnstartedTools(messages: List<UIMessage>): List<UIMessage> {
    check(canSteerAtBoundary(messages))
    return messages.map { message ->
        message.copy(parts = message.parts.map { part ->
            if (part !is UIMessagePart.Tool || part.isExecuted) part else when (val decision = part.approvalState) {
                // A completed human decision is already a result. Never replace an ask_user
                // answer or an explicit denial with a generic steering placeholder.
                is ToolApprovalState.Answered -> part.copy(output = listOf(UIMessagePart.Text(decision.answer)))
                is ToolApprovalState.Denied -> part.copy(output = listOf(UIMessagePart.Text(buildJsonObject {
                    put("error", "Tool execution denied by user. Reason: ${decision.reason.ifBlank { "No reason provided" }}")
                }.toString())))
                else -> part.copy(
                    approvalState = ToolApprovalState.Denied("Superseded by a user update before execution"),
                    output = listOf(UIMessagePart.Text(buildJsonObject {
                        put("status", "not_executed")
                        put("reason", "superseded_by_user_input")
                        put("detail", "New user input arrived before this call started. Reconsider the remaining actions using that input; this call did not run.")
                    }.toString())),
                )
            }
        })
    }
}

internal fun applyCompletedTools(messages: List<UIMessage>, completed: List<UIMessagePart.Tool>): List<UIMessage> {
    if (completed.isEmpty() || messages.isEmpty()) return messages
    val byId = completed.associateBy { it.toolCallId }
    val last = messages.last()
    return messages.dropLast(1) + last.copy(parts = last.parts.map {
        if (it is UIMessagePart.Tool) byId[it.toolCallId] ?: it else it
    })
}
