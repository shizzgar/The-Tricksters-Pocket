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

}
