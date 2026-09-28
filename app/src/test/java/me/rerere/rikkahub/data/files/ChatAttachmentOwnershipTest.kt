package me.rerere.rikkahub.data.files

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ChatAttachmentOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `only imported attachments belong to chat cleanup`() {
        val root = temporary.newFolder("files")
        val upload = File(root, "upload").apply { mkdirs() }
        val imported = File(upload, "attachment.png").apply { writeText("imported copy") }
        assertEquals("upload/attachment.png", FileUtils.ownedChatAttachmentRelativePath(root, imported))
        listOf(
            File(temporary.root, "original.png"),
            File(root, "images/generated.png"),
            File(root, "workspace/source.png"),
            File(root, "settings.json"),
            File(root, "upload-other/attachment.png"),
            upload,
        ).forEach { assertNull(it.path, FileUtils.ownedChatAttachmentRelativePath(root, it)) }
    }

    @Test fun `symlinks cannot turn an external file into a chat attachment`() {
        val root = temporary.newFolder("files")
        val upload = File(root, "upload").apply { mkdirs() }
        val original = temporary.newFile("original.png").apply { writeText("keep") }
        val link = File(upload, "link.png")
        Files.createSymbolicLink(link.toPath(), original.toPath())
        assertNull(FileUtils.ownedChatAttachmentRelativePath(root, link))
        assertEquals("keep", original.readText())
    }

    @Test fun `a replaced upload directory is not owned cleanup storage`() {
        val root = temporary.newFolder("files")
        val external = temporary.newFolder("external")
        val original = File(external, "original.png").apply { writeText("keep") }
        Files.createSymbolicLink(File(root, "upload").toPath(), external.toPath())
        assertNull(FileUtils.ownedChatAttachmentRelativePath(root, File(root, "upload/original.png")))
        assertEquals("keep", original.readText())
    }
}
