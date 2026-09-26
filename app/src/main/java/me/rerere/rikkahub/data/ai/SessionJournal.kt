package me.rerere.rikkahub.data.ai

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class TraceRecord(val sequence: Long, val timestamp: Long, val source: String, val payloadHash: String, val previousHash: String, val hash: String, val summary: TraceSummary? = null)

data class TracePage(val records: List<TraceRecord>, val before: Long?, val total: Long, val error: String? = null)

/** Append-only index and immutable gzip payloads. No silent retention deletion.
 * Task checkpoints are atomic mutable state, separate from the historical event stream. */
class SessionJournal(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val deleted = mutableSetOf<String>()
    private val heads = mutableMapOf<String, TraceRecord?>()
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private fun directory(id: String, create: Boolean = true): File {
        require(runCatching { java.util.UUID.fromString(id).toString() == id }.getOrDefault(false))
        return File(root, id).apply {
            if (create) {
                check(id !in deleted) { "This chat was deleted; its trace cannot be recreated" }
                check(isDirectory || mkdirs()) { "Cannot create private session journal" }
            }
        }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun atomic(file: File, bytes: ByteArray) {
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
        check(temp.renameTo(file)) { "Cannot save task checkpoint" }
    }
    private fun indexDigest(sequence: Long, at: Long, source: String, payloadHash: String, previousHash: String, summary: TraceSummary?): String =
        digest(("$sequence\n$at\n$source\n$payloadHash\n$previousHash" +
            (summary?.let { "\n" + json.encodeToString(TraceSummary.serializer(), it) } ?: "")).toByteArray())
    private fun validHash(r: TraceRecord) = r.hash == indexDigest(r.sequence, r.timestamp, r.source, r.payloadHash, r.previousHash, r.summary)
    private val legacySummaries = object : LinkedHashMap<String, TraceSummary>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TraceSummary>?) = size > 2000
    }

    /** A crash may leave an uncommitted final fragment. Preserve it before removing only that fragment. */
    private fun recoverUncommittedTail(index: File) {
        if (!index.exists() || index.length() == 0L) return
        java.io.RandomAccessFile(index, "rw").use { file ->
            file.seek(file.length() - 1)
            if (file.read() == 10) return
            var end = file.length()
            while (end > 0) { file.seek(end - 1); if (file.read() == 10) break; end-- }
            file.seek(end)
            val fragment = ByteArray((file.length() - end).toInt())
            file.readFully(fragment)
            atomic(File(index.parentFile, "uncommitted-${digest(fragment)}.fragment"), fragment)
            file.setLength(end); file.fd.sync()
        }
    }

    suspend fun append(id: String, source: String, payload: JsonObject): TraceRecord = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val dir = directory(id)
            val index = File(dir, "events.jsonl")
            val previous = if (heads.containsKey(id)) heads[id] else {
                recoverUncommittedTail(index)
                var last: TraceRecord? = null
                if (index.exists()) index.useLines { lines -> lines.forEach { line ->
                    val record = json.decodeFromString<TraceRecord>(line)
                    check(record.sequence == (last?.sequence ?: 0L) + 1 && record.previousHash == last?.hash.orEmpty() && validHash(record)) { "Session journal index integrity check failed" }
                    last = record
                } }
                heads[id] = last
                last
            }
            check(dir.usableSpace > 32L * 1024 * 1024) { "Session trace storage is full; free private storage before resuming" }
            val raw = payload.toString().toByteArray(Charsets.UTF_8)
            val payloadHash = digest(raw)
            val blobs = File(dir, "payloads").apply { check(isDirectory || mkdirs()) }
            val blob = File(blobs, "$payloadHash.json.gz")
            if (!blob.exists()) {
                val temp = File(blobs, "$payloadHash.tmp")
                FileOutputStream(temp).use { stream ->
                    GZIPOutputStream(stream).use { it.write(raw); it.finish(); stream.fd.sync() }
                }
                check(temp.renameTo(blob))
            }
            val sequence = (previous?.sequence ?: 0L) + 1L
            val at = clock()
            val prev = previous?.hash.orEmpty()
            val summary = traceSummary(source, payload)
            val hash = indexDigest(sequence, at, source, payloadHash, prev, summary)
            val record = TraceRecord(sequence, at, source, payloadHash, prev, hash, summary)
            FileOutputStream(index, true).use { it.write((json.encodeToString(TraceRecord.serializer(), record) + "\n").toByteArray()); it.fd.sync() }
            heads[id] = record
            changes.value++
            record
        }
    }
    suspend fun page(id: String, before: Long? = null, source: String? = null, query: String = "", limit: Int = 60): TracePage = withContext(Dispatchers.IO) {
        val readerContext = currentCoroutineContext()
        run {
            // A reader observes only complete lines. Payloads are immutable; searching must not hold the writer lock.
            val result = ArrayDeque<TraceRecord>()
            var total = 0L
            var error: String? = null
            val file = File(directory(id, create = false), "events.jsonl")
            if (file.exists()) file.useLines { lines -> lines.forEach { line ->
                readerContext.ensureActive()
                val record = runCatching { json.decodeFromString<TraceRecord>(line) }.getOrElse { error = "Incomplete or damaged event record"; return@forEach }
                if (!validHash(record)) { error = "Event hash mismatch"; return@forEach }
                total = maxOf(total, record.sequence)
                if (before != null && record.sequence >= before) return@forEach
                if (source != null && !record.source.startsWith(source)) return@forEach
                if (query.isNotBlank() && !record.source.contains(query, true) && !runCatching { payloadLocked(id, record).contains(query, true) }.getOrDefault(false)) return@forEach
                result.addLast(record)
                while (result.size > limit.coerceIn(1, 20_000)) result.removeFirst()
            } }
            TracePage(result.toList().asReversed(), result.firstOrNull()?.sequence?.takeIf { it > 1 }, total, error)
        }
    }
    /** Legacy journals are projected lazily, without rewriting their immutable event chain. */
    suspend fun trajectory(id: String, limit: Int = 600, before: Long? = null): TrajectoryPage = withContext(Dispatchers.IO) {
        val page = page(id, before = before, limit = limit)
        var error = page.error
        val entries = page.records.asReversed().mapNotNull { record ->
            currentCoroutineContext().ensureActive()
            try {
                val summary = record.summary ?: synchronized(legacySummaries) { legacySummaries[record.hash] }
                    ?: traceSummary(record.source, Json.parseToJsonElement(payloadLocked(id, record)).jsonObject).also {
                        synchronized(legacySummaries) { legacySummaries[record.hash] = it }
                    }
                TraceEntry(record, summary)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message; null }
        }
        TrajectoryPage(entries, page.total, page.records.size >= limit && page.before != null, error)
    }

    private fun payloadLocked(id: String, record: TraceRecord): String {
        require(record.payloadHash.matches(Regex("[a-f0-9]{64}")))
        val file = File(directory(id, create = false), "payloads/${record.payloadHash}.json.gz")
        val bytes = GZIPInputStream(file.inputStream()).use { it.readBytes() }
        check(digest(bytes) == record.payloadHash) { "Trace payload hash mismatch" }
        return bytes.toString(Charsets.UTF_8)
    }
    suspend fun payload(id: String, record: TraceRecord): String = withContext(Dispatchers.IO) { payloadLocked(id, record) }
    suspend fun saveTask(task: AgentTaskRecord) = withContext(Dispatchers.IO) { synchronized(lock) {
        atomic(File(directory(task.conversationId), "task.json"), json.encodeToString(AgentTaskRecord.serializer(), task).toByteArray())
        changes.value++
    } }
    suspend fun task(id: String): AgentTaskRecord? = withContext(Dispatchers.IO) { synchronized(lock) {
        File(directory(id, create = false), "task.json").takeIf { it.exists() }?.let { json.decodeFromString<AgentTaskRecord>(it.readText()) }
    } }
    suspend fun unfinished(): List<AgentTaskRecord> = withContext(Dispatchers.IO) { synchronized(lock) {
        root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            runCatching { json.decodeFromString<AgentTaskRecord>(File(dir, "task.json").readText()) }.getOrNull()
        }.filter { it.status in setOf("running", "waiting_network") && it.recoverAutomatically }
    } }

    suspend fun storageBytes(id: String? = null): Long = withContext(Dispatchers.IO) {
        val dir = if (id == null) root else directory(id, create = false)
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Removing a chat also forbids a still-finishing task from recreating its private journal. */
    suspend fun delete(id: String) = clear(id, preventFutureWrites = true)

    /** Old journals from deleted chats, including versions predating coordinated cleanup. */
    suspend fun cleanupOrphans(existingConversationIds: Set<String>): Int = withContext(Dispatchers.IO) {
        val cutoff = clock() - 10 * 60_000L
        val candidates = synchronized(lock) {
            root.listFiles().orEmpty().filter { dir ->
                dir.isDirectory && dir.name !in existingConversationIds && dir.lastModified() < cutoff &&
                    runCatching { java.util.UUID.fromString(dir.name).toString() == dir.name }.getOrDefault(false)
            }.map { it.name }
        }
        candidates.forEach { delete(it) }
        candidates.size
    }

    /** Explicit user cleanup; a future generation may start a fresh journal. */
    suspend fun clear(id: String, preventFutureWrites: Boolean = false) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val dir = directory(id, create = false)
            if (preventFutureWrites) deleted.add(id)
            check(!dir.exists() || dir.deleteRecursively()) { "Cannot delete the session trace" }
            heads.remove(id)
            synchronized(legacySummaries) { legacySummaries.clear() }
            changes.value++
        }
    }

    /** Strict allow-list, never a regex over potentially private prompt/tool content. */
    suspend fun diagnosticPreview(id: String, limit: Int = 200): String {
        val page = page(id, limit = limit.coerceIn(1, 200))
        return diagnosticSummary(page)
    }

    /** Export the entire recorded conversation, independently of Trajectory's display window. */
    suspend fun exportArchive(
        id: String,
        output: java.io.OutputStream,
        cacheDirectory: File,
        metadata: JsonObject = buildJsonObject {},
    ): TraceArchiveResult = withContext(Dispatchers.IO) {
        require(java.util.UUID.fromString(id).toString() == id)
        check(cacheDirectory.isDirectory || cacheDirectory.mkdirs())
        val staging = java.nio.file.Files.createTempDirectory(cacheDirectory.toPath(), "trace-export-").toFile()
        try {
            val readerContext = currentCoroutineContext()
            TraceArchiveWriter(staging, { readerContext.ensureActive() }, ::validHash) { session, target ->
                // Only the mutable index/checkpoint are copied under the writer lock. Payloads
                // are immutable. Compression and the potentially slow document provider run outside it.
                synchronized(lock) {
                    readerContext.ensureActive()
                    require(java.util.UUID.fromString(session).toString() == session)
                    val source = File(root, session)
                    check(target.isDirectory || target.mkdirs())
                    val exists = source.isDirectory
                    source.listFiles().orEmpty().filter {
                        it.name in setOf("events.jsonl", "task.json") || it.name.matches(Regex("uncommitted-[a-f0-9]{64}\\.fragment"))
                    }.forEach { file ->
                        readerContext.ensureActive()
                        file.inputStream().use { input -> target.resolve(file.name).outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                readerContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                            }
                        } }
                    }
                    TraceArchiveSnapshot(session, System.currentTimeMillis(), target,
                        File(source, "payloads").listFiles().orEmpty().filter {
                            it.isFile && it.name.matches(Regex("[a-f0-9]{64}\\.json\\.gz"))
                        }, exists)
                }
            }.write(id, output, metadata)
        } finally { staging.deleteRecursively() }
    }
    companion object {
        private val instances = ConcurrentHashMap<String, SessionJournal>()
        fun at(filesDir: File): SessionJournal = instances.getOrPut(filesDir.absolutePath) { SessionJournal(File(filesDir, "session_journal")) }
    }
}
