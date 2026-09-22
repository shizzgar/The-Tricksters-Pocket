package me.rerere.rikkahub.skills

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Byte-preserving, deterministic package shared by imports and the Termux bridge. */
internal object SkillPackage {
    const val MAX_FILES = 200
    const val MAX_BYTES = 20L * 1024 * 1024
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun files(root: File): List<File> {
        require(!Files.isSymbolicLink(root.toPath())) { "Skill directory must not be a symbolic link" }
        val canonical = root.canonicalFile
        val files = mutableListOf<File>()
        var total = 0L
        root.walkTopDown().onEnter { dir ->
            require(!Files.isSymbolicLink(dir.toPath())) { "Symbolic links are not supported in skill packages" }
            require(dir.relativeTo(root).path.count { it == File.separatorChar } < 32) { "Skill directory is too deep" }
            true
        }.forEach { file ->
            require(!Files.isSymbolicLink(file.toPath())) { "Symbolic links are not supported in skill packages" }
            if (file.isFile && file.name != ".seeded") {
                require(file.canonicalPath.startsWith(canonical.path + File.separator)) { "File is outside the skill" }
                val relative = file.relativeTo(root).invariantSeparatorsPath
                require('\\' !in relative && relative != ".rikkahub-manifest.json") { "Unsupported skill path" }
                total += file.length()
                require(total <= MAX_BYTES && files.size < MAX_FILES) { "Skill package exceeds 200 files or 20 MiB" }
                files += file
            }
        }
        return files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }
    }

    fun readFiles(root: File): Map<String, ByteArray> {
        var total = 0L
        return files(root).associate { file ->
            val bytes = file.inputStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    require(total + output.size() + size <= MAX_BYTES) { "Skill package exceeds 20 MiB" }
                    output.write(buffer, 0, size)
                }
                output.toByteArray()
            }
            total += bytes.size
            require(total <= MAX_BYTES) { "Skill package exceeds 20 MiB" }
            val relative = file.relativeTo(root).invariantSeparatorsPath
            (if (relative.equals("SKILL.md", true)) "SKILL.md" else relative) to bytes
        }.also { require("SKILL.md" in it) { "Missing SKILL.md" } }
    }

    fun archive(root: File, destination: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        java.security.DigestOutputStream(destination.outputStream(), digest).use { stream ->
            ZipOutputStream(stream).use { zip ->
                readFiles(root).forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
