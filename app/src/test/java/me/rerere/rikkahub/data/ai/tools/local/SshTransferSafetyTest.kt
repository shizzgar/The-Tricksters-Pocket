package me.rerere.rikkahub.data.ai.tools.local

import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SshTransferSafetyTest {
    @Test fun `failed remote read preserves destination and removes temporary download`() {
        val root = Files.createTempDirectory("download-test").toFile()
        try {
            val destination = root.resolve("important.txt").apply { writeText("old bytes") }
            assertThrows(IOException::class.java) {
                replaceDownloadedFile(destination) { output ->
                    output.write("partial".toByteArray())
                    throw IOException("connection lost")
                }
            }
            assertEquals("old bytes", destination.readText())
            assertEquals(listOf("important.txt"), root.list()!!.toList())
            replaceDownloadedFile(destination) { it.write("complete".toByteArray()) }
            assertEquals("complete", destination.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun `failed new download leaves no apparent result`() {
        val root = Files.createTempDirectory("download-test").toFile()
        try {
            assertThrows(IOException::class.java) {
                replaceDownloadedFile(root.resolve("new.bin")) { throw IOException("remote missing") }
            }
            assertTrue(root.list()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun `default route success remains reachable when a bound route failed`() {
        val outcome = ProbeOutcome(null, "default", listOf("wifi" to "timeout"), "192.0.2.1", 30)
        assertTrue(outcome.reachable)
        assertNull(outcome.winningNetwork)
        assertFalse(outcome.copy(winningLabel = null).reachable)
        assertFalse(outcome.copy(winningLabel = null, failures = emptyList()).reachable)
    }
}
