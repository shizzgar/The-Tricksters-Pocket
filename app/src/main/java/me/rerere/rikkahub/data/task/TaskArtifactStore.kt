package me.rerere.rikkahub.data.task

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class TaskBrief(
    val goal: String = "",
    val acceptanceCriteria: String = "",
    // Null migrates previously saved, explicit briefs. Automatically captured files never opt in.
    val active: Boolean? = null,
    val reviewAssistantId: String? = null,
) {
    val isActive: Boolean get() = active != false && (goal.isNotBlank() || acceptanceCriteria.isNotBlank())
}

/** Portable identity of a review chat: policy files are intentionally excluded from backups. */
@Serializable
data class TaskReviewScope(val workspaceId: String? = null) {
    init { require(workspaceId == null || runCatching { java.util.UUID.fromString(workspaceId) }.isSuccess) { "Invalid review workspace ID" } }
}

@Serializable
data class TaskArtifact(
    val id: String,
    val conversationId: String,
    val assistantId: String,
    val workspaceId: String,
    val path: String,
    val label: String,
    val sha256: String,
    val sizeBytes: Long,
    val sourceToolCallId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "available",
    val error: String? = null,
    val diff: String? = null,
)

@Serializable
private data class TaskFiles(
    val brief: TaskBrief = TaskBrief(),
    val artifacts: List<TaskArtifact> = emptyList(),
    val capturedToolCalls: Set<String> = emptySet(),
    val reviewScope: TaskReviewScope? = null,
)

/** App-owned, atomic metadata. Workspace content stays in its original workspace. */
class TaskArtifactStore private constructor(private val directory: File) {
    private val mutex = Mutex()
    private val deletedConversations = mutableSetOf<String>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    private fun file(id: String): File {
        require(runCatching { java.util.UUID.fromString(id) }.isSuccess) { "Invalid conversation ID" }
        return File(directory, "$id.json")
    }
    private fun read(id: String): TaskFiles = file(id).let { if (it.exists()) json.decodeFromString(it.readText()) else TaskFiles() }
    private fun save(id: String, value: TaskFiles) {
        check(id !in deletedConversations) { "Conversation was deleted" }
        directory.mkdirs()
        val target = file(id)
        val temporary = File.createTempFile("task-", ".tmp", directory)
        try {
            temporary.outputStream().use { it.write(json.encodeToString(value).toByteArray()); it.fd.sync() }
            java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            mutableRevision.value++
        } finally { temporary.delete() }
    }
    suspend fun removeConversation(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val target = file(id)
        deletedConversations.add(id)
        require(!target.exists() || target.delete()) { "Could not remove task metadata" }
        mutableRevision.value++
    } }
    suspend fun reviewScope(id: String): TaskReviewScope? = withContext(Dispatchers.IO) { mutex.withLock { read(id).reviewScope } }
    suspend fun markReview(id: String, scope: TaskReviewScope) = withContext(Dispatchers.IO) { mutex.withLock {
        save(id, read(id).copy(reviewScope = scope))
    } }
    suspend fun brief(id: String): TaskBrief = withContext(Dispatchers.IO) { mutex.withLock { read(id).brief } }
    suspend fun saveBrief(id: String, brief: TaskBrief) = withContext(Dispatchers.IO) { mutex.withLock { save(id, read(id).copy(brief = brief)) } }
    suspend fun updateBriefText(id: String, goal: String, criteria: String): TaskBrief = withContext(Dispatchers.IO) { mutex.withLock {
        val old = read(id)
        require(old.brief.isActive) { "Task is closed. Use + in the chat to set it again." }
        val updated = old.brief.copy(goal = goal.trim(), acceptanceCriteria = criteria.trim())
        require(updated.isActive) { "Task goal or acceptance criteria is required" }
        save(id, old.copy(brief = updated))
        updated
    } }
    suspend fun closeBrief(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val old = read(id)
        save(id, old.copy(brief = old.brief.copy(active = false)))
    } }
    suspend fun setReviewAssistant(id: String, assistantId: String) = withContext(Dispatchers.IO) { mutex.withLock {
        require(runCatching { java.util.UUID.fromString(assistantId) }.isSuccess) { "Invalid reviewer ID" }
        val old = read(id)
        save(id, old.copy(brief = old.brief.copy(reviewAssistantId = assistantId)))
    } }
    suspend fun artifacts(ids: Collection<String>): List<TaskArtifact> = withContext(Dispatchers.IO) { mutex.withLock { ids.flatMap { read(it).artifacts } } }
    suspend fun setAvailability(item: TaskArtifact, status: String, error: String? = null) = withContext(Dispatchers.IO) { mutex.withLock {
        val old = read(item.conversationId)
        save(item.conversationId, old.copy(artifacts = old.artifacts.map { if (it.id == item.id) it.copy(status = status, error = error) else it }))
    } }
    suspend fun register(
        conversationId: String, assistantId: String, workspaceId: String, path: String,
        repository: WorkspaceRepository, label: String = path.substringAfterLast('/'),
        sourceToolCallId: String? = null, diff: String? = null, expectedSha256: String? = null,
    ): TaskArtifact {
        requireSafeArtifactPath(path)
        val size = repository.rootfsFileSize(workspaceId, path)
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        repository.exportRootfsFile(workspaceId, path, object : OutputStream() {
            override fun write(b: Int) { digest.update(b.toByte()); count++ }
            override fun write(b: ByteArray, off: Int, len: Int) { digest.update(b, off, len); count += len }
        })
        require(count == size) { "File changed while registering it; retry after the writer finishes" }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        require(expectedSha256 == null || hash == expectedSha256) { "File changed after the tool wrote it; register the completed result again" }
        val item = TaskArtifact(
            id = "$workspaceId:$path:$hash", conversationId = conversationId, assistantId = assistantId,
            workspaceId = workspaceId, path = path, label = label.ifBlank { path.substringAfterLast('/') },
            sha256 = hash, sizeBytes = size, sourceToolCallId = sourceToolCallId, diff = diff,
        )
        withContext(Dispatchers.IO) { mutex.withLock {
            val old = read(conversationId)
            save(conversationId, old.copy(artifacts = old.artifacts.filterNot { it.workspaceId == workspaceId && it.path == path } + item,
                capturedToolCalls = old.capturedToolCalls + listOfNotNull(sourceToolCallId)))
        } }
        return item
    }
    /** Only explicit success envelopes become results; tool exceptions/denials never do. */
    suspend fun captureToolOutputs(conversation: Conversation, assistant: Assistant, parts: List<UIMessagePart>, repository: WorkspaceRepository) {
        val workspace = assistant.workspaceId?.toString() ?: return
        for (tool in parts.filterIsInstance<UIMessagePart.Tool>()) {
            val output = successfulWorkspaceFileOutput(tool) ?: continue
            val captured = withContext(Dispatchers.IO) { mutex.withLock { tool.toolCallId in read(conversation.id.toString()).capturedToolCalls } }
            if (captured) continue
            val path = output["path"]?.jsonPrimitive?.contentOrNull ?: continue
            val workspaceId = output["workspace_id"]?.jsonPrimitive?.contentOrNull ?: workspace
            register(conversation.id.toString(), assistant.id.toString(), workspaceId, path, repository,
                sourceToolCallId = tool.toolCallId, diff = tool.output.firstOrNull()?.metadataAs<DiffMetadata>()?.diff,
                expectedSha256 = output["revision"]?.jsonPrimitive?.contentOrNull?.takeIf { it.matches(Regex("[a-f0-9]{64}")) })
        }
    }
    suspend fun exportVerified(item: TaskArtifact, repository: WorkspaceRepository, destination: File): File = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        try {
            destination.outputStream().use { repository.exportRootfsFile(item.workspaceId, item.path, it) }
            val digest = MessageDigest.getInstance("SHA-256")
            destination.inputStream().use { stream -> val buffer = ByteArray(8192); while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            require(digest.digest().joinToString("") { "%02x".format(it) } == item.sha256) { "The file changed after registration. Ask the agent to register the new result." }
            setAvailability(item, "available")
            destination
        } catch (e: Exception) {
            destination.delete()
            if (e is kotlinx.coroutines.CancellationException) throw e
            setAvailability(item, "unavailable", e.message)
            throw e
        }
    }
    companion object {
        /** Validate staged backup metadata before publishing it into the live task store. */
        fun validateBackupDocument(raw: String, expectedConversationId: String? = null) {
            val data = Json { ignoreUnknownKeys = true }.decodeFromString<TaskFiles>(raw)
            fun uuid(value: String) { require(runCatching { java.util.UUID.fromString(value) }.isSuccess) { "Invalid task metadata ID" } }
            expectedConversationId?.let(::uuid)
            data.brief.reviewAssistantId?.let(::uuid)
            data.artifacts.forEach { item ->
                uuid(item.conversationId); uuid(item.assistantId); uuid(item.workspaceId)
                require(expectedConversationId == null || expectedConversationId == item.conversationId) { "Artifact belongs to another conversation" }
                requireSafeArtifactPath(item.path)
                require(item.sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid artifact hash" }
                require(item.sizeBytes >= 0L && item.createdAt >= 0L) { "Invalid artifact metadata" }
                require(item.status in setOf("available", "unavailable")) { "Invalid artifact availability" }
                require(item.id == "${item.workspaceId}:${item.path}:${item.sha256}") { "Artifact identity does not match its path and hash" }
            }
        }
        private val instances = ConcurrentHashMap<String, TaskArtifactStore>()
        fun at(filesDir: File): TaskArtifactStore = instances.getOrPut(filesDir.absolutePath) { TaskArtifactStore(File(filesDir, "task-results")) }
    }
}

fun requireSafeArtifactPath(path: String) {
    require(path.startsWith('/') && path != "/" && path.none { it == '\u0000' || it == '\\' } && path.split('/').none { it == ".." || it == "." }) { "Artifact path must be an absolute normalized file path" }
}

fun successfulWorkspaceFileOutput(tool: UIMessagePart.Tool): JsonObject? {
    if (tool.toolName !in setOf("workspace_write_file", "workspace_edit_file") || !tool.isExecuted) return null
    return tool.output.filterIsInstance<UIMessagePart.Text>().mapNotNull {
        runCatching { Json.parseToJsonElement(it.text) as? JsonObject }.getOrNull()
    }.firstOrNull { output ->
        output["error"] == null && (output["isError"] as? JsonPrimitive)?.booleanOrNull != true &&
            (output["success"] as? JsonPrimitive)?.booleanOrNull != false &&
            (output["ok"] as? JsonPrimitive)?.booleanOrNull != false &&
            (output["isDirectory"] as? JsonPrimitive)?.booleanOrNull != true &&
            (output["path"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { requireSafeArtifactPath(it) }.isSuccess } == true &&
            (output["sizeBytes"] as? JsonPrimitive)?.longOrNull?.let { it >= 0L } == true
    }
}
