package me.rerere.rikkahub.data.ai

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TraceArchiveTest {
    @get:Rule val folder = TemporaryFolder()
    private val id = "44dc2746-71b2-4c37-81b7-83f5fba54493"
    private val child = "a046f6f2-bd3a-40ca-a032-f519bedfbb14"
    private val grandchild = "53dce3e4-c685-486a-b410-fb85448c94e0"
    private val unrelated = "f8c5ce76-2d88-44de-9c6f-e776a860de9c"
    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun archive(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                check(entry.name !in keys)
                put(entry.name, zip.readBytes())
            }
        }
    }
    private fun Map<String, ByteArray>.json(path: String) = Json.parseToJsonElement(getValue(path).toString(Charsets.UTF_8)).jsonObject
    private fun legacy(record: TraceRecord): TraceRecord {
        val material = "${record.sequence}\n${record.timestamp}\n${record.source}\n${record.payloadHash}\n${record.previousHash}"
        return record.copy(summary = null, hash = hash(material.toByteArray()))
    }

    @Test fun `exports every event beyond display limits and preserves complete payload bytes`() = runBlocking {
        val root = folder.newFolder()
        val cache = folder.newFolder()
        val journal = SessionJournal(root)
        val payload = buildJsonObject {
            put("request_id", "request")
            put("messages", buildJsonArray { add(buildJsonObject { put("role", "system"); put("content", "Полный промпт\n".repeat(8000)) }) })
            put("reasoning", "Reasoning returned by the provider")
            put("tool_calls", buildJsonArray { add(buildJsonObject { put("name", "termux_job_wait"); put("arguments", "{}") }) })
            put("stdout", "complete stdout"); put("stderr", "complete stderr")
        }
        val first = journal.append(id, "model.request", payload)
        // Construct a valid legacy chain in one write, avoiding 20,025 unrelated fsyncs.
        val index = File(root, "$id/events.jsonl")
        index.bufferedWriter().use { writer ->
            var previous = ""
            repeat(20_025) { n ->
                val record = legacy(first.copy(sequence = n + 1L, previousHash = previous))
                writer.appendLine(Json.encodeToString(TraceRecord.serializer(), record))
                previous = record.hash
            }
        }
        assertEquals(1, journal.page(id, limit = 1).records.size)
        val output = ByteArrayOutputStream()
        val result = journal.exportArchive(id, output, cache)
        val entries = archive(output.toByteArray())
        assertEquals(20_025L, result.events)
        assertEquals(0, result.warnings)
        assertArrayEquals(index.readBytes(), entries.getValue("sessions/$id/events.jsonl"))
        assertArrayEquals(payload.toString().toByteArray(), entries.getValue("sessions/$id/payloads/${first.payloadHash}.json"))
        assertArrayEquals(File(root, "$id/payloads/${first.payloadHash}.json.gz").readBytes(), entries.getValue("sessions/$id/raw-payloads/${first.payloadHash}.json.gz"))
        assertFalse(entries.json("manifest.json").getValue("ui_filters_applied").jsonPrimitive.boolean)
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun `includes recursive and legacy subagents once without unrelated conversations`() = runBlocking {
        val root = folder.newFolder()
        val journal = SessionJournal(root)
        val first = journal.append(id, "subagent.started", buildJsonObject { put("child_conversation", child) })
        File(root, "$id/events.jsonl").writeText(Json.encodeToString(TraceRecord.serializer(), legacy(first)) + "\n")
        journal.append(child, "subagent.started", buildJsonObject { put("child_conversation", grandchild) })
        journal.append(grandchild, "subagent.started", buildJsonObject { put("child_conversation", id) })
        journal.append(unrelated, "model.request", buildJsonObject { put("private", "unrelated conversation") })
        journal.saveTask(AgentTaskRecord(child, checkpoint = "saved-checkpoint"))
        val output = ByteArrayOutputStream()
        val result = journal.exportArchive(id, output, folder.newFolder())
        val entries = archive(output.toByteArray())
        assertEquals(3, result.sessions)
        assertEquals(3L, result.events)
        assertEquals(0, result.warnings)
        assertTrue(entries.keys.none { unrelated in it })
        assertEquals("saved-checkpoint", entries.json("sessions/$child/task.json").getValue("checkpoint").jsonPrimitive.content)
        assertEquals(3, entries.keys.count { it.endsWith("/events.jsonl") })
    }

    @Test fun `live append during slow output is not blocked or added to the captured index`() = runBlocking {
        val root = folder.newFolder()
        val cache = folder.newFolder()
        val journal = SessionJournal(root)
        val random = ByteArray(200_000).also { java.util.Random(7).nextBytes(it) }
        journal.append(id, "model.stream", buildJsonObject { put("content", Base64.getEncoder().encodeToString(random)) })
        val originalIndex = File(root, "$id/events.jsonl").readBytes()
        val bytes = ByteArrayOutputStream()
        val executor = Executors.newSingleThreadExecutor()
        var appended = false
        val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()))
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (!appended && cache.walkTopDown().any { it.name == "events.jsonl" }) {
                    appended = true
                    executor.submit { runBlocking {
                        journal.append(id, "tool.result", buildJsonObject { put("result", "later") })
                    } }.get(5, TimeUnit.SECONDS)
                }
                bytes.write(b, off, len)
            }
        }
        try {
            val result = journal.exportArchive(id, output, cache)
            assertTrue(appended)
            assertEquals(1L, result.events)
            assertEquals(2L, journal.page(id).total)
            assertArrayEquals(originalIndex, archive(bytes.toByteArray()).getValue("sessions/$id/events.jsonl"))
        } finally { executor.shutdownNow() }
    }

    @Test fun `damaged records and missing or corrupt payloads are exported with explicit issues`() = runBlocking {
        val root = folder.newFolder()
        val journal = SessionJournal(root)
        val missing = journal.append(id, "model.request", buildJsonObject { put("one", 1) })
        val broken = journal.append(id, "model.stream", buildJsonObject { put("two", 2) })
        File(root, "$id/payloads/${missing.payloadHash}.json.gz").delete()
        File(root, "$id/payloads/${broken.payloadHash}.json.gz").writeText("broken gzip")
        File(root, "$id/events.jsonl").appendText("{\"sequence\":3,")
        val fragment = "uncommitted-${"a".repeat(64)}.fragment"
        File(root, "$id/$fragment").writeText("crash fragment")
        val output = ByteArrayOutputStream()
        val result = journal.exportArchive(id, output, folder.newFolder())
        val entries = archive(output.toByteArray())
        assertTrue(result.warnings >= 4)
        assertFalse(entries.json("manifest.json").getValue("complete").jsonPrimitive.boolean)
        val issues = entries.getValue("issues.jsonl").toString(Charsets.UTF_8)
        listOf("missing_payload", "unreadable_payload", "invalid_record", "recovered_fragment", "uncommitted_tail").forEach { assertTrue(it, issues.contains(it)) }
        assertEquals("broken gzip", entries.getValue("sessions/$id/raw-payloads/${broken.payloadHash}.json.gz").toString(Charsets.UTF_8))
        assertEquals("crash fragment", entries.getValue("sessions/$id/$fragment").toString(Charsets.UTF_8))
        assertArrayEquals(File(root, "$id/events.jsonl").readBytes(), entries.getValue("sessions/$id/events.jsonl"))
    }

    @Test fun `missing child and invalid child identifiers do not read other paths`() = runBlocking {
        val root = folder.newFolder()
        val journal = SessionJournal(root)
        journal.append(id, "subagent.started", buildJsonObject { put("child_conversation", child) })
        journal.append(id, "subagent.started", buildJsonObject { put("child_conversation", "../../outside") })
        val output = ByteArrayOutputStream()
        val result = journal.exportArchive(id, output, folder.newFolder())
        val entries = archive(output.toByteArray())
        assertEquals(2, result.sessions)
        assertEquals(2, result.warnings)
        assertTrue(entries.keys.none { ".." in it })
        assertFalse(File(root, child).exists())
    }

    @Test fun `hash mismatches and orphan payloads retain the original stored evidence`() = runBlocking {
        val root = folder.newFolder()
        val journal = SessionJournal(root)
        val record = journal.append(id, "tool.result", buildJsonObject { put("output", "original") })
        val changed = "{\"output\":\"changed\"}".toByteArray()
        GZIPOutputStream(File(root, "$id/payloads/${record.payloadHash}.json.gz").outputStream()).use { it.write(changed) }
        val orphan = "{\"orphan\":true}".toByteArray()
        GZIPOutputStream(File(root, "$id/payloads/${hash(orphan)}.json.gz").outputStream()).use { it.write(orphan) }
        val output = ByteArrayOutputStream()
        val result = journal.exportArchive(id, output, folder.newFolder())
        val entries = archive(output.toByteArray())
        assertEquals(1, result.warnings)
        assertArrayEquals(changed, entries.getValue("sessions/$id/payloads/${record.payloadHash}.json"))
        assertArrayEquals(orphan, entries.getValue("sessions/$id/payloads/${hash(orphan)}.json"))
        assertEquals(1, entries.json("manifest.json").getValue("sessions").jsonArray.single().jsonObject.getValue("unreferenced_payloads").jsonPrimitive.int)
    }

    @Test fun `cancellation and output failures clean staging and leave the journal unchanged`() = runBlocking {
        val root = folder.newFolder()
        val cache = folder.newFolder()
        val journal = SessionJournal(root)
        journal.append(id, "model.request", buildJsonObject { put("content", "test") })
        val index = File(root, "$id/events.jsonl").readBytes()
        for (failure in listOf(CancellationException("cancel export"), IOException("destination full"))) {
            var thrown = false
            val output = object : OutputStream() { override fun write(b: Int) { if (!thrown) { thrown = true; throw failure } } }
            try {
                journal.exportArchive(id, output, cache)
                fail("Expected export failure")
            } catch (e: Exception) { assertSame(failure, e) }
            assertTrue(cache.listFiles()!!.isEmpty())
            assertArrayEquals(index, File(root, "$id/events.jsonl").readBytes())
        }
    }

    @Test fun `empty journal is explicit and invalid root id is rejected`() = runBlocking {
        val root = folder.newFolder()
        val journal = SessionJournal(root)
        val output = ByteArrayOutputStream()
        val result = journal.exportArchive(id, output, folder.newFolder())
        assertEquals(0L, result.events)
        assertEquals(1, result.warnings)
        assertTrue(root.listFiles()!!.isEmpty())
        assertTrue(runCatching { journal.exportArchive("../escape", ByteArrayOutputStream(), folder.newFolder()) }.isFailure)
    }
}
