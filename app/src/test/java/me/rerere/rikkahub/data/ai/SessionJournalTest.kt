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

    @Test fun `legacy records are projected without rewriting old hashes`() = runBlocking {
        val root = folder.newFolder()
        val record = SessionJournal(root).append(id, "model.request", buildJsonObject { put("request_id", "legacy"); put("model", "old") })
        val material = "${record.sequence}\n${record.timestamp}\n${record.source}\n${record.payloadHash}\n${record.previousHash}"
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(material.toByteArray()).joinToString("") { "%02x".format(it) }
        val legacy = record.copy(hash = hash, summary = null)
        File(root, "$id/events.jsonl").writeText(Json.encodeToString(TraceRecord.serializer(), legacy) + "\n")
        val reopened = SessionJournal(root)
        val projection = reopened.trajectory(id)
        assertEquals("old", projection.entries.single().summary.title)
        assertEquals(hash, projection.entries.single().record.hash)
        assertEquals(hash, reopened.append(id, "model.response", buildJsonObject { put("request_id", "legacy") }).previousHash)
    }

    @Test fun `summary tampering invalidates index integrity`() = runBlocking {
        val root = folder.newFolder()
        SessionJournal(root).append(id, "model.request", buildJsonObject { put("model", "original-model") })
        val index = File(root, "$id/events.jsonl")
        index.writeText(index.readText().replace("original-model", "forged-model"))
        assertNotNull(SessionJournal(root).trajectory(id).error)
        assertTrue(runCatching { SessionJournal(root).append(id, "tool.result", buildJsonObject {}) }.isFailure)
    }

    @Test fun `bounded trajectory windows can reach the entire journal`() = runBlocking {
        val journal = SessionJournal(folder.newFolder())
        repeat(5) { journal.append(id, "conversation.event", buildJsonObject { put("n", it) }) }
        val recent = journal.trajectory(id, limit = 2)
        assertEquals(listOf(4L, 5L), recent.entries.map { it.record.sequence })
        val older = journal.trajectory(id, limit = 2, before = 4)
        assertEquals(listOf(2L, 3L), older.entries.map { it.record.sequence })
        val first = journal.trajectory(id, limit = 2, before = 2)
        assertEquals(listOf(1L), first.entries.map { it.record.sequence })
        assertFalse(first.hasEarlier)
    }
    @Test fun `deletion removes all payloads and blocks a late writer from recreating chat trace`() = runBlocking {
        val root = folder.newFolder()
        val journal = SessionJournal(root)
        journal.append(id, "tool.result", buildJsonObject { put("content", "synthetic-private-output") })
        assertTrue(journal.storageBytes(id) > 0)
        journal.delete(id)
        assertEquals(0L, journal.storageBytes(id))
        assertTrue(journal.page(id).records.isEmpty())
        assertFalse(File(root, id).exists())
        assertTrue(runCatching { journal.append(id, "task.finished", buildJsonObject {}) }.isFailure)
        assertFalse(File(root, id).exists())
    }

    @Test fun `explicit cleanup starts a new valid journal chain`() = runBlocking {
        val journal = SessionJournal(folder.newFolder())
        journal.append(id, "tool.result", buildJsonObject {})
        journal.clear(id)
        val fresh = journal.append(id, "model.request", buildJsonObject {})
        assertEquals(1L, fresh.sequence)
        assertEquals("", fresh.previousHash)
        assertNull(journal.page(id).error)
    }
}
