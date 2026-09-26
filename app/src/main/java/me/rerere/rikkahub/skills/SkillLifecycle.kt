package me.rerere.rikkahub.skills

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.files.SkillFrontmatterParser

internal enum class SkillInstallChoice { NEW, COPY, UPDATE }
internal data class SkillFileChange(val path: String, val before: ByteArray?, val after: ByteArray?) {
    val kind get() = when { before == null -> "+"; after == null -> "−"; else -> "~" }
    /** Prefix/suffix context diff: all changed lines are shown in one bounded change region. */
    fun preview(): String {
        fun text(bytes: ByteArray?): String? = if (bytes == null) "" else if (bytes.size <= 512 * 1024) SkillWorkspace.decodeText(bytes) else null
        val old = text(before); val next = text(after)
        if (old == null || next == null) return listOf("Before" to before, "After" to after).joinToString("\n") { (label, bytes) ->
            "$label: ${bytes?.size ?: 0} B · SHA-256 ${bytes?.let(SkillPackage::digest) ?: "∅"}"
        }
        val left = if (old.isEmpty()) emptyList() else old.lines()
        val right = if (next.isEmpty()) emptyList() else next.lines()
        var prefix = 0
        while (prefix < minOf(left.size, right.size) && left[prefix] == right[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(left.size, right.size) - prefix && left[left.lastIndex - suffix] == right[right.lastIndex - suffix]) suffix++
        return buildString {
            appendLine("@@ -${prefix + 1},${left.size - prefix - suffix} +${prefix + 1},${right.size - prefix - suffix} @@")
            fun line(mark: String, text: String) { if (length < 24_000) appendLine(mark + text.take((24_000 - length).coerceAtLeast(0))) }
            left.subList(maxOf(0, prefix - 3), prefix).forEach { line(" ", it) }
            left.subList(prefix, left.size - suffix).forEach { line("−", it) }
            right.subList(prefix, right.size - suffix).forEach { line("+", it) }
            if (length >= 24_000) appendLine("\n… Preview truncated · SHA-256 ${after?.let(SkillPackage::digest) ?: "∅"}")
            else right.subList(right.size - suffix, minOf(right.size, right.size - suffix + 3)).forEach { line(" ", it) }
        }
    }
}
internal data class SkillImportProposal(
    val name: String,
    val source: String,
    val version: String?,
    val requirements: SkillRequirements,
    val files: Map<String, ByteArray>,
    val expectedRevision: String?,
    val changes: List<SkillFileChange>,
) {
    val bytes get() = files.values.sumOf { it.size.toLong() }
}

/** Uses the workbench transaction and the same previous-version slot, including crash recovery. */
internal class SkillLifecycle(private val skillsRoot: File, private val stateRoot: File) {
    fun preview(name: String, files: Map<String, ByteArray>, source: String): SkillImportProposal {
        require(validName(name)) { "Use 1–40 lowercase letters, digits, underscores or hyphens" }
        require(files.size <= SkillPackage.MAX_FILES && files.values.sumOf { it.size.toLong() } <= SkillPackage.MAX_BYTES)
        require(files.keys.all(SkillWorkspace::validPath)) { "Invalid or reserved package path" }
        val markdown = requireNotNull(files["SKILL.md"]) { "Missing SKILL.md" }
        require(markdown.size <= 512 * 1024)
        val meta = SkillFrontmatterParser.parse(requireNotNull(SkillWorkspace.decodeText(markdown)))
        require(meta["name"] == name && !meta["description"].isNullOrBlank()) { "SKILL.md requires a matching name and description" }
        val root = File(skillsRoot, name)
        SkillWorkspace.recoverAll(skillsRoot, stateRoot)
        return SkillPackageLocks.withLock(root) {
            val workspace = if (root.exists()) SkillWorkspace(root, stateRoot, name) else null
            val revision = workspace?.snapshot()?.revision
            val previous = if (workspace != null) SkillPackage.readFiles(root) else emptyMap()
            if (workspace != null && workspace.snapshot().revision != revision) throw SkillConflict()
            // Snapshot the candidate, so a caller cannot change reviewed bytes before installation.
            val copied = files.mapValues { it.value.copyOf() }
            SkillImportProposal(name, source.take(2048), meta.scalar("version"), SkillRequirements.parse(markdown.toString(Charsets.UTF_8)), copied, revision,
                (previous.keys + copied.keys).sorted().mapNotNull { path ->
                    val old = previous[path]; val next = copied[path]
                    if (old != null && next != null && old.contentEquals(next)) null else SkillFileChange(path, old, next)
                })
        }
    }
    fun install(proposal: SkillImportProposal, choice: SkillInstallChoice, copyName: String? = null): String {
        val name = if (choice == SkillInstallChoice.COPY) requireNotNull(copyName).trim() else proposal.name
        require(validName(name)) { "Use 1–40 lowercase letters, digits, underscores or hyphens" }
        if (choice == SkillInstallChoice.COPY) require(name != proposal.name) { "Choose a different name for the copy" }
        val files = proposal.files.toMutableMap()
        if (choice == SkillInstallChoice.COPY) {
            val md = requireNotNull(files["SKILL.md"]).toString(Charsets.UTF_8)
            val fence = Regex("\\r?\\n---(?:\\r?\\n|$)").find(md, 3) ?: error("Missing frontmatter")
            files["SKILL.md"] = (md.substring(0, fence.range.first).replace(Regex("(?m)^name:.*$"), "name: $name") + md.substring(fence.range.first)).toByteArray()
        }
        val origin = Json.encodeToString(SkillOrigin.serializer(), SkillOrigin(proposal.source, System.currentTimeMillis()))
        val root = File(skillsRoot, name)
        SkillPackageLocks.withLock(root) {
            if (choice == SkillInstallChoice.UPDATE) {
                val revision = requireNotNull(proposal.expectedRevision) { "Nothing was installed when the preview was opened; reload it" }
                SkillWorkspace(root, stateRoot, name).replacePackage(files, revision, origin)
            } else {
                // No existence check followed by replace: create holds the package lock throughout.
                require(choice != SkillInstallChoice.NEW || proposal.expectedRevision == null) { "Choose Update or Install copy for an existing skill" }
                SkillWorkspace.create(root, stateRoot, name, files, origin)
            }
        }
        return name
    }
    companion object { fun validName(name: String) = name.matches(Regex("[a-z0-9][a-z0-9_-]{0,39}")) }
}

@Serializable
internal data class SkillOrigin(val source: String, val installedAt: Long)
internal data class SkillRequirements(val tools: List<String>, val anyTools: List<String>, val skills: List<String>, val environment: List<String>) {
    companion object {
        fun parse(markdown: String): SkillRequirements {
            val meta = SkillFrontmatterParser.parse(markdown)
            return SkillRequirements(meta.list("requires-tools"), meta.list("requires-any-tools"), meta.list("requires-skills"), meta.list("requires-env"))
        }
    }
    fun missingTools(available: Set<String>): List<String> = tools.filterNot { it in available } +
        if (anyTools.isNotEmpty() && anyTools.none { it in available }) listOf(anyTools.joinToString(" / ")) else emptyList()
}

@Serializable
internal data class SkillTestRecord(
    val revision: String, val assistantId: String, val prompt: String, val timestamp: Long,
    val outcome: String, val output: String, val modelId: String? = null,
)
/** Bounded history stays outside exported packages; a response is not a correctness verdict. */
internal class SkillTestHistory(private val file: File) {
    fun read(): List<SkillTestRecord> = synchronized(lock) {
        runCatching { json.decodeFromString<List<SkillTestRecord>>(file.readText()) }.getOrDefault(emptyList())
    }
    fun append(record: SkillTestRecord) = synchronized(lock) {
        val records = (read() + record.copy(prompt = record.prompt.take(16_000), output = record.output.take(32_000))).takeLast(12)
        file.parentFile!!.mkdirs()
        val tmp = File.createTempFile("tests-", ".tmp", file.parentFile)
        try { tmp.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(SkillTestRecord.serializer()), records));
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { tmp.delete() }
    }
    companion object { private val lock = Any(); private val json = Json { ignoreUnknownKeys = true } }
}
