package me.rerere.rikkahub.service

import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.AgentTaskPolicy
import me.rerere.rikkahub.data.ai.AgentToolPolicy
import me.rerere.rikkahub.data.ai.ScopedAgentPolicy
import me.rerere.rikkahub.data.ai.SessionJournal
import me.rerere.rikkahub.data.ai.hooks.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.repository.*
import me.rerere.rikkahub.data.task.TaskArtifactStore
import me.rerere.rikkahub.data.task.TaskBrief
import me.rerere.rikkahub.data.task.TaskReviewScope
import me.rerere.rikkahub.ui.pages.history.HistoryVM
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File
import kotlin.uuid.Uuid

/** Real Room, file stores, HistoryVM and ChatService boundaries, with no provider request. */
class ConversationLifecycleInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val koin get() = GlobalContext.get()
    private val repository get() = koin.get<ConversationRepository>()
    private val files get() = koin.get<FilesManager>()
    private fun tool(image: String) = UIMessagePart.Tool(
        "outer", "fixture", "{}", listOf(UIMessagePart.Tool(
            "image", "show_image", "{}", listOf(UIMessagePart.Image(image)),
        )),
    )
    private fun conversation(part: UIMessagePart) = Conversation(
        assistantId = koin.get<SettingsStore>().settingsFlow.value.assistantId,
        title = "Attachment lifecycle fixture",
        messageNodes = listOf(MessageNode.of(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part)))),
    )

    @Test fun deletingToolImageKeepsOriginalAndSharedManagedAttachments() = runBlocking {
        val original = File(context.cacheDir, "original-${Uuid.random()}.png").apply { writeText("user original") }
        val imported = files.saveManagedFromBytes(FileFolders.UPLOAD, "owned copy".toByteArray(), "copy.png", "image/png")
        val managed = files.getFile(imported)
        val first = conversation(UIMessagePart.Tool("images", "fixture", "{}", listOf(tool(original.toUri().toString()), tool(managed.toUri().toString()))))
        val second = conversation(tool(managed.toUri().toString()))
        try {
            repository.insertConversation(first)
            repository.insertConversation(second)
            repository.deleteConversation(first)
            assertEquals("user original", original.readText())
            assertEquals("owned copy", managed.readText())
            repository.deleteConversation(second)
            assertFalse("Last owning chat can release its imported copy", managed.exists())
            assertEquals("user original", original.readText())
        } finally {
            repository.deleteConversation(first)
            repository.deleteConversation(second)
            original.delete()
            managed.delete()
        }
    }

    @Test fun projectReferenceSurvivesDeletionOfItsLastChatReference() = runBlocking {
        val entry = files.saveManagedFromBytes(FileFolders.UPLOAD, "reference".toByteArray(), "reference.txt", "text/plain")
        val managed = files.getFile(entry)
        val conversation = conversation(tool(managed.toUri().toString()))
        val projects = koin.get<ProjectRepository>()
        val project = PocketProject(name = "Keep reference fixture", files = listOf(ProjectReferenceFile(entry.displayName, entry.relativePath, entry.mimeType)))
        try {
            projects.save(project)
            projects.bindConversation(project.id, conversation.id)
            repository.insertConversation(conversation)
            repository.deleteConversation(conversation)
            assertEquals("reference", managed.readText())
        } finally {
            projects.remove(project.id)
            repository.deleteConversation(conversation)
            files.delete(entry.id)
        }
    }

    @Test fun forkCopiesNestedToolAttachmentsAndOutlivesSourceDeletion() = runBlocking {
        val service = koin.get<ChatService>()
        val imported = files.saveManagedFromBytes(FileFolders.UPLOAD, "image fixture".toByteArray(), "copy.png", "image/png")
        val sourceFile = files.getFile(imported)
        val source = conversation(tool(sourceFile.toUri().toString()))
        var fork: Conversation? = null
        try {
            service.saveConversation(source.id, source)
            fork = service.forkConversationAtMessage(source.id, source.currentMessages.single().id)
            val storedFork = requireNotNull(repository.getConversationById(fork.id))
            val forkFile = storedFork.files.single().toFile()
            assertNotEquals(sourceFile.canonicalPath, forkFile.canonicalPath)
            assertEquals(sourceFile.readText(), forkFile.readText())
            service.dropSession(source.id)
            repository.deleteConversation(source)
            assertFalse(sourceFile.exists())
            assertEquals("image fixture", forkFile.readText())
            service.dropSession(fork.id)
            repository.deleteConversation(storedFork)
            assertFalse(forkFile.exists())
        } finally {
            service.dropSession(source.id)
            repository.deleteConversation(source)
            fork?.let { service.dropSession(it.id); repository.deleteConversation(it) }
        }
    }

    @Test fun historyUndoRetainsReviewRestrictionsAndWritableMetadataStores() = runBlocking {
        val settings = koin.get<SettingsStore>()
        withTimeout(15_000) { settings.settingsFlow.first { !it.init } }
        val vm = HistoryVM(repository, settings)
        val viewModels = ViewModelStore().apply { put("history-lifecycle", vm) }
        val imported = files.saveManagedFromBytes(FileFolders.UPLOAD, "review attachment".toByteArray(), "review.txt", "text/plain")
        val attachment = files.getFile(imported)
        val conversation = conversation(tool(attachment.toUri().toString())).copy(parentConversationId = Uuid.random())
        val id = conversation.id.toString()
        val tasks = TaskArtifactStore.at(context.filesDir)
        val hooks = HookRuntimeStore.at(context.filesDir)
        val journal = SessionJournal.at(context.filesDir)
        val projects = koin.get<ProjectRepository>()
        val project = PocketProject(name = "Undo review fixture")
        val policy = ScopedAgentPolicy(maxSteps = 10, usedSteps = 3, readOnly = true)
        val rule = ToolHook(name = "Undo hook", scope = ToolHookScope(global = true),
            condition = ToolHookCondition(outcome = ToolHookOutcome.NONZERO_EXIT),
            action = ToolHookAction(prompt = "Keep the original instruction."))
        val runtime = ToolHookRuntime(hooks, id, "turn-undo", { listOf(rule) },
            ToolHookScopeContext(conversation.assistantId, conversationId = conversation.id),
            resolveContent = { HookContentResolution.Resolved(it.prompt) })
        fun failedCall(name: String) = UIMessagePart.Tool(name, "termux_run_command", "{}",
            listOf(UIMessagePart.Text("""{"exit_code":2}""")), executionStartedAt = 1L, executionAttemptId = name)
        try {
            repository.insertConversation(conversation)
            AgentTaskPolicy.initialize(context.filesDir)
            AgentTaskPolicy.set(id, policy)
            tasks.markReview(id, TaskReviewScope())
            tasks.saveBrief(id, TaskBrief("Inspect without edits"))
            projects.save(project)
            projects.bindConversation(project.id, conversation.id)
            journal.append(id, "fixture", buildJsonObject {})
            runtime.completed(failedCall("before-undo"))
            val pending = hooks.pending(id)
            assertFalse(pending.ids.isEmpty())

            vm.deleteConversation(conversation) {
                assertNotNull("Undo window must retain the authoritative row", repository.getConversationById(conversation.id))
                assertEquals(policy, AgentTaskPolicy.get(id))
                assertEquals("review attachment", attachment.readText())
                true
            }

            assertNotNull(repository.getConversationById(conversation.id))
            assertEquals(policy, AgentTaskPolicy.get(id))
            assertFalse(AgentToolPolicy.permits("workspace_write_file", id, readOnly = false))
            assertEquals(TaskReviewScope(), tasks.reviewScope(id))
            assertEquals(project.id, projects.projectForConversation(conversation.id)?.id)
            assertEquals(pending, hooks.pending(id))
            // Undo must not leave deletion tombstones in any of the durable stores.
            tasks.saveBrief(id, TaskBrief("Continue review"))
            runtime.completed(failedCall("after-undo"))
            journal.append(id, "after-undo", buildJsonObject {})
            assertEquals("Continue review", tasks.brief(id).goal)
            assertTrue(attachment.exists())

            vm.deleteConversation(conversation) { false }
            assertNull(repository.getConversationById(conversation.id))
            assertNull(AgentTaskPolicy.get(id))
            assertNull(tasks.reviewScope(id))
            assertFalse(attachment.exists())
        } finally {
            withContext(Dispatchers.Main) { viewModels.clear() }
            repository.deleteConversation(conversation)
            projects.remove(project.id)
        }
    }

    @Test fun overlappingUndoWindowsFinalizeCancellationAndKeepTheOtherChat() = runBlocking {
        val settings = koin.get<SettingsStore>()
        withTimeout(15_000) { settings.settingsFlow.first { !it.init } }
        val vm = HistoryVM(repository, settings)
        val viewModels = ViewModelStore().apply { put("history-overlap", vm) }
        val first = conversation(UIMessagePart.Text("first"))
        val second = conversation(UIMessagePart.Text("second"))
        val firstShown = CompletableDeferred<Unit>()
        val secondShown = CompletableDeferred<Unit>()
        val undoSecond = CompletableDeferred<Boolean>()
        try {
            repository.insertConversation(first)
            repository.insertConversation(second)
            withTimeout(15_000) { vm.conversations.first { rows -> rows.any { it.id == first.id } && rows.any { it.id == second.id } } }
            val firstDeletion = launch {
                vm.deleteConversation(first) { firstShown.complete(Unit); awaitCancellation() }
            }
            val secondDeletion = launch {
                vm.deleteConversation(second) { secondShown.complete(Unit); undoSecond.await() }
            }
            firstShown.await()
            secondShown.await()
            withTimeout(15_000) { vm.conversations.first { rows -> rows.none { it.id == first.id || it.id == second.id } } }
            // Snackbar replacement/navigation cancels the first window. Cleanup must
            // survive that cancellation without committing the unrelated second row.
            firstDeletion.cancelAndJoin()
            assertNull(repository.getConversationById(first.id))
            assertNotNull(repository.getConversationById(second.id))
            undoSecond.complete(true)
            secondDeletion.join()
            withTimeout(15_000) { vm.conversations.first { rows -> rows.any { it.id == second.id } } }
            assertNotNull(repository.getConversationById(second.id))
        } finally {
            withContext(Dispatchers.Main) { viewModels.clear() }
            repository.deleteConversation(first)
            repository.deleteConversation(second)
        }
    }
}
