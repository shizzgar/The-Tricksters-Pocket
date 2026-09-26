package me.rerere.rikkahub.data.sync

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable authenticated chunks keep memory bounded, including Android GCM decrypt providers. */
internal object PortableBackupCipher {
    const val ENTRY = "encrypted-backup.bin"
    private val MAGIC = "POCKET-BACKUP-1\n".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 8
    private const val CHUNK_BYTES = 1024 * 1024
    private const val MAX_BYTES = 4L * 1024 * 1024 * 1024

    fun encrypt(input: InputStream, output: OutputStream, password: CharArray) {
        require(password.size >= 8) { "Use a backup password of at least 8 characters" }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val header = MAGIC + salt + nonce
        val key = derive(password, salt)
        val out = DataOutputStream(output)
        out.write(header)
        var sequence = 0
        var total = 0L
        try {
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                var size = 0
                while (size < buffer.size) {
                    val count = input.read(buffer, size, buffer.size - size)
                    if (count < 0) break
                    size += count
                }
                total += size
                require(total <= MAX_BYTES) { "Backup exceeds the 4 GiB limit" }
                out.writeInt(size)
                out.write(cipher(Cipher.ENCRYPT_MODE, key, header, nonce, sequence++, size).doFinal(buffer, 0, size))
                // Authenticated zero-length final frame proves completeness, even at exact chunk boundaries.
                if (size == 0) break
            }
        } finally { key.fill(0) }
    }

    /** Callers stage privately and publish only after the authenticated final frame was checked. */
    fun decrypt(input: InputStream, output: OutputStream, password: CharArray) {
        require(password.isNotEmpty()) { "Enter the password for this encrypted backup" }
        val source = DataInputStream(input)
        val header = ByteArray(MAGIC.size + SALT_BYTES + NONCE_BYTES)
        source.readFully(header)
        require(header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Unsupported encrypted backup" }
        val key = derive(password, header.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES))
        val nonce = header.takeLast(NONCE_BYTES).toByteArray()
        var sequence = 0
        var total = 0L
        try {
            while (true) {
                val size = source.readInt()
                require(size in 0..CHUNK_BYTES) { "Invalid encrypted backup frame" }
                total += size
                require(total <= MAX_BYTES) { "Backup exceeds the 4 GiB limit" }
                val frame = ByteArray(size + 16)
                source.readFully(frame)
                val plain = cipher(Cipher.DECRYPT_MODE, key, header, nonce, sequence++, size).doFinal(frame)
                if (size == 0) {
                    require(source.read() == -1) { "Unexpected trailing data in encrypted backup" }
                    break
                }
                output.write(plain)
            }
        } catch (e: javax.crypto.AEADBadTagException) {
            throw IllegalArgumentException("Incorrect password or damaged encrypted backup", e)
        } finally { key.fill(0) }
    }

    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    private fun cipher(mode: Int, key: ByteArray, header: ByteArray, nonce: ByteArray, sequence: Int, size: Int): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce + ByteBuffer.allocate(4).putInt(sequence).array()))
            updateAAD(header + ByteBuffer.allocate(8).putInt(sequence).putInt(size).array())
        }
}
