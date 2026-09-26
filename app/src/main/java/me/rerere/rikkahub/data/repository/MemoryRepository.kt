package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryRevision

class MemoryConflictException : IllegalStateException("Memory changed since it was opened. Reload before editing.")

internal fun reviseMemory(old: MemoryEntity, content: String, deleted: Boolean, expectedRevision: Int?, sourceConversationId: String?, sourceMessageId: String?, now: Long): MemoryEntity {
    if (expectedRevision != null && old.revision != expectedRevision) throw MemoryConflictException()
    val history = Json.decodeFromString<List<MemoryRevision>>(old.history) + MemoryRevision(old.content, old.revision, old.updatedAt, old.deleted, old.sourceConversationId, old.sourceMessageId)
    return old.copy(content = content, deleted = deleted, revision = old.revision + 1, updatedAt = now,
        sourceConversationId = sourceConversationId ?: old.sourceConversationId,
        sourceMessageId = sourceMessageId ?: old.sourceMessageId,
        history = Json.encodeToString(history))
}

class MemoryRepository(private val memoryDAO: MemoryDAO) {
    private val writes = Mutex()
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
        fun projectScope(projectId: String) = "__project__:$projectId"
    }
    private fun MemoryEntity.model() = AssistantMemory(id, content, sourceConversationId, sourceMessageId, updatedAt, revision, assistantId, deleted, Json.decodeFromString(history))
    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> = memoryDAO.getMemoriesOfAssistantFlow(assistantId).map { rows -> rows.map { it.model() } }
    suspend fun getMemoriesOfAssistant(assistantId: String) = memoryDAO.getMemoriesOfAssistant(assistantId).map { it.model() }
    fun getGlobalMemoriesFlow() = getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
    suspend fun getGlobalMemories() = getMemoriesOfAssistant(GLOBAL_MEMORY_ID)
    fun ledger(scope: String) = memoryDAO.getMemoryLedgerFlow(scope).map { rows -> rows.map { it.model() } }
    suspend fun deleteMemoriesOfAssistant(assistantId: String) = memoryDAO.deleteMemoriesOfAssistant(assistantId)

    suspend fun updateContent(id: Int, content: String, expectedRevision: Int? = null, sourceConversationId: String? = null, sourceMessageId: String? = null): AssistantMemory = writes.withLock {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        check(!old.deleted) { "Memory was deleted. Restore it explicitly before editing." }
        val updated = reviseMemory(old, content, false, expectedRevision, sourceConversationId, sourceMessageId, System.currentTimeMillis())
        memoryDAO.updateMemory(updated)
        updated.model()
    }

    suspend fun addMemory(assistantId: String, content: String, sourceConversationId: String? = null, sourceMessageId: String? = null): AssistantMemory = writes.withLock {
        require(content.isNotBlank()) { "Memory content is empty" }
        // Identical facts are idempotent within one scope, never merged across projects.
        memoryDAO.getMemoriesOfAssistant(assistantId).firstOrNull { it.content.trim() == content.trim() }?.let { return@withLock it.model() }
        val entity = MemoryEntity(assistantId = assistantId, content = content, sourceConversationId = sourceConversationId, sourceMessageId = sourceMessageId, updatedAt = System.currentTimeMillis())
        entity.copy(id = memoryDAO.insertMemory(entity).toInt()).model()
    }

    suspend fun deleteMemory(id: Int, expectedRevision: Int? = null, sourceConversationId: String? = null, sourceMessageId: String? = null) = writes.withLock {
        val old = memoryDAO.getMemoryById(id) ?: return@withLock
        if (!old.deleted) memoryDAO.updateMemory(reviseMemory(old, old.content, true, expectedRevision, sourceConversationId, sourceMessageId, System.currentTimeMillis()))
    }

    suspend fun restore(id: Int, revision: Int, expectedRevision: Int): AssistantMemory = writes.withLock {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        val selected = Json.decodeFromString<List<MemoryRevision>>(old.history).firstOrNull { it.revision == revision } ?: error("Revision not found")
        val restored = reviseMemory(old, selected.content, selected.deleted, expectedRevision, selected.sourceConversationId, selected.sourceMessageId, System.currentTimeMillis())
        memoryDAO.updateMemory(restored)
        restored.model()
    }
}
