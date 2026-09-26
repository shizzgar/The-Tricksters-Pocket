package me.rerere.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RevisionCheckedFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `stale editor cannot replace sibling changes`() {
        val file = temp.newFile("shared.txt").apply { writeText("initial") }
        val (_, version) = RevisionCheckedFiles.snapshot(file, 1024)
        RevisionCheckedFiles.write(file, "first".toByteArray(), true, version)
        assertThrows(IllegalArgumentException::class.java) { RevisionCheckedFiles.write(file, "second".toByteArray(), true, version) }
        assertEquals("first", file.readText())
    }

    @Test fun `simultaneous writers with one revision have only one winner`() {
        val file = temp.newFile("shared.txt").apply { writeText("initial") }
        val version = RevisionCheckedFiles.snapshot(file, 1024).second
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf("left", "right").map { text -> executor.submit<Boolean> {
                gate.await()
                runCatching { RevisionCheckedFiles.write(file, text.toByteArray(), true, version) }.isSuccess
            } }
            gate.countDown()
            assertEquals(1, futures.count { it.get(5, TimeUnit.SECONDS) })
            assertTrue(file.readText() in setOf("left", "right"))
        } finally { executor.shutdownNow() }
    }

    @Test fun `guarded overwrite requires read and does not leave temporary files`() {
        val file = temp.newFile("guarded.txt").apply { writeText("retain") }
        assertThrows(IllegalArgumentException::class.java) { RevisionCheckedFiles.write(file, byteArrayOf(1), true, null, requireRevision = true) }
        assertEquals("retain", file.readText())
        assertFalse(file.parentFile!!.listFiles()!!.any { it.name.startsWith(".pocket-write-") })
    }

    @Test fun `rootfs write refuses traversal and symlink escape`() {
        val base = temp.newFolder("workspaces")
        val manager = WorkspaceManager(base)
        manager.ensureWorkspace("one")
        val outside = temp.newFile("outside.txt").apply { writeText("retain") }
        assertThrows(IllegalArgumentException::class.java) { manager.writeRootfsText("one", "/workspace/../../outside.txt", "bad", true, null) }
        java.nio.file.Files.createSymbolicLink(File(manager.filesDir("one"), "link").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { manager.writeRootfsText("one", "/workspace/link", "bad", true, null) }
        assertEquals("retain", outside.readText())
    }

    @Test fun `new file can be created while directory and stale missing target are refused`() {
        val file = File(temp.root, "new.txt")
        RevisionCheckedFiles.write(file, "new".toByteArray(), false, null, true)
        assertEquals("new", file.readText())
        assertThrows(IllegalArgumentException::class.java) { RevisionCheckedFiles.write(file, byteArrayOf(), false, null) }
        assertThrows(IllegalArgumentException::class.java) { RevisionCheckedFiles.write(temp.root, byteArrayOf(), true, null) }
        assertThrows(IllegalArgumentException::class.java) { RevisionCheckedFiles.write(File(temp.root, "missing"), byteArrayOf(), true, "stale") }
    }
}
