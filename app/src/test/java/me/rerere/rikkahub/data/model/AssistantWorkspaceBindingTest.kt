package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantWorkspaceBindingTest {
    @Test fun bindingTermuxDisablesOnlySeparateIntegrationAndUnlinkDoesNotReenableIt() {
        val workspace = WorkspaceEntity(Uuid.random().toString(), "Project", "project", createdAt = 1, updatedAt = 1, termuxPath = "/data/data/com.termux/files/home/project")
        val assistant = Assistant(localTools = listOf(LocalToolOption.Termux, LocalToolOption.TimeInfo))
        val bound = assistant.bindWorkspace(workspace)
        assertEquals(listOf(LocalToolOption.TimeInfo), bound.localTools)
        assertEquals(Uuid.parse(workspace.id), bound.workspaceId)
        val unbound = bound.bindWorkspace(null)
        assertNull(unbound.workspaceId)
        assertEquals(bound.localTools, unbound.localTools)
        assertEquals(assistant.localTools, assistant.bindWorkspace(workspace.copy(termuxPath = null)).localTools)
    }
}
