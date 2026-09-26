package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.task.TaskArtifactStore
import kotlin.uuid.Uuid

object TaskArtifactTools {
    fun create(conversationId: Uuid, assistant: Assistant, repository: WorkspaceRepository, store: TaskArtifactStore): List<Tool> {
        val workspaceId = assistant.workspaceId?.toString() ?: return emptyList()
        return listOf(Tool(
            name = "register_artifact",
            description = "Register an existing completed file as a task result, including files produced by shell/build tools. Reads the file to record its SHA-256, workspace, source assistant and chat. Does not mark its contents as verified. Only register after its producing command succeeds. Users can open, save, share and choose an assistant to review results from the task dashboard.",
            parameters = { InputSchema.Obj(properties = buildJsonObject {
                put("path", buildJsonObject { put("type", "string"); put("description", "Absolute normalized path in this assistant's workspace") })
                put("label", buildJsonObject { put("type", "string"); put("description", "Short human-readable result name") })
            }, required = listOf("path")) },
            needsApproval = { false },
            execute = { input ->
                val path = input.jsonObject["path"]?.jsonPrimitive?.contentOrNull ?: error("path is required")
                val label = input.jsonObject["label"]?.jsonPrimitive?.contentOrNull ?: path.substringAfterLast('/')
                val artifact = store.register(conversationId.toString(), assistant.id.toString(), workspaceId, path, repository, label)
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("registered", true); put("path", path); put("workspace_id", workspaceId)
                    put("sha256", artifact.sha256); put("sizeBytes", artifact.sizeBytes); put("verification", "not_checked")
                }.toString()))
            },
        ))
    }
}
