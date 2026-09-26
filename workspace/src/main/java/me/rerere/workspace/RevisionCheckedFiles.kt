package me.rerere.workspace

import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Serializes app-managed editors; shell programs must still coordinate their own writes. */
object RevisionCheckedFiles {
    private val locks = Array(128) { Any() }
    fun revision(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun revision(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun lock(file: File) = locks[(file.canonicalPath.hashCode() and Int.MAX_VALUE) % locks.size]
    fun snapshot(file: File, maxBytes: Long): Pair<String, String> = synchronized(lock(file)) {
        require(file.isFile) { "File not found: ${file.name}" }
        require(file.length() <= maxBytes) { "File is too large to edit" }
        val bytes = java.io.ByteArrayOutputStream().use { output ->
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    require(output.size().toLong() + size <= maxBytes) { "File grew beyond the read limit" }
                    output.write(buffer, 0, size)
                }
            }
            output.toByteArray()
        }
        bytes.toString(Charsets.UTF_8) to revision(bytes)
    }
    fun write(file: File, bytes: ByteArray, overwrite: Boolean, expectedRevision: String?, requireRevision: Boolean = false) = synchronized(lock(file)) {
        require(!file.exists() || file.isFile) { "Path is not a file" }
        require(!file.exists() || overwrite) { "File already exists" }
        require(!file.exists() || !requireRevision || expectedRevision != null) { "Read the file first and pass expected_revision before overwriting it" }
        require(expectedRevision == null || (file.isFile && revision(file) == expectedRevision)) {
            "Revision conflict: the file changed after it was read. Reload and reapply the edit."
        }
        file.parentFile?.mkdirs()
        val temporary = File.createTempFile(".pocket-write-", ".tmp", file.parentFile)
        try {
            temporary.outputStream().use { stream -> stream.write(bytes); stream.fd.sync() }
            // Recheck just before replacement to detect external writers during staging.
            require(expectedRevision == null || (file.isFile && revision(file) == expectedRevision)) { "Revision conflict: reload the file" }
            val executable = file.canExecute()
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            if (executable) file.setExecutable(true, false)
        } finally { temporary.delete() }
    }
}
