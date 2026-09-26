package me.rerere.rikkahub.data.repository

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.MemoryRevision
import org.junit.Assert.*
import org.junit.Test

class MemoryRevisionTest {
    @Test fun `revision retains content and original source before editing`() {
        val old = MemoryEntity(id = 4, assistantId = "bro", content = "old", sourceConversationId = "c1", sourceMessageId = "m1", updatedAt = 10)
        val revised = reviseMemory(old, "new", false, 0, "c2", "m2", 20)
        val history = Json.decodeFromString<List<MemoryRevision>>(revised.history)
        assertEquals("old", history.single().content)
        assertEquals("m1", history.single().sourceMessageId)
        assertEquals("m2", revised.sourceMessageId)
        assertEquals(1, revised.revision)
    }
    @Test fun `stale edit fails instead of overwriting`() {
        assertThrows(MemoryConflictException::class.java) { reviseMemory(MemoryEntity(assistantId = "bro", revision = 3), "stale", false, 2, null, null, 20) }
    }
    @Test fun `forget keeps reversible previous revision`() {
        val deleted = reviseMemory(MemoryEntity(assistantId = "bro", content = "fact"), "fact", true, 0, null, null, 20)
        assertTrue(deleted.deleted)
        assertFalse(Json.decodeFromString<List<MemoryRevision>>(deleted.history).single().deleted)
    }
    @Test fun `project facts have distinct scope from assistants and global facts`() {
        assertNotEquals(MemoryRepository.GLOBAL_MEMORY_ID, MemoryRepository.projectScope("abc"))
        assertNotEquals(MemoryRepository.projectScope("abc"), MemoryRepository.projectScope("def"))
    }
}
