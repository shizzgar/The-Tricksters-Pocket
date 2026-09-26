package me.rerere.rikkahub.data.model

import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.DEVBRO_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.OPSBRO_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.REBRO_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.NETBRO_ASSISTANT_ID
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceShellStatus

enum class AssistantReadinessIssue { MODEL_MISSING, MODEL_DISABLED, MODEL_NOT_CHAT, MODEL_TOOLS,
    WORKSPACE_MISSING, WORKSPACE_BROKEN, EXECUTION_MISSING, SKILLS_MISSING, SKILLS_UNKNOWN }

/** Configuration readiness, never a claim that a network, credential or shell live probe passed. */
data class AssistantReadiness(
    val modelName: String?,
    val workspaceName: String?,
    val termuxWorkspace: Boolean,
    val executionConfigured: Boolean,
    val readOnly: Boolean,
    val searchEnabled: Boolean,
    val enabledSkillCount: Int,
    val missingSkills: Set<String>,
    val issues: List<AssistantReadinessIssue>,
) {
    val configured: Boolean get() = issues.isEmpty()
}

fun assistantReadiness(
    assistant: Assistant,
    settings: Settings,
    workspaces: List<WorkspaceEntity>,
    installedSkillNames: Set<String>?,
): AssistantReadiness {
    val selectedId = assistant.chatModelId ?: settings.chatModelId
    val provider = settings.providers.firstOrNull { provider -> provider.models.any { it.id == selectedId } }
    val model = provider?.models?.firstOrNull { it.id == selectedId }
    val workspace = workspaces.firstOrNull { it.id == assistant.workspaceId?.toString() }
    val termux = workspace?.termuxPath != null
    val standaloneShell = (LocalToolOption.Termux in assistant.localTools || termux) && "termux_run_command" !in assistant.disabledLocalTools
    val workspaceShell = workspace != null && (termux || workspace.shellStatus == WorkspaceShellStatus.READY.name) &&
        "workspace_shell" !in assistant.disabledLocalTools
    val execution = !assistant.readOnlyTools && (standaloneShell || workspaceShell)
    val missingSkills = installedSkillNames?.let { assistant.enabledSkills - it }.orEmpty()
    val technicalExecutor = assistant.id in setOf(DEVBRO_ASSISTANT_ID, OPSBRO_ASSISTANT_ID, REBRO_ASSISTANT_ID, NETBRO_ASSISTANT_ID)
    val issues = buildList {
        when {
            model == null -> add(AssistantReadinessIssue.MODEL_MISSING)
            provider?.enabled == false -> add(AssistantReadinessIssue.MODEL_DISABLED)
            model.type != ModelType.CHAT -> add(AssistantReadinessIssue.MODEL_NOT_CHAT)
            (assistant.localTools.isNotEmpty() || assistant.enabledSkills.isNotEmpty() || workspace != null) &&
                ModelAbility.TOOL !in model.abilities -> add(AssistantReadinessIssue.MODEL_TOOLS)
        }
        if (assistant.workspaceId != null && workspace == null) add(AssistantReadinessIssue.WORKSPACE_MISSING)
        if (workspace?.shellStatus == WorkspaceShellStatus.BROKEN.name) add(AssistantReadinessIssue.WORKSPACE_BROKEN)
        if (technicalExecutor && !execution && !assistant.readOnlyTools) add(AssistantReadinessIssue.EXECUTION_MISSING)
        if (missingSkills.isNotEmpty()) add(AssistantReadinessIssue.SKILLS_MISSING)
        if (installedSkillNames == null && assistant.enabledSkills.isNotEmpty()) add(AssistantReadinessIssue.SKILLS_UNKNOWN)
    }
    return AssistantReadiness(model?.let { it.displayName.ifBlank { it.modelId } }, workspace?.name, termux,
        execution, assistant.readOnlyTools, assistant.enableWebSearch, assistant.enabledSkills.size, missingSkills, issues)
}
