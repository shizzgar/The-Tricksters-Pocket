package me.rerere.rikkahub.data.project

import me.rerere.rikkahub.data.repository.ProjectReferenceFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.Base64

class ProjectReferenceReaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun reference(path: String, mime: String = "text/plain") = ProjectReferenceFile("Reference", path, mime)

    @Test fun `paged project text can be read without any workspace or Termux mount`() {
        val root = temporary.newFolder()
        val file = File(root, "upload/reference.md").apply { parentFile!!.mkdirs(); writeText("a🦊б\nsecond line") }
        val reader = ProjectReferenceReader(root)
        val ref = reference("upload/${file.name}")
        val first = reader.read(ref, limit = 2)
        val second = reader.read(ref, cursor = first.nextCursor)
        assertEquals(file.readText(), first.content + second.content)
        assertTrue(first.hasMore)
        assertFalse(second.hasMore)
        assertEquals(file.readText().length.toLong(), second.nextCursor)
    }

    @Test fun `binary pages preserve every byte and enforce page bound`() {
        val root = temporary.newFolder()
        val bytes = ByteArray(18000) { it.toByte() }
        File(root, "upload/reference.bin").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val reader = ProjectReferenceReader(root)
        val ref = reference("upload/reference.bin", "application/octet-stream")
        val output = java.io.ByteArrayOutputStream()
        var cursor = 0L
        do {
            val page = reader.read(ref, cursor, Int.MAX_VALUE, rawBytes = true)
            val decoded = Base64.getDecoder().decode(page.content)
            assertTrue(decoded.size <= 8192)
            output.write(decoded)
            cursor = page.nextCursor
        } while (page.hasMore)
        assertArrayEquals(bytes, output.toByteArray())
        assertEquals(bytes.size.toLong(), cursor)
    }

    @Test fun `reference traversal and symlink outside uploads fail closed`() {
        val root = temporary.newFolder()
        val secret = File(root, "settings.json").apply { writeText("private") }
        File(root, "upload").mkdirs()
        val reader = ProjectReferenceReader(root)
        listOf("upload/../settings.json", secret.absolutePath).forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { reader.read(reference(path)) }
        }
        Files.createSymbolicLink(File(root, "upload/link.txt").toPath(), secret.toPath())
        assertThrows(IllegalArgumentException::class.java) { reader.read(reference("upload/link.txt")) }
    }

    @Test fun `large references reject eager text extraction but permit bounded raw reads`() {
        val root = temporary.newFolder()
        val file = File(root, "upload/large.txt").apply { parentFile!!.mkdirs() }
        java.io.RandomAccessFile(file, "rw").use { it.setLength(ProjectReferenceReader.MAX_TEXT_FILE_BYTES + 1) }
        val reader = ProjectReferenceReader(root)
        assertThrows(IllegalArgumentException::class.java) { reader.read(reference("upload/large.txt")) }
        assertEquals(8192L, reader.read(reference("upload/large.txt"), rawBytes = true).nextCursor)
    }

    @Test fun `document extractor result uses same bounded paging contract`() {
        val root = temporary.newFolder()
        File(root, "upload/reference.pdf").apply { parentFile!!.mkdirs(); writeText("fixture") }
        val page = ProjectReferenceReader(root).read(reference("upload/reference.pdf", "application/pdf"), limit = 4) { file, mime ->
            assertEquals("fixture", file.readText())
            assertEquals("application/pdf", mime)
            "extracted document"
        }
        assertEquals("extr", page.content)
        assertEquals(4L, page.nextCursor)
        assertTrue(page.hasMore)
    }
}
