package me.rerere.rikkahub.data.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class PortableBackupCipherTest {
    private val payload = "synthetic credentials: test-key-123; db/attachments".repeat(25000).toByteArray()
    private fun encrypt(): ByteArray = ByteArrayOutputStream().also {
        PortableBackupCipher.encrypt(ByteArrayInputStream(payload), it, "correct-password".toCharArray())
    }.toByteArray()
    private fun decrypt(bytes: ByteArray, password: String): ByteArray = ByteArrayOutputStream().also {
        PortableBackupCipher.decrypt(ByteArrayInputStream(bytes), it, password.toCharArray())
    }.toByteArray()

    @Test fun `portable archive round trip uses random salt and nonce`() {
        val first = encrypt()
        val second = encrypt()
        assertFalse(first.contentEquals(second))
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("test-key-123"))
        assertArrayEquals(payload, decrypt(first, "correct-password"))
    }

    @Test fun `wrong password and modified ciphertext reject authentication`() {
        val encrypted = encrypt()
        assertTrue(runCatching { decrypt(encrypted, "wrong-password") }.exceptionOrNull() is IllegalArgumentException)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertTrue(runCatching { decrypt(encrypted, "correct-password") }.isFailure)
    }

    @Test fun `truncated or modified header fails before restore`() {
        val encrypted = encrypt()
        assertTrue(runCatching { decrypt(encrypted.copyOf(20), "correct-password") }.isFailure)
        encrypted[0] = 0
        assertTrue(runCatching { decrypt(encrypted, "correct-password") }.isFailure)
    }
    @Test fun `missing terminal frame and appended data are rejected`() {
        val encrypted = encrypt()
        assertTrue(runCatching { decrypt(encrypted.copyOf(encrypted.size - 20), "correct-password") }.isFailure)
        assertTrue(runCatching { decrypt(encrypted + byteArrayOf(1), "correct-password") }.isFailure)
    }
}
