package me.rerere.rikkahub.data.ai

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionJournalTest {
    @get:Rule val folder = TemporaryFolder()
    private val id = "44dc2746-71b2-4c37-81b7-83f5fba54493"
    @Test fun `new process appends to verified chain and searches immutable full payload`() = runBlocking {
        val root = folder.newFolder()
        val first = SessionJournal(root).append(id, "model.request", buildJsonObject { put("content", "find me") })
        val next = SessionJournal(root)
        val second = next.append(id, "tool.result", buildJsonObject { put("exit_code", 7) })
        assertEquals(first.hash, second.previousHash)
        assertEquals(listOf(first), next.page(id, query = "find me").records)
        assertTrue(next.payload(id, first).contains("find me"))
    }
    @Test fun `torn uncommitted tail is preserved and does not strand recovery`() = runBlocking {
        val root = folder.newFolder()
        SessionJournal(root).append(id, "tool.started", buildJsonObject { put("id", "x") })
        File(root, "$id/events.jsonl").appendText("{\"sequence\":2,")
        val reopened = SessionJournal(root)
        assertEquals(2L, reopened.append(id, "task.recovered", buildJsonObject { put("replayed", false) }).sequence)
        assertEquals(1, File(root, id).listFiles()!!.count { it.extension == "fragment" })
        assertNull(reopened.page(id).error)
    }
    @Test fun `cancelled and headless tasks cannot be restored automatically`() = runBlocking {
        val journal = SessionJournal(folder.newFolder())
        journal.saveTask(AgentTaskRecord(id, status = "cancelled"))
        assertTrue(journal.unfinished().isEmpty())
        journal.saveTask(AgentTaskRecord(id, recoverAutomatically = false))
        assertTrue(journal.unfinished().isEmpty())
        journal.saveTask(AgentTaskRecord(id, checkpoint = "saved"))
        assertEquals("saved", journal.unfinished().single().checkpoint)
    }
    @Test fun `committed corruption fails closed instead of rewriting history`() = runBlocking {
        val root = folder.newFolder()
        SessionJournal(root).append(id, "tool.result", buildJsonObject { put("value", 1) })
        val index = File(root, "$id/events.jsonl")
        index.writeText(index.readText().replace("tool.result", "tool.forged"))
        assertTrue(runCatching { SessionJournal(root).append(id, "task.resume", buildJsonObject {}) }.isFailure)
    }
}
