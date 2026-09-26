package me.rerere.rikkahub.data.ai

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

data class TraceArchiveResult(val sessions: Int, val events: Long, val warnings: Int)

internal data class TraceArchiveSnapshot(
    val id: String,
    val capturedAt: Long,
    val directory: File,
    val payloadFiles: List<File>,
    val exists: Boolean,
)

/** Streaming, lossless payload export. No UI paging, filtering, or model/tool re-execution. */
internal class TraceArchiveWriter(
    private val staging: File,
    private val checkActive: () -> Unit,
    private val validRecordHash: (TraceRecord) -> Boolean,
    private val snapshot: (String, File) -> TraceArchiveSnapshot,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val hashPattern = Regex("[a-f0-9]{64}")
    private val issues = File(staging, "issues.jsonl")
    private var warnings = 0

    private fun issue(session: String, code: String, detail: String) {
        checkActive()
        issues.appendText(buildJsonObject {
            put("session", session); put("code", code); put("detail", detail)
        }.toString() + "\n")
        warnings++
    }

    private fun transfer(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            checkActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
    }

    private fun ZipOutputStream.file(path: String, file: File) {
        checkActive()
        putNextEntry(ZipEntry(path))
        file.inputStream().use { transfer(it, this) }
        closeEntry()
    }

    private fun ZipOutputStream.text(path: String, value: String) {
        checkActive()
        putNextEntry(ZipEntry(path))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    fun write(rootId: String, output: OutputStream, metadata: JsonObject): TraceArchiveResult {
        val startedAt = System.currentTimeMillis()
        val pending = ArrayDeque<String>().apply { add(rootId) }
        val visited = mutableSetOf<String>()
        val sessions = mutableListOf<JsonObject>()
        var totalEvents = 0L
        ZipOutputStream(output.buffered()).use { zip ->
            zip.text("README.txt", README)
            while (pending.isNotEmpty()) {
                checkActive()
                val id = pending.removeFirst()
                if (!visited.add(id)) continue
                val saved = snapshot(id, File(staging, id))
                val prefix = "sessions/$id/"
                val index = File(saved.directory, "events.jsonl")
                val referenced = linkedSetOf<String>()
                val legacyChildren = mutableSetOf<String>()
                var records = 0L
                var previous: TraceRecord? = null
                var lastSequence: Long? = null
                val warningsBefore = warnings
                fun child(value: String?) {
                    if (value == null) return
                    if (runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) {
                        if (value !in visited) pending.add(value)
                    } else issue(id, "invalid_child_id", value)
                }

                if (!saved.exists) issue(id, "missing_session", "Referenced journal does not exist")
                saved.directory.listFiles().orEmpty().sortedBy { it.name }.forEach { file ->
                    zip.file(prefix + file.name, file)
                    if (file.extension == "fragment") issue(id, "recovered_fragment", file.name)
                }
                if (index.exists()) {
                    if (index.length() > 0) java.io.RandomAccessFile(index, "r").use { file ->
                        file.seek(file.length() - 1)
                        if (file.read() != 10) issue(id, "uncommitted_tail", "Index ends without a committed newline; original bytes retained")
                    }
                    index.useLines { lines -> lines.forEachIndexed { lineIndex, line ->
                        checkActive()
                        val record = try { json.decodeFromString<TraceRecord>(line) }
                        catch (e: Exception) {
                            if (e is CancellationException) throw e
                            issue(id, "invalid_record", "Line ${lineIndex + 1}: ${e.message}")
                            return@forEachIndexed
                        }
                        records++
                        if (record.sequence != (previous?.sequence ?: 0L) + 1 ||
                            record.previousHash != previous?.hash.orEmpty() || !validRecordHash(record)) {
                            issue(id, "index_integrity", "Line ${lineIndex + 1}, sequence ${record.sequence}")
                        }
                        previous = record
                        lastSequence = record.sequence
                        if (hashPattern.matches(record.payloadHash)) referenced.add(record.payloadHash)
                        else issue(id, "invalid_payload_hash", "Sequence ${record.sequence}")
                        if (record.source.startsWith("subagent.")) {
                            child(record.summary?.childConversation)
                            if (record.summary?.childConversation == null) legacyChildren.add(record.payloadHash)
                        }
                    } }
                } else {
                    zip.text(prefix + "events.jsonl", "")
                    if (saved.exists) issue(id, "missing_index", "No recorded event index in this journal")
                }
                val payloads = saved.payloadFiles.associateBy { it.name.removeSuffix(".json.gz") }
                referenced.filter { it !in payloads }.forEach { issue(id, "missing_payload", it) }
                var exportedPayloads = 0
                payloads.toSortedMap().forEach { (hash, file) ->
                    checkActive()
                    // Keep original compressed bytes as well as readable JSON. Even a damaged
                    // gzip or hash-mismatched payload remains available for diagnosis.
                    zip.file(prefix + "raw-payloads/" + file.name, file)
                    try {
                        val digest = MessageDigest.getInstance("SHA-256")
                        GZIPInputStream(file.inputStream()).use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                checkActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                digest.update(buffer, 0, n)
                            }
                        }
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        if (actual != hash) issue(id, "payload_integrity", hash)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        issue(id, "unreadable_payload", "$hash: ${e.message}")
                        return@forEach
                    }
                    zip.putNextEntry(ZipEntry(prefix + "payloads/$hash.json"))
                    GZIPInputStream(file.inputStream()).use { transfer(it, zip) }
                    zip.closeEntry()
                    exportedPayloads++
                    if (hash in legacyChildren) {
                        try {
                            val body = GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() }
                            child((json.parseToJsonElement(body) as? JsonObject)?.get("child_conversation")?.jsonPrimitive?.contentOrNull)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            issue(id, "unreadable_child_link", "$hash: ${e.message}")
                        }
                    }
                }
                totalEvents += records
                sessions.add(buildJsonObject {
                    put("id", id); put("snapshot_at_ms", saved.capturedAt); put("exists", saved.exists)
                    put("events", records); put("last_sequence", lastSequence?.let(::JsonPrimitive) ?: JsonNull)
                    put("index_bytes", index.length()); put("payloads", payloads.size)
                    put("readable_payloads", exportedPayloads)
                    put("unreferenced_payloads", payloads.keys.count { it !in referenced })
                    put("warnings", warnings - warningsBefore)
                })
            }
            if (issues.exists()) zip.file("issues.jsonl", issues) else zip.text("issues.jsonl", "")
            zip.text("manifest.json", buildJsonObject {
                put("format", "rikkahub-trajectory"); put("schema_version", 1)
                put("conversation_id", rootId); put("export_started_at_ms", startedAt)
                put("export_finished_at_ms", System.currentTimeMillis()); put("metadata", metadata)
                put("scope", "complete_recorded_journals_with_descendant_subagents")
                put("snapshot_mode", "independent_per_session")
                put("ui_filters_applied", false); put("event_count", totalEvents)
                put("warning_count", warnings); put("complete", warnings == 0)
                put("sessions", JsonArray(sessions))
            }.toString())
        }
        return TraceArchiveResult(sessions.size, totalEvents, warnings)
    }

    companion object {
        private val README = """
            The Trickster's Pocket — complete recorded trajectory (schema 1)

            manifest.json: root conversation, app metadata, counts, per-session snapshot times.
            sessions/<uuid>/events.jsonl: original append-only event index, in recorded order.
            sessions/<uuid>/payloads/<payloadHash>.json: complete decompressed event data.
            sessions/<uuid>/raw-payloads/<payloadHash>.json.gz: original stored bytes, including
            damaged/orphaned blobs when present. Repeated events can share one payload.
            sessions/<uuid>/task.json: saved task checkpoint, when present.
            sessions/<uuid>/uncommitted-*.fragment: preserved crash fragments, when present.
            issues.jsonl: missing data or integrity problems; empty for a verified snapshot.

            For each event, read the JSON file named by its payloadHash. source identifies
            model.request, model.stream, model.response, tool.started, tool.result,
            subagent.*, compaction.*, task.* and other recorded events. Requests contain
            normalized messages and tool schemas; streamed chunks contain provider-returned
            text, reasoning and tool-call data. They are not shortened to UI previews.

            The export ignores UI search, filters, run selection and paging. It follows
            recorded child_conversation links recursively, without unrelated conversations
            or fork ancestors. Each session is snapshotted independently: activity after
            that session's snapshot is not included. Export again for a later snapshot.

            complete means no storage/integrity issues were detected, not that every event
            that ever happened was recorded. Missing old history, hidden model reasoning,
            raw HTTP traffic, provider credentials/headers and referenced attachment files
            not stored in the journal cannot be reconstructed. This is not replay.
            No tools run during export. Private prompts, commands and results are included;
            inspect the archive before sharing it.
        """.trimIndent() + "\n"
    }
}
