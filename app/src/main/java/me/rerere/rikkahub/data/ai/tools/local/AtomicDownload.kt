package me.rerere.rikkahub.data.ai.tools.local

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Publish only a complete download. A failed transfer never touches the old destination. */
internal fun replaceDownloadedFile(destination: File, download: (OutputStream) -> Unit) {
    val target = destination.absoluteFile
    val temporary = File.createTempFile(".download-", ".part", requireNotNull(target.parentFile))
    try {
        FileOutputStream(temporary).use { output ->
            download(output)
            output.fd.sync()
        }
        // Both files are siblings, so the rename stays on one filesystem. If atomic
        // replacement is unsupported, fail safely rather than truncate the old file.
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temporary.delete()
    }
}
