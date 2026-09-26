package me.rerere.rikkahub.data.task

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TaskArtifactStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val id = "cc409fc4-f62f-4763-b2e6-90a034b94f63"
    private fun tool(output: String) = UIMessagePart.Tool("t1", "workspace_write_file", "{\"path\":\"/workspace/result.txt\"}", listOf(UIMessagePart.Text(output)))

    @Test fun `failed denied and partial tool envelopes never become produced files`() {
        assertNull(successfulWorkspaceFileOutput(tool("{\"error\":\"Permission denied\",\"path\":\"/workspace/result.txt\",\"sizeBytes\":3}")))
        assertNull(successfulWorkspaceFileOutput(tool("{\"isError\":true,\"path\":\"/workspace/result.txt\",\"sizeBytes\":3}")))
        assertNull(successfulWorkspaceFileOutput(tool("Tool cancelled by user")))
        assertNull(successfulWorkspaceFileOutput(tool("{\"path\":\"/workspace/result.txt\"}")))
        assertNotNull(successfulWorkspaceFileOutput(tool("{\"path\":\"/workspace/result.txt\",\"sizeBytes\":3}")))
    }

    @Test fun `unsafe artifact paths and forged store ids are rejected`() = runBlocking {
        listOf("../secret", "/workspace/../secret", "/workspace/a\u0000", "/workspace/a\\b", "/").forEach {
            assertThrows(IllegalArgumentException::class.java) { requireSafeArtifactPath(it) }
        }
        assertNull(successfulWorkspaceFileOutput(tool("{\"path\":\"/workspace/../secret\",\"sizeBytes\":3}")))
        val store = TaskArtifactStore.at(temp.root)
        assertTrue(runCatching { store.saveBrief("../escape", TaskBrief("bad")) }.isFailure)
        assertFalse(File(temp.root, "escape.json").exists())
    }

    @Test fun `brief survives file persistence and corrupt data is not silently replaced`() = runBlocking {
        val store = TaskArtifactStore.at(temp.root)
        val brief = TaskBrief("Build APK", "Signature and tests pass")
        store.saveBrief(id, brief)
        assertEquals(brief, store.brief(id))
        val file = File(temp.root, "task-results/$id.json")
        assertTrue(file.readText().contains("Signature and tests pass"))
        file.writeText("broken")
        assertTrue(runCatching { store.saveBrief(id, TaskBrief("overwrite")) }.isFailure)
        assertEquals("broken", file.readText())
    }
    @Test fun `deleted task cannot be recreated by a delayed writer`() = runBlocking {
        val store = TaskArtifactStore.at(temp.root)
        store.saveBrief(id, TaskBrief("private"))
        store.removeConversation(id)
        assertTrue(runCatching { store.saveBrief(id, TaskBrief("late")) }.isFailure)
        assertFalse(File(temp.root, "task-results/$id.json").exists())
    }

    @Test fun `backup validator rejects malformed and unsafe artifact records`() {
        TaskArtifactStore.validateBackupDocument("{\"brief\":{\"goal\":\"restore\"}}", id)
        assertThrows(Exception::class.java) { TaskArtifactStore.validateBackupDocument("broken", id) }
        assertThrows(Exception::class.java) { TaskArtifactStore.validateBackupDocument("{\"artifacts\":[{\"path\":\"/workspace/../secret\"}]}", id) }
    }

    @Test fun `task is opt in and closing retains brief reviewer and results`() = runBlocking {
        val store = TaskArtifactStore.at(temp.root)
        assertFalse(store.brief(id).isActive)
        assertFalse(TaskBrief(active = true).isActive)
        val reviewer = "7760cefb-909a-438b-88fb-6ebd220405f0"
        // An auto-captured file creates a metadata document with an empty brief.
        val directory = File(temp.root, "task-results").apply { mkdirs() }
        val file = File(directory, "$id.json")
        file.writeText("""{"artifacts":[],"capturedToolCalls":["produced-result"]}""")
        assertFalse(store.brief(id).isActive)
        store.setReviewAssistant(id, reviewer)
        assertFalse(store.brief(id).isActive)
        store.saveBrief(id, TaskBrief("Build release", "Checks pass", active = true, reviewAssistantId = reviewer))
        assertTrue(store.brief(id).isActive)
        store.closeBrief(id)
        assertFalse(store.brief(id).isActive)
        assertEquals("Build release", store.brief(id).goal)
        assertEquals(reviewer, store.brief(id).reviewAssistantId)
        assertTrue(file.readText().contains("produced-result"))
        assertTrue(runCatching { store.updateBriefText(id, "stale editor", "") }.isFailure)
        // Decode the actual persisted document, rather than relying on process state.
        val persisted = kotlinx.serialization.json.Json.parseToJsonElement(file.readText()) as kotlinx.serialization.json.JsonObject
        val closed = kotlinx.serialization.json.Json.decodeFromJsonElement(TaskBrief.serializer(), persisted.getValue("brief"))
        assertFalse(closed.isActive)
        store.saveBrief(id, closed.copy(active = true))
        assertTrue(store.brief(id).isActive)
    }

    @Test fun `old explicit tasks migrate but old empty auto records stay hidden`() = runBlocking {
        val directory = File(temp.root, "task-results").apply { mkdirs() }
        val file = File(directory, "$id.json")
        file.writeText("""{"brief":{"goal":"Existing task","acceptanceCriteria":"Keep results"}}""")
        assertTrue(TaskArtifactStore.at(temp.root).brief(id).isActive)
        file.writeText("""{"brief":{"goal":"","acceptanceCriteria":""},"artifacts":[]}""")
        assertFalse(TaskArtifactStore.at(temp.root).brief(id).isActive)
        assertTrue(runCatching { TaskArtifactStore.at(temp.root).setReviewAssistant(id, "missing-or-invalid") }.isFailure)
    }

    @Test fun `review identity survives portable metadata restore without a policy file`() = runBlocking {
        val source = TaskArtifactStore.at(temp.newFolder("source"))
        val workspace = "7760cefb-909a-438b-88fb-6ebd220405f0"
        source.markReview(id, TaskReviewScope(workspace))
        assertFalse(source.brief(id).isActive)
        val sourceFile = File(temp.root, "source/task-results/$id.json")
        val document = sourceFile.readText()
        TaskArtifactStore.validateBackupDocument(document, id)
        val restoredRoot = temp.newFolder("restored")
        File(restoredRoot, "task-results").mkdirs()
        File(restoredRoot, "task-results/$id.json").writeText(document)
        assertEquals(TaskReviewScope(workspace), TaskArtifactStore.at(restoredRoot).reviewScope(id))
        assertFalse(File(restoredRoot, "agent-policies").exists())
        assertThrows(Exception::class.java) { TaskArtifactStore.validateBackupDocument("""{"reviewScope":{"workspaceId":"../bad"}}""", id) }
        source.removeConversation(id)
        assertTrue(runCatching { source.markReview(id, TaskReviewScope(workspace)) }.isFailure)
    }

}
