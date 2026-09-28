package me.rerere.rikkahub.context

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.AgentTaskPolicy
import me.rerere.rikkahub.data.ai.ScopedAgentPolicy
import me.rerere.rikkahub.data.ai.tools.ProjectReferenceTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.*
import me.rerere.rikkahub.service.ChatService
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/** Real project store, Room, tool execution and fork boundaries. No model/network request. */
class ProjectContinuityInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val koin get() = GlobalContext.get()
    private val conversations get() = koin.get<ConversationRepository>()
    private val projects get() = koin.get<ProjectRepository>()
    private fun conversation(assistant: Assistant, parent: Uuid? = null, cwd: String? = null) = Conversation(
        assistantId = assistant.id, parentConversationId = parent, workspaceCwd = cwd,
        messageNodes = listOf(MessageNode(messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Project fixture")))))),
    )

    @Test fun forkOfInheritedProjectChatKeepsProjectAndWorkingDirectoryAfterReload() = runBlocking {
        val service = koin.get<ChatService>()
        val assistant = Assistant(workspaceId = Uuid.random())
        val root = conversation(assistant)
        val child = conversation(assistant, root.id, "/project/nested/repo")
        val project = PocketProject(name = "Fork project fixture", workspaceId = Uuid.random().toString(), instructions = "Shared fixture context")
        var fork: Conversation? = null
        try {
            conversations.insertConversation(root)
            service.saveConversation(child.id, child)
            projects.save(project); projects.bindConversation(project.id, root.id)
            fork = service.forkConversationAtMessage(child.id, child.currentMessages.single().id)
            val restored = ProjectRepository(context, conversations)
            assertEquals(project.id, restored.projectForConversation(fork.id)?.id)
            assertEquals(child.workspaceCwd, conversations.getConversationById(fork.id)?.workspaceCwd)
            assertEquals(Uuid.parse(project.workspaceId!!), restored.effectiveAssistant(fork.id, assistant, Settings(assistants = listOf(assistant))).workspaceId)
            assertNull(fork.parentConversationId)
        } finally {
            projects.remove(project.id)
            service.dropSession(child.id)
            fork?.let { service.dropSession(it.id); conversations.deleteConversation(it) }
            conversations.deleteConversation(child); conversations.deleteConversation(root)
        }
    }

    @Test fun forkWithoutProjectClearsWorkingDirectoryWhenParentWorkspaceInheritanceIsLost() = runBlocking {
        val store = koin.get<SettingsStore>()
        val original = withTimeout(15000) { store.settingsFlow.first { !it.init } }
        val service = koin.get<ChatService>()
        val parentAssistant = Assistant(name = "Parent fixture", workspaceId = Uuid.random())
        val childAssistant = Assistant(name = "Child fixture", workspaceId = null)
        val parent = conversation(parentAssistant)
        val child = conversation(childAssistant, parent.id, "/parent/work/repo")
        var fork: Conversation? = null
        try {
            store.update(original.copy(assistants = original.assistants + parentAssistant + childAssistant))
            withTimeout(15000) { store.settingsFlow.first { settings -> settings.assistants.any { it.id == childAssistant.id } } }
            conversations.insertConversation(parent); service.saveConversation(child.id, child)
            assertEquals(parentAssistant.workspaceId, projects.effectiveAssistant(child.id, childAssistant, store.settingsFlow.value).workspaceId)
            fork = service.forkConversationAtMessage(child.id, child.currentMessages.single().id)
            assertNull(conversations.getConversationById(fork.id)?.workspaceCwd)
        } finally {
            service.dropSession(child.id)
            fork?.let { service.dropSession(it.id); conversations.deleteConversation(it) }
            conversations.deleteConversation(child); conversations.deleteConversation(parent)
            store.update(original)
            withTimeout(15000) { store.settingsFlow.first { settings -> settings.assistants.none { it.id == childAssistant.id } } }
        }
    }

    @Test fun draftChildInheritsCwdUsingEffectiveWorkspaceAndRespectsScopedOverride() = runBlocking {
        val parentAssistant = Assistant(workspaceId = Uuid.random())
        val childAssistant = Assistant(workspaceId = Uuid.random())
        val parent = conversation(parentAssistant, cwd = "/project/repo/subdir")
        val draft = conversation(childAssistant, parent.id)
        val settings = Settings(assistants = listOf(parentAssistant, childAssistant))
        val project = PocketProject(name = "Delegate fixture", workspaceId = Uuid.random().toString())
        try {
            conversations.insertConversation(parent)
            projects.save(project); projects.bindConversation(project.id, parent.id)
            assertFalse(conversations.existsConversationById(draft.id))
            assertEquals(parent.workspaceCwd, projects.inheritedWorkingDirectory(parent, draft, parentAssistant, childAssistant, settings))
            val effective = projects.effectiveEnvironment(draft.id, childAssistant, settings, draft)
            assertEquals(WorkspaceSource.PROJECT, effective.workspaceSource)
            assertEquals(project.id, effective.project?.id)
            AgentTaskPolicy.initialize(context.filesDir)
            AgentTaskPolicy.set(draft.id.toString(), ScopedAgentPolicy(2, scopedWorkspaceId = Uuid.random().toString(), readOnly = true))
            assertNull(projects.inheritedWorkingDirectory(parent, draft, parentAssistant, childAssistant, settings))
            assertTrue(projects.effectiveEnvironment(draft.id, childAssistant, settings, draft).readOnly)
        } finally {
            AgentTaskPolicy.clear(draft.id.toString())
            projects.remove(project.id); conversations.deleteConversation(parent)
        }
    }

    @Test fun projectReferenceToolReadsWithoutMountAndUnlinkRevokesAlreadyBuiltTool() = runBlocking {
        val files = koin.get<FilesManager>()
        val managed = files.saveManagedFromBytes(FileFolders.UPLOAD, "Readable without Termux mount".toByteArray(), "reference.txt", "text/plain")
        val local = files.getFile(managed)
        val chat = conversation(Assistant())
        val project = PocketProject(name = "Reference fixture", workspaceId = Uuid.random().toString(), files = listOf(ProjectReferenceFile(managed.displayName, managed.relativePath, managed.mimeType)))
        try {
            conversations.insertConversation(chat)
            projects.save(project); projects.bindConversation(project.id, chat.id)
            val tool = ProjectReferenceTools.create(chat.id, projects, context.filesDir).single()
            val args = buildJsonObject { put("reference_path", managed.relativePath) }
            val result = tool.execute(args).single() as UIMessagePart.Text
            assertEquals("Readable without Termux mount", Json.parseToJsonElement(result.text).jsonObject["reference_data"]?.jsonPrimitive?.content)
            projects.removeFile(project.id, managed.relativePath)
            // An editor opened before unlink must not restore its stale reference list.
            projects.save(project.copy(name = "Edited reference fixture"))
            assertTrue(projects.projects.value.first { it.id == project.id }.files.isEmpty())
            assertTrue(ProjectReferenceTools.create(chat.id, projects, context.filesDir).isEmpty())
            var revoked = false
            try { tool.execute(args) } catch (_: IllegalStateException) { revoked = true }
            assertTrue(revoked)
            assertTrue(local.exists())
            projects.remove(project.id)
            assertNull(projects.projectForConversation(chat.id))
            assertTrue(conversations.existsConversationById(chat.id))
            assertTrue(local.exists())
        } finally {
            projects.remove(project.id); conversations.deleteConversation(chat); files.delete(managed.id)
        }
    }
    @Test fun projectWorkspaceChangesResetLiveAndSavedCwdButUnrelatedEditsPreserveIt() = runBlocking {
        val service = koin.get<ChatService>()
        val assistant = Assistant()
        val parent = conversation(assistant, cwd = "/old/root")
        val child = conversation(assistant, parent.id, "/old/child")
        val scoped = conversation(assistant, parent.id, "/review/root")
        val project = PocketProject(name = "Workspace edit fixture", workspaceId = Uuid.random().toString())
        try {
            service.saveConversation(parent.id, parent)
            service.saveConversation(child.id, child)
            service.saveConversation(scoped.id, scoped)
            projects.save(project); projects.bindConversation(project.id, parent.id)
            AgentTaskPolicy.initialize(context.filesDir)
            AgentTaskPolicy.set(scoped.id.toString(), ScopedAgentPolicy(10, readOnly = true, scopedWorkspaceId = Uuid.random().toString()))
            service.saveProjectContext(project.copy(instructions = "Only instructions changed"))
            assertEquals(parent.workspaceCwd, service.getConversationFlow(parent.id).value.workspaceCwd)
            val updated = project.copy(workspaceId = Uuid.random().toString())
            service.saveProjectContext(updated)
            assertNull(service.getConversationFlow(parent.id).value.workspaceCwd)
            assertNull(service.getConversationFlow(child.id).value.workspaceCwd)
            assertNull(conversations.getConversationById(child.id)?.workspaceCwd)
            assertEquals(scoped.workspaceCwd, service.getConversationFlow(scoped.id).value.workspaceCwd)
            service.saveConversation(parent.id, service.getConversationFlow(parent.id).value.copy(workspaceCwd = "/new/root"))
            service.bindProjectContext(null, parent.id)
            assertNull(service.getConversationFlow(parent.id).value.workspaceCwd)
            service.bindProjectContext(project.id, parent.id)
            service.saveConversation(parent.id, service.getConversationFlow(parent.id).value.copy(workspaceCwd = "/new/root"))
            service.removeProjectContext(project.id)
            assertNull(service.getConversationFlow(parent.id).value.workspaceCwd)
            assertEquals(scoped.workspaceCwd, service.getConversationFlow(scoped.id).value.workspaceCwd)
        } finally {
            projects.remove(project.id)
            listOf(child, scoped, parent).forEach { service.dropSession(it.id); conversations.deleteConversation(it) }
        }
    }

}
