package me.rerere.rikkahub.data.ai.tools.local

import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class TermuxArchiveFilesTest {
    @Test fun `archives include age and size and deletion preserves unselected output`() {
        val root = Files.createTempDirectory("archives").toFile()
        try {
            fun archive(time: Long) = root.resolve("a".repeat(24)).resolve(UUID.randomUUID().toString()).apply {
                mkdirs()
                resolve("stdout.txt").writeText("А😀")
                resolve("metadata.json").writeText("""{"created_at_ms":$time}""")
            }
            val old = archive(100)
            val kept = archive(200)
            val files = TermuxArchiveFiles(root)
            val entries = files.list()
            assertEquals(listOf(200L, 100L), entries.map { it.createdAt })
            assertEquals(old.listFiles()!!.sumOf { it.length() }, entries.last().bytes)
            assertEquals(1, files.delete(listOf(entries.last(), entries.last())))
            assertFalse(old.exists())
            assertTrue(kept.resolve("stdout.txt").isFile)
            assertEquals(0, files.delete(listOf(entries.first().copy(owner = "../escape"))))
        } finally { root.deleteRecursively() }
    }

    @Test fun `legacy and incomplete archives remain available for cleanup`() {
        val root = Files.createTempDirectory("archives").toFile()
        try {
            val dir = root.resolve("b".repeat(24)).resolve(UUID.randomUUID().toString()).apply { mkdirs() }
            dir.resolve("stdout.txt").writeText("interrupted write")
            val files = TermuxArchiveFiles(root)
            assertEquals(1, files.list().size)
            assertTrue(files.list().single().createdAt > 0)
            assertEquals(1, files.delete(files.list()))
            assertEquals(0, files.list().size)
        } finally { root.deleteRecursively() }
    }
}
