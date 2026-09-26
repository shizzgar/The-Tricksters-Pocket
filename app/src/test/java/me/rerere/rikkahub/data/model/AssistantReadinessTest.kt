package me.rerere.rikkahub.data.model

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.createDevbroAssistant
import me.rerere.rikkahub.data.datastore.createVerifybroAssistant
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantReadinessTest {
    private val model = Model(modelId = "test", displayName = "Test model", abilities = listOf(ModelAbility.TOOL))
    private val provider = ProviderSetting.OpenAI(name = "Test", models = listOf(model))
    private val settings = Settings(chatModelId = model.id, providers = listOf(provider))
    private val dev = createDevbroAssistant()
    private fun inspect(assistant: Assistant = dev, s: Settings = settings, ws: List<WorkspaceEntity> = emptyList(), skills: Set<String>? = assistant.enabledSkills) =
        assistantReadiness(assistant, s, ws, skills)

    @Test fun `unresolvable model and disabled provider are actionable without silently falling back`() {
        assertTrue(AssistantReadinessIssue.MODEL_MISSING in inspect(dev.copy(chatModelId = Uuid.random())).issues)
        assertTrue(AssistantReadinessIssue.MODEL_DISABLED in inspect(s = settings.copy(providers = listOf(provider.copy(enabled = false)))).issues)
        assertTrue(AssistantReadinessIssue.MODEL_NOT_CHAT in inspect(s = settings.copy(providers = listOf(provider.copy(models = listOf(model.copy(type = ModelType.EMBEDDING)))))).issues)
        assertTrue(AssistantReadinessIssue.MODEL_TOOLS in inspect(s = settings.copy(providers = listOf(provider.copy(models = listOf(model.copy(abilities = emptyList())))))).issues)
    }

    @Test fun `Termux workspace supplies execution without standalone integration`() {
        val workspace = WorkspaceEntity(Uuid.random().toString(), "My work", "root", createdAt = 0, updatedAt = 0, termuxPath = "/data/data/com.termux/files/home/project")
        val bound = dev.copy(workspaceId = Uuid.parse(workspace.id), localTools = listOf(LocalToolOption.Files))
        val status = inspect(bound, ws = listOf(workspace))
        assertTrue(status.configured)
        assertTrue(status.termuxWorkspace)
        assertTrue(status.executionConfigured)
        assertTrue(AssistantReadinessIssue.EXECUTION_MISSING in inspect(bound.copy(disabledLocalTools = setOf("workspace_shell", "termux_run_command")), ws = listOf(workspace)).issues)
        assertTrue(AssistantReadinessIssue.WORKSPACE_MISSING in inspect(bound).issues)
    }

    @Test fun `missing versus uninspected skills remain distinct and read only is not treated as broken`() {
        val reviewer = createVerifybroAssistant()
        assertTrue(inspect(reviewer).configured)
        assertTrue(inspect(reviewer).readOnly)
        assertFalse(inspect(reviewer.copy(localTools = listOf(LocalToolOption.Termux))).executionConfigured)
        assertTrue(AssistantReadinessIssue.SKILLS_MISSING in inspect(skills = emptySet()).issues)
        assertTrue(AssistantReadinessIssue.SKILLS_UNKNOWN in inspect(skills = null).issues)
        assertFalse(AssistantReadinessIssue.EXECUTION_MISSING in inspect(dev.copy(readOnlyTools = true, localTools = emptyList())).issues)
    }
}
