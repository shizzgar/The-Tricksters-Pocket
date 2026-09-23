package me.rerere.rikkahub.skills

import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillManager
import kotlin.uuid.Uuid

internal class AssistantSkillAccess(
    private val manager: SkillManager,
    private val settings: SettingsStore,
    private val assistantId: Uuid,
) : SkillManagementAccess {
    private fun assistant() = settings.settingsFlow.value.assistants.firstOrNull { it.id == assistantId }
    override fun allowed(tool: String): Boolean {
        val assistant = assistant() ?: return false
        return LocalToolOption.SkillManagement in assistant.localTools &&
            tool !in assistant.disabledLocalTools && "use_skill" !in assistant.disabledLocalTools && skills().isNotEmpty()
    }
    override fun skills() = manager.listSkills().filter { it.name in assistant()?.enabledSkills.orEmpty() }
    override fun workspace(name: String) = manager.workspace(name)
    override suspend fun create(name: String, files: Map<String, ByteArray>) {
        manager.createPackage(name, files)
        settings.update { current -> current.copy(assistants = current.assistants.map {
            if (it.id == assistantId) it.copy(enabledSkills = it.enabledSkills + name) else it
        }) }
    }
    override suspend fun delete(name: String, revision: String) = manager.deleteSkill(name, revision)
    override fun changed(name: String) = manager.invalidateSkill(name)
}
