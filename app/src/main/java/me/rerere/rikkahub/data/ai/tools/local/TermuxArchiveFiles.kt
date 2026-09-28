package me.rerere.rikkahub.data.ai.tools.local

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Comparator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class TermuxArchiveEntry(val owner: String, val ref: String, val bytes: Long, val createdAt: Long) {
    val key: String get() = "$owner/$ref"
}

/** Enumerates only app-owned archive directories, including incomplete writes for cleanup. */
internal class TermuxArchiveFiles(private val root: File) {
    private fun safeDirectory(owner: String, ref: String): File? {
        if (!owner.matches(Regex("[a-f0-9]{24}")) || !ref.matches(Regex("[a-f0-9-]{36}"))) return null
        val directory = File(File(root, owner), ref)
        if (directory.canonicalFile != File(root.canonicalFile, "$owner/$ref")) return null
        return directory.takeIf { it.isDirectory }
    }

    fun list(): List<TermuxArchiveEntry> = root.listFiles().orEmpty().flatMap { owner ->
        owner.listFiles().orEmpty().mapNotNull { candidate ->
            val dir = safeDirectory(owner.name, candidate.name) ?: return@mapNotNull null
            val metadata = File(dir, "metadata.json")
            val createdAt = runCatching {
                Json.parseToJsonElement(metadata.readText()).jsonObject["created_at_ms"]?.jsonPrimitive?.longOrNull
            }.getOrNull() ?: metadata.lastModified().takeIf { it > 0 } ?: dir.lastModified()
            val size = Files.walk(dir.toPath()).use { paths ->
                paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.mapToLong { Files.size(it) }.sum()
            }
            TermuxArchiveEntry(owner.name, dir.name, size, createdAt)
        }
    }.sortedByDescending { it.createdAt }

    fun delete(entries: List<TermuxArchiveEntry>): Int = entries.distinctBy { it.key }.count { entry ->
        val directory = safeDirectory(entry.owner, entry.ref) ?: return@count false
        Files.walk(directory.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
        directory.parentFile?.let { if (it.listFiles()?.isEmpty() == true) it.delete() }
        true
    }
}
