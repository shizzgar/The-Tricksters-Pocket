package me.rerere.rikkahub.skills

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillMetadata
import java.util.Base64

/** A caller-scoped boundary, shared by live chat and headless runs. */
internal interface SkillManagementAccess {
    fun allowed(tool: String): Boolean
    fun skills(): List<SkillMetadata>
    fun workspace(name: String): SkillWorkspace
    suspend fun create(name: String, files: Map<String, ByteArray>)
    suspend fun delete(name: String, revision: String): Boolean
    fun changed(name: String)
}

internal fun createSkillManagementTools(access: SkillManagementAccess): List<Tool> {
    fun field(description: String, type: String = "string") = buildJsonObject {
        put("type", type); put("description", description)
    }
    val nameField = field("Connected skill name. Changes affect the shared package used by all assistants.")
    val revisionField = field("Exact revision returned by skill_list_files or skill_read_file. Reload after any mutation.")
    fun tool(name: String, description: String, fields: Map<String, JsonObject>, required: List<String>, write: Boolean = false,
             run: suspend (JsonObject) -> JsonObject): Tool = Tool(
        name = name, description = description,
        parameters = { InputSchema.Obj(properties = JsonObject(fields), required = required) },
        needsApproval = { write },
        execute = { input -> withContext(Dispatchers.IO) {
            val result = try {
                require(access.allowed(name)) { "Skill management is disabled or no skills are connected for the calling assistant" }
                run(input as? JsonObject ?: error("Expected an object"))
            } catch (e: CancellationException) { throw e
            } catch (e: SkillConflict) {
                buildJsonObject { put("ok", false); put("error", "revision_conflict"); put("detail", "Reload the file and revision before retrying. Nothing was overwritten.") }
            } catch (e: Exception) {
                buildJsonObject { put("ok", false); put("error", "skill_operation_failed"); put("detail", e.message ?: "Operation failed") }
            }
            listOf(UIMessagePart.Text(result.toString()))
        } },
    )
    fun resolve(args: JsonObject): Pair<String, SkillWorkspace> {
        val name = args.string("name")
        require(access.skills().any { it.name == name }) { "Skill is not connected or no longer exists" }
        return name to access.workspace(name)
    }
    suspend fun mutate(args: JsonObject, action: (SkillWorkspace, String) -> SkillSnapshot): JsonObject {
        val (name, workspace) = resolve(args)
        return try { snapshotJson(name, action(workspace, args.string("revision"))) }
        finally { access.changed(name) }
    }
    return listOf(
        tool("skill_list_files", "List a connected skill's complete file tree, byte sizes and revision for safe editing. Includes scripts and binary assets; never executes them.",
            mapOf("name" to nameField), listOf("name")) { args ->
            val (name, workspace) = resolve(args)
            snapshotJson(name, workspace.snapshot())
        },
        tool("skill_read_file", "Read a skill file, including the full SKILL.md frontmatter. Returns SHA-256 and package revision. Reads at most 16 KiB per call; use next_offset to continue. Binary or split UTF-8 chunks return base64. Read before editing.",
            mapOf("name" to nameField, "path" to field("Relative file path"), "offset" to field("Byte offset, default 0", "integer")), listOf("name", "path")) { args ->
            val (name, workspace) = resolve(args)
            val meta = access.skills().first { it.name == name }
            SkillPackageLocks.withLock(meta.skillDir) {
                val snapshot = workspace.snapshot()
                val doc = workspace.open(args.string("path"))
                val offset = args["offset"]?.jsonPrimitive?.intOrNull ?: 0
                require(offset in 0..doc.bytes.size) { "Offset is outside the file" }
                val end = minOf(doc.bytes.size, offset + 16 * 1024)
                val bytes = doc.bytes.copyOfRange(offset, end)
                val text = SkillWorkspace.decodeText(bytes)
                buildJsonObject {
                    put("ok", true); put("name", name); put("path", doc.path); put("revision", snapshot.revision)
                    put("sha256", doc.hash); put("size_bytes", doc.bytes.size); put("offset", offset)
                    put("encoding", if (text == null) "base64" else "utf8")
                    put("content", text ?: Base64.getEncoder().encodeToString(bytes))
                    if (end < doc.bytes.size) put("next_offset", end)
                }
            }
        },
        tool("skill_create", "Create a new skill with SKILL.md and connect it to the calling assistant. Never overwrites an existing package. Add scripts and assets with skill_write_file afterwards. Instructions are data, not executed code.",
            mapOf("name" to field("Unique name: 1–40 lowercase letters, digits, underscores or hyphens"), "description" to field("Brief purpose"), "instructions" to field("Markdown instructions without frontmatter")),
            listOf("name", "description", "instructions"), true) { args ->
            val name = args.string("name")
            val description = args.string("description")
            require(description.isNotBlank() && description.length <= 2048)
            val content = "---\nname: ${JsonPrimitive(name)}\ndescription: ${JsonPrimitive(description)}\n---\n${args.string("instructions")}\n"
            require(content.toByteArray().size <= SkillWorkspace.MAX_EDIT_BYTES) { "Skill instructions exceed 256 KiB" }
            access.create(name, mapOf("SKILL.md" to content.toByteArray()))
            snapshotJson(name, access.workspace(name).snapshot())
        },
        tool("skill_write_file", "Create or replace one skill resource atomically. UTF-8 text is limited to 256 KiB; base64 binary to 64 KiB. Existing files require sha256 from skill_read_file. SKILL.md must retain the skill name and description. Does not execute scripts or automatically sync Termux.",
            mapOf("name" to nameField, "path" to field("Relative destination path"), "revision" to revisionField,
                "content" to field("Complete UTF-8 text or standard base64"), "encoding" to field("utf8 (default) or base64"),
                "create" to field("true for a new file; false requires sha256", "boolean"), "sha256" to field("Original file hash when replacing")),
            listOf("name", "path", "revision", "content", "create"), true) { args -> mutate(args) { workspace, revision ->
                val create = args["create"]?.jsonPrimitive?.booleanOrNull ?: error("create must be boolean")
                val content = args.string("content")
                val bytes = when (args["encoding"]?.jsonPrimitive?.content ?: "utf8") {
                    "utf8" -> content.toByteArray().also { require(it.size <= SkillWorkspace.MAX_EDIT_BYTES) { "Text exceeds 256 KiB" } }
                    "base64" -> {
                        require(content.length <= 88_000) { "Binary exceeds 64 KiB" }
                        Base64.getDecoder().decode(content).also { require(it.size <= SkillWorkspace.MAX_HEX_EDIT_BYTES) { "Binary exceeds 64 KiB" } }
                    }
                    else -> error("Use utf8 or base64 encoding")
                }
                workspace.write(args.string("path"), bytes, revision, if (create) null else args.string("sha256"), create)
            } },
        tool("skill_edit_file", "Replace one unique exact text match in a connected skill file. Requires the current package revision and SHA-256. Ambiguous matches and external edits are rejected. Preserves the rest of the file, including line endings.",
            mapOf("name" to nameField, "path" to field("Relative text file path"), "revision" to revisionField, "sha256" to field("Original file hash"),
                "old_text" to field("Nonempty exact text, must occur once"), "new_text" to field("Replacement text; may be empty")),
            listOf("name", "path", "revision", "sha256", "old_text", "new_text"), true) { args -> mutate(args) { workspace, revision ->
                val doc = workspace.open(args.string("path"))
                if (doc.hash != args.string("sha256")) throw SkillConflict()
                require(doc.bytes.size <= SkillWorkspace.MAX_EDIT_BYTES) { "Text exceeds 256 KiB" }
                val text = requireNotNull(doc.text) { "This file is binary; use base64 write" }
                val old = args.string("old_text")
                require(old.isNotEmpty()) { "old_text must not be empty" }
                val index = text.indexOf(old)
                require(index >= 0 && text.indexOf(old, index + 1) < 0) { "old_text must match exactly once" }
                val bytes = text.replaceRange(index, index + old.length, args.string("new_text")).toByteArray()
                require(bytes.size <= SkillWorkspace.MAX_EDIT_BYTES)
                workspace.write(doc.path, bytes, revision, doc.hash)
            } },
        tool("skill_manage_files", "Manage a skill package with revision protection. mkdir creates a directory; move/copy use full destination paths; delete removes a file or folder recursively; restore swaps in the previous package version. SKILL.md cannot be moved or deleted. No scripts are executed.",
            mapOf("name" to nameField, "revision" to revisionField, "action" to field("mkdir, move, copy, delete or restore"),
                "path" to field("Source path, or new directory for mkdir"), "destination" to field("New relative path for move/copy")),
            listOf("name", "revision", "action"), true) { args -> mutate(args) { workspace, revision ->
                when (args.string("action")) {
                    "mkdir" -> workspace.createDirectory(args.string("path"), revision)
                    "move" -> workspace.rename(args.string("path"), args.string("destination"), revision)
                    "copy" -> workspace.rename(args.string("path"), args.string("destination"), revision, copy = true)
                    "delete" -> workspace.delete(setOf(args.string("path")), revision)
                    "restore" -> workspace.restore(revision)
                    else -> error("Unknown action")
                }
            } },
        tool("skill_delete", "Delete an entire connected skill package from the application and disconnect it from every assistant. Requires the current revision. This cannot be undone with skill_manage_files restore; export a backup in the skill workspace first if needed.",
            mapOf("name" to nameField, "revision" to revisionField), listOf("name", "revision"), true) { args ->
            val (name, _) = resolve(args)
            check(access.delete(name, args.string("revision"))) { "Could not delete skill" }
            buildJsonObject { put("ok", true); put("deleted", name) }
        },
    )
}

private fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?: throw IllegalArgumentException("$key must be a string")

private fun snapshotJson(name: String, snapshot: SkillSnapshot): JsonObject = buildJsonObject {
    put("ok", true); put("name", name); put("revision", snapshot.revision); put("can_restore", snapshot.canRestore)
    put("file_count", snapshot.files); put("size_bytes", snapshot.bytes)
    put("entries", buildJsonArray { snapshot.entries.forEach { entry -> add(buildJsonObject {
        put("path", entry.path); put("directory", entry.directory); put("size_bytes", entry.size); put("executable", entry.executable)
    }) } })
}
