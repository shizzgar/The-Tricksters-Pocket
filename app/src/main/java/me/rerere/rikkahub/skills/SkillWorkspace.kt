package me.rerere.rikkahub.skills

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import me.rerere.rikkahub.data.files.SkillFrontmatterParser

internal object SkillPackageLocks {
    private val locks = ConcurrentHashMap<String, Any>()
    fun <T> withLock(root: File, block: () -> T): T = synchronized(locks.getOrPut(root.absolutePath) { Any() }, block)
}

internal data class SkillEntry(val path: String, val directory: Boolean, val size: Long, val modified: Long, val executable: Boolean)
internal data class SkillSnapshot(val entries: List<SkillEntry>, val revision: String, val canRestore: Boolean) {
    val files get() = entries.count { !it.directory }
    val bytes get() = entries.sumOf { it.size }
}
internal data class SkillDocument(val path: String, val bytes: ByteArray, val hash: String, val text: String?, val modified: Long = 0)
internal class SkillConflict : java.io.IOException("The package changed since it was opened. Reload it before saving; your draft has been kept.")

/** The editor never converts binary data to text. A package mutation is staged, checked and recoverable. */
internal class SkillWorkspace(private val root: File, stateRoot: File, private val name: String = root.name) {
    private val state = File(stateRoot, root.name)
    private val pending get() = File(state, "pending")
    private val backup get() = File(state, "previous")
    private val stage get() = File(state, "staging")
    private fun <T> locked(block: () -> T): T = SkillPackageLocks.withLock(root) {
        recover(root, state)
        require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) { "Skill package is unavailable" }
        block()
    }
    fun snapshot(): SkillSnapshot = locked { snapshotOf(root).copy(canRestore = backup.isDirectory) }
    fun open(path: String): SkillDocument = locked {
        val file = resolve(root, path)
        require(file.isFile && file.length() <= SkillPackage.MAX_BYTES) { "File is unavailable or exceeds 20 MiB" }
        val bytes = readBounded(file)
        SkillDocument(path, bytes, SkillPackage.digest(bytes), decodeText(bytes), file.lastModified())
    }
    fun write(path: String, bytes: ByteArray, revision: String, originalHash: String? = null, create: Boolean = false): SkillSnapshot = mutate(revision) { dir ->
        val target = resolve(dir, path)
        require(!target.isDirectory) { "A folder already uses this path" }
        require(!create || !target.exists()) { "A file already uses this path" }
        if (originalHash != null && (!target.isFile || SkillPackage.digest(target.readBytes()) != originalHash)) throw SkillConflict()
        require(bytes.size <= SkillPackage.MAX_BYTES) { "File exceeds 20 MiB" }
        target.parentFile!!.mkdirs()
        target.writeBytes(bytes)
    }
    fun createDirectory(path: String, revision: String): SkillSnapshot = mutate(revision) { dir ->
        val target = resolve(dir, path)
        require(!target.exists()) { "The destination already exists" }
        check(target.mkdirs()) { "Could not create the folder" }
    }
    fun importFiles(files: Map<String, ByteArray>, revision: String): SkillSnapshot = mutate(revision) { dir ->
        require(files.isNotEmpty()) { "No files selected" }
        files.forEach { (path, bytes) ->
            val target = resolve(dir, path)
            require(!target.exists()) { "Already exists: $path. Use Replace file explicitly." }
            target.parentFile!!.mkdirs(); target.writeBytes(bytes)
        }
    }
    fun move(paths: Set<String>, destination: String, revision: String): SkillSnapshot = mutate(revision) { dir ->
        val folder = if (destination.isBlank()) dir else resolve(dir, destination)
        require(folder.isDirectory) { "Choose an existing destination folder" }
        val sources = topLevel(paths)
        sources.forEach { path ->
            require(path != "SKILL.md") { "SKILL.md must remain at the package root" }
            val source = resolve(dir, path); val target = File(folder, source.name)
            require(source.exists() && !target.exists()) { "Missing source or destination already exists: $path" }
            require(!target.toPath().startsWith(source.toPath())) { "A folder cannot be moved into itself" }
        }
        sources.forEach { path -> val source = resolve(dir, path); check(source.renameTo(File(folder, source.name))) { "Could not move $path" } }
    }
    fun rename(path: String, destination: String, revision: String, copy: Boolean = false): SkillSnapshot = mutate(revision) { dir ->
        require(copy || path != "SKILL.md") { "SKILL.md must remain at the package root" }
        val source = resolve(dir, path); val target = resolve(dir, destination)
        require(source.exists() && !target.exists()) { "Missing source or destination already exists" }
        require(!target.toPath().startsWith(source.toPath())) { "A folder cannot contain itself" }
        target.parentFile!!.mkdirs()
        if (copy) copyTree(source, target) else check(source.renameTo(target)) { "Could not rename the file" }
    }
    fun delete(paths: Set<String>, revision: String): SkillSnapshot = mutate(revision) { dir ->
        val sources = topLevel(paths)
        require("SKILL.md" !in sources) { "SKILL.md cannot be deleted" }
        sources.forEach { path -> val file = resolve(dir, path); require(file.exists()); check(file.deleteRecursively()) { "Could not remove $path" } }
    }
    fun restore(revision: String): SkillSnapshot = locked {
        require(backup.isDirectory) { "No previous version is available" }
        mutate(revision) { dir ->
            dir.listFiles().orEmpty().forEach { check(it.deleteRecursively()) }
            backup.listFiles().orEmpty().forEach { copyTree(it, File(dir, it.name)) }
        }
    }
    fun export(destination: File): String = locked { SkillPackage.archive(root, destination) }

    private fun mutate(revision: String, edit: (File) -> Unit): SkillSnapshot = locked {
        if (snapshotOf(root).revision != revision) throw SkillConflict()
        state.mkdirs()
        check(!stage.exists() || stage.deleteRecursively())
        try {
            copyTree(root, stage)
            edit(stage)
            validateManifest(stage)
            File(stage, ".user-edited").writeText("1")
            snapshotOf(stage) // all limits and paths checked before touching the original
            if (snapshotOf(root).revision != revision) throw SkillConflict()
            check(root.renameTo(pending)) { "Could not stage the previous package" }
            if (!stage.renameTo(root)) { check(pending.renameTo(root)); error("Could not publish the edited package") }
            if (backup.exists()) check(backup.deleteRecursively())
            check(pending.renameTo(backup))
            snapshotOf(root).copy(canRestore = true)
        } finally {
            if (stage.exists()) stage.deleteRecursively()
            recover(root, state)
        }
    }
    private fun validateManifest(dir: File) {
        val manifest = dir.resolve("SKILL.md")
        require(manifest.isFile && manifest.length() <= 512 * 1024) { "SKILL.md is required and must be under 512 KiB" }
        val text = decodeText(manifest.readBytes()) ?: error("SKILL.md must contain UTF-8 text")
        val meta = SkillFrontmatterParser.parse(text)
        require(meta["name"] == name) { "Keep the skill name '$name' in SKILL.md" }
        require(!meta["description"].isNullOrBlank()) { "SKILL.md requires a description" }
    }
    companion object {
        const val MAX_EDIT_BYTES = 256 * 1024
        const val MAX_HEX_EDIT_BYTES = 64 * 1024
        val internalNames = setOf(".seeded", ".core-bundled-hash", ".user-edited", ".rikkahub-manifest.json")
        fun decodeText(bytes: ByteArray): String? = runCatching {
            require(bytes.none { (it.toInt() and 255) < 32 && it !in byteArrayOf(9, 10, 13) })
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        fun decodeHex(text: String): ByteArray {
            val compact = text.filterNot { it.isWhitespace() }
            require(compact.length % 2 == 0 && compact.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) { "Use complete hexadecimal byte pairs (00–FF)" }
            require(compact.length <= MAX_HEX_EDIT_BYTES * 2) { "Hex editing is limited to 64 KiB" }
            return ByteArray(compact.length / 2) { compact.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
        fun encodeHex(bytes: ByteArray, columns: Int = 16): String = bytes.asList().chunked(columns).joinToString("\n") { line -> line.joinToString(" ") { "%02X".format(it.toInt() and 255) } }
        fun validPath(path: String): Boolean = path.isNotBlank() && path.length <= 512 && !path.startsWith('/') && '\\' !in path && path.none { it.isISOControl() } &&
            path.split('/').let { parts -> parts.size <= 32 && parts.none { it.isBlank() || it in setOf(".", "..") || it in internalNames } }
        private fun resolve(root: File, path: String): File {
            require(validPath(path)) { "Use a relative path without '..' or reserved filenames" }
            var current = root
            path.split('/').forEach { part -> current = File(current, part); require(!Files.isSymbolicLink(current.toPath())) { "Symbolic links are not supported" } }
            require(current.canonicalPath.startsWith(root.canonicalPath + File.separator))
            return current
        }
        private fun topLevel(paths: Set<String>): List<String> {
            require(paths.isNotEmpty()) { "Select a file or folder" }
            return paths.filter { path -> paths.none { other -> path != other && path.startsWith("$other/") } }
        }
        private fun readBounded(file: File): ByteArray = file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                require(out.size().toLong() + n <= SkillPackage.MAX_BYTES) { "File exceeds 20 MiB" }
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        private fun copyTree(source: File, target: File) {
            require(!Files.isSymbolicLink(source.toPath())) { "Symbolic links are not supported" }
            if (source.isDirectory) {
                check(target.mkdirs() || target.isDirectory)
                source.listFiles().orEmpty().forEach { copyTree(it, File(target, it.name)) }
            } else {
                require(source.isFile) { "Unsupported file type" }
                target.writeBytes(readBounded(source)); target.setExecutable(source.canExecute(), true)
                target.setLastModified(source.lastModified())
            }
        }
        private fun snapshotOf(root: File): SkillSnapshot {
            val digest = MessageDigest.getInstance("SHA-256")
            val entries = mutableListOf<SkillEntry>()
            var bytes = 0L
            var files = 0
            root.walkTopDown().onEnter { require(!Files.isSymbolicLink(it.toPath())); true }.forEach { file ->
                if (file == root) return@forEach
                require(!Files.isSymbolicLink(file.toPath())) { "Symbolic links are not supported" }
                val path = file.relativeTo(root).invariantSeparatorsPath
                if (path in internalNames) return@forEach
                require(validPath(path)) { "Unsupported package path: $path" }
                require(file.isDirectory || file.isFile) { "Unsupported file type" }
                val size = if (file.isFile) file.length() else 0
                bytes += size; if (file.isFile) files++
                require(files <= SkillPackage.MAX_FILES && bytes <= SkillPackage.MAX_BYTES && entries.size < 400) { "Package exceeds 200 files, 400 entries or 20 MiB" }
                entries += SkillEntry(path, file.isDirectory, size, file.lastModified(), file.canExecute())
            }
            var hashedBytes = 0L
            entries.sortedBy { it.path }.forEach { entry ->
                digest.update((entry.path + if (entry.directory) "/\u0000" else "\u0000${entry.size}\u0000").toByteArray())
                if (!entry.directory) File(root, entry.path).inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) { val count = input.read(buffer); if (count < 0) break; hashedBytes += count; require(hashedBytes <= SkillPackage.MAX_BYTES); digest.update(buffer, 0, count) }
                }
            }
            return SkillSnapshot(entries, digest.digest().joinToString("") { "%02x".format(it) }, false)
        }
        fun recoverAll(skillsRoot: File, stateRoot: File) {
            stateRoot.listFiles().orEmpty().filter { it.isDirectory && validPath(it.name) }.forEach { state ->
                val root = File(skillsRoot, state.name)
                SkillPackageLocks.withLock(root) { recover(root, state) }
            }
        }
        private fun recover(root: File, state: File) {
            val pending = File(state, "pending")
            if (!root.exists() && pending.isDirectory) check(pending.renameTo(root)) { "Could not recover the previous package" }
            else if (root.isDirectory && pending.isDirectory) {
                val previous = File(state, "previous")
                if (previous.exists()) check(previous.deleteRecursively())
                check(pending.renameTo(previous))
            }
        }
    }
}
