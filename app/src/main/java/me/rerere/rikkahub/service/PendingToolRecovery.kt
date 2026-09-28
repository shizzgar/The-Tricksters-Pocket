package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools

/** Shared by service recovery and its approval/resume regression tests. Stop remains unconditional. */
internal fun repairInterruptedToolBatch(
    message: UIMessage,
    approvalContinuation: Boolean,
    cancelTool: (UIMessagePart.Tool) -> UIMessagePart.Tool,
): UIMessage {
    val tools = message.getTools()
    // The loop pauses the whole batch before executing its Auto siblings. An explicit
    // approval decision resumes that batch; it is not recovery from a killed process.
    if (approvalContinuation && tools.none { it.isPending } && tools.any { it.canResumeExecution }) {
        return message
    }
    return if (tools.any { !it.isExecuted && !it.approvalState.canResumeToolExecution() }) {
        message.finishPendingTools(cancelTool)
    } else message
}
