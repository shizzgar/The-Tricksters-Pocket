package me.rerere.rikkahub.ui.components.ai

import android.content.Context
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.AssistantReadiness
import me.rerere.rikkahub.data.model.AssistantReadinessIssue

internal fun AssistantReadiness.summary(context: Context): String = buildList {
    add(context.getString(R.string.crew_model, modelName ?: context.getString(R.string.crew_not_selected)))
    add(when {
        workspaceName != null -> context.getString(if (termuxWorkspace) R.string.crew_workspace_termux else R.string.crew_workspace, workspaceName)
        executionConfigured -> context.getString(R.string.crew_termux_configured)
        else -> context.getString(R.string.crew_no_workspace)
    })
    add(context.getString(if (readOnly) R.string.crew_read_only else R.string.crew_standard_access))
    add(context.getString(if (searchEnabled) R.string.crew_search_on else R.string.crew_search_off))
    add(context.getString(R.string.crew_skills, enabledSkillCount))
}.joinToString(" · ")

internal fun AssistantReadiness.issueText(context: Context): String = issues.joinToString("; ") {
    context.getString(when (it) {
        AssistantReadinessIssue.MODEL_MISSING -> R.string.crew_issue_model
        AssistantReadinessIssue.MODEL_DISABLED -> R.string.crew_issue_provider
        AssistantReadinessIssue.MODEL_NOT_CHAT -> R.string.crew_issue_chat_model
        AssistantReadinessIssue.MODEL_TOOLS -> R.string.crew_issue_tools
        AssistantReadinessIssue.WORKSPACE_MISSING -> R.string.crew_issue_workspace
        AssistantReadinessIssue.WORKSPACE_BROKEN -> R.string.crew_issue_workspace_broken
        AssistantReadinessIssue.EXECUTION_MISSING -> R.string.crew_issue_execution
        AssistantReadinessIssue.SKILLS_MISSING -> R.string.crew_issue_skills
        AssistantReadinessIssue.SKILLS_UNKNOWN -> R.string.crew_issue_skills_unknown
    }) + if (it == AssistantReadinessIssue.SKILLS_MISSING) ": ${missingSkills.sorted().joinToString()}" else ""
}
