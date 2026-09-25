package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import kotlin.uuid.Uuid

fun Assistant.bindWorkspace(workspace: WorkspaceEntity?): Assistant = copy(
    workspaceId = workspace?.id?.let(Uuid::parse),
    localTools = if (workspace?.termuxPath != null) localTools - LocalToolOption.Termux else localTools,
)
