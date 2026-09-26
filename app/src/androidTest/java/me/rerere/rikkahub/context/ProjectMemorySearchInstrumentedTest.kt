package me.rerere.rikkahub.context

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.fts.WorkSearchFilter
import me.rerere.rikkahub.data.db.fts.WorkSearchKind
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.*
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

class ProjectMemorySearchInstrumentedTest {
    @Test fun projectMembershipSurvivesReloadAndIncludesChildChat() = runBlocking {
        val conversations = GlobalContext.get().get<ConversationRepository>()
        val projects = GlobalContext.get().get<ProjectRepository>()
        val assistant = Uuid.random()
        val root = Conversation(assistantId = assistant, messageNodes = emptyList())
        val child = Conversation(assistantId = assistant, parentConversationId = root.id, messageNodes = emptyList())
        val project = PocketProject(name = "Context fixture", instructions = "Keep checks reproducible", knowledge = "Release track beta")
        try {
            conversations.insertConversation(root); conversations.insertConversation(child)
            projects.save(project); projects.bindConversation(project.id, root.id)
            val reloaded = ProjectRepository(InstrumentationRegistry.getInstrumentation().targetContext, conversations)
            assertEquals(project.id, reloaded.projectForConversation(child.id)?.id)
            assertTrue(reloaded.projectForConversation(child.id)!!.promptContext().contains("Release track beta"))
        } finally { projects.remove(project.id); conversations.deleteConversation(child); conversations.deleteConversation(root) }
    }

    @Test fun reviewWorkspaceOverrideWinsWithoutChangingAssistantSettings() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val conversations = GlobalContext.get().get<ConversationRepository>()
        val projects = GlobalContext.get().get<ProjectRepository>()
        val source = me.rerere.rikkahub.data.datastore.createDevbroAssistant().copy(workspaceId = Uuid.random())
        val reviewer = me.rerere.rikkahub.data.datastore.createOpsbroAssistant().copy(workspaceId = Uuid.random())
        val root = Conversation(assistantId = source.id, messageNodes = emptyList())
        val child = Conversation(assistantId = reviewer.id, parentConversationId = root.id, messageNodes = emptyList())
        val settings = me.rerere.rikkahub.data.datastore.Settings(assistants = listOf(source, reviewer))
        try {
            conversations.insertConversation(root); conversations.insertConversation(child)
            me.rerere.rikkahub.data.ai.AgentTaskPolicy.initialize(context.filesDir)
            me.rerere.rikkahub.data.ai.AgentTaskPolicy.set(child.id.toString(), me.rerere.rikkahub.ui.pages.chat.taskReviewPolicy(source.workspaceId.toString()))
            val effective = projects.effectiveAssistant(child.id, reviewer, settings)
            assertEquals(source.workspaceId, effective.workspaceId)
            assertNotEquals(source.workspaceId, settings.assistants.last().workspaceId)
        } finally { conversations.deleteConversation(child); conversations.deleteConversation(root) }
    }

    @Test fun forgottenFactCanBeRestoredAndStaleEditCannotOverwriteIt() = runBlocking {
        val memories = GlobalContext.get().get<MemoryRepository>()
        val scope = MemoryRepository.projectScope(Uuid.random().toString())
        try {
            val first = memories.addMemory(scope, "Initial fact", "chat", "message")
            val second = memories.updateContent(first.id, "Changed fact", first.revision)
            memories.deleteMemory(second.id, second.revision)
            assertTrue(memories.getMemoriesOfAssistant(scope).isEmpty())
            val restored = memories.restore(first.id, first.revision, second.revision + 1)
            assertEquals("Initial fact", restored.content)
            assertEquals("message", restored.sourceMessageId)
            var conflicted = false
            try { memories.updateContent(first.id, "Stale", first.revision) } catch (_: MemoryConflictException) { conflicted = true }
            assertTrue(conflicted)
            assertEquals("Initial fact", memories.getMemoriesOfAssistant(scope).single().content)
        } finally { memories.deleteMemoriesOfAssistant(scope) }
    }

    @Test fun projectSearchFindsChildToolOutputAndPagesBeyondFifty() = runBlocking {
        val conversations = GlobalContext.get().get<ConversationRepository>()
        val assistant = Uuid.random()
        val keyword = "pocketfixture" + Uuid.random().toString().replace("-", "")
        val root = Conversation(assistantId = assistant, messageNodes = emptyList())
        val nodes = (0 until 55).map { index -> MessageNode(messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool("call$index", "fixture_shell", "{}", listOf(UIMessagePart.Text("$keyword evidence $index"))))))) }
        val child = Conversation(assistantId = assistant, parentConversationId = root.id, messageNodes = nodes)
        try {
            conversations.insertConversation(root); conversations.insertConversation(child)
            val filter = WorkSearchFilter(kind = WorkSearchKind.TOOL, conversationIds = setOf(root.id.toString()), includeChildren = true, toolName = "fixture_shell")
            val first = conversations.searchMessages(keyword, filter = filter)
            val second = conversations.searchMessages(keyword, filter = filter, offset = 50)
            assertEquals(50, first.size); assertEquals(5, second.size)
            assertEquals(55, (first + second).map { it.messageId }.toSet().size)
            assertTrue(conversations.searchMessages(keyword, filter = filter.copy(includeChildren = false)).isEmpty())
            assertTrue(conversations.searchMessages(keyword, filter = filter.copy(kind = WorkSearchKind.FILE)).isEmpty())
        } finally { conversations.deleteConversation(child); conversations.deleteConversation(root) }
    }
}
