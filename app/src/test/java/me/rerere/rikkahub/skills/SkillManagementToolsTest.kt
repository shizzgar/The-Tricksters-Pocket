package me.rerere.rikkahub.skills

import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillMetadata
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillManagementToolsTest {
    @get:Rule val temp = TemporaryFolder()
    private val enabled = mutableSetOf<String>()
    private var allowed = true
    private val skills by lazy { temp.newFolder("skills") }
    private val state by lazy { temp.newFolder("state") }
    private val access = object : SkillManagementAccess {
        override fun allowed(tool: String) = allowed && enabled.isNotEmpty()
        override fun skills() = skills.listFiles().orEmpty().filter { it.name in enabled }.map {
            SkillMetadata(it.name, "fixture", skillDir = it)
        }
        override fun workspace(name: String) = SkillWorkspace(File(skills, name), state, name)
        override suspend fun create(name: String, files: Map<String, ByteArray>) {
            SkillWorkspace.create(File(skills, name), state, name, files)
            enabled += name
        }
        override suspend fun delete(name: String, revision: String): Boolean {
            if (workspace(name).snapshot().revision != revision) throw SkillConflict()
            enabled -= name
            return File(skills, name).deleteRecursively()
        }
        override fun changed(name: String) = Unit
    }
    private val tools by lazy { createSkillManagementTools(access).associateBy { it.name } }
    private fun seed() {
        runBlocking { access.create("sample", mapOf("SKILL.md" to "---\nname: sample\ndescription: Test\n---\nDo work\n".toByteArray())) }
    }
    private fun call(tool: String, vararg args: Pair<String, Any>): JsonObject = runBlocking {
        val input = buildJsonObject { args.forEach { (key, value) -> when (value) {
            is Boolean -> put(key, value); is Int -> put(key, value); else -> put(key, value.toString())
        } } }
        Json.parseToJsonElement((tools.getValue(tool).execute(input).single() as UIMessagePart.Text).text).jsonObject
    }
    private fun revision(name: String = "sample") = access.workspace(name).snapshot().revision
    private fun assertOk(result: JsonObject) = assertEquals(result.toString(), JsonPrimitive(true), result["ok"])

    @Test fun `create preserves quoted metadata and never replaces a package`() {
        seed()
        assertOk(call("skill_create", "name" to "new-skill", "description" to "Quoted: \"purpose\"", "instructions" to "Run [script](scripts/main.py)"))
        assertTrue("new-skill" in enabled)
        assertEquals("Quoted: \"purpose\"", SkillFrontmatterParser.parse(File(skills, "new-skill/SKILL.md").readText())["description"])
        assertEquals(JsonPrimitive(false), call("skill_create", "name" to "new-skill", "description" to "x", "instructions" to "bad")["ok"])
        assertTrue(File(skills, "new-skill/SKILL.md").readText().contains("scripts/main.py"))
    }
    @Test fun `binary write and read preserve bytes and refuse missing replacement hash`() {
        seed()
        val bytes = byteArrayOf(0, -1, -128, 10, 13)
        assertOk(call("skill_write_file", "name" to "sample", "path" to "assets/data.bin", "revision" to revision(), "create" to true,
            "encoding" to "base64", "content" to Base64.getEncoder().encodeToString(bytes)))
        val result = call("skill_read_file", "name" to "sample", "path" to "assets/data.bin")
        assertEquals(JsonPrimitive("base64"), result["encoding"])
        assertArrayEquals(bytes, Base64.getDecoder().decode(result.getValue("content").jsonPrimitive.content))
        assertEquals(JsonPrimitive(false), call("skill_write_file", "name" to "sample", "path" to "assets/data.bin", "revision" to revision(), "create" to false, "content" to "wrong")["ok"])
        assertArrayEquals(bytes, File(skills, "sample/assets/data.bin").readBytes())
    }
    @Test fun `exact edit rejects ambiguous matches and stale revisions without overwriting`() {
        seed()
        assertOk(call("skill_write_file", "name" to "sample", "path" to "main.py", "revision" to revision(), "create" to true, "content" to "a\r\na\r\n"))
        var read = call("skill_read_file", "name" to "sample", "path" to "main.py")
        val hash = read.getValue("sha256").jsonPrimitive.content
        val oldRevision = revision()
        assertEquals(JsonPrimitive(false), call("skill_edit_file", "name" to "sample", "path" to "main.py", "revision" to oldRevision, "sha256" to hash, "old_text" to "a", "new_text" to "b")["ok"])
        assertOk(call("skill_edit_file", "name" to "sample", "path" to "main.py", "revision" to oldRevision, "sha256" to hash, "old_text" to "a\r\na", "new_text" to "b\r\na"))
        read = call("skill_read_file", "name" to "sample", "path" to "main.py")
        assertEquals(JsonPrimitive("b\r\na\r\n"), read["content"])
        assertEquals(JsonPrimitive("revision_conflict"), call("skill_write_file", "name" to "sample", "path" to "other", "revision" to oldRevision, "create" to true, "content" to "bad")["error"])
        assertFalse(File(skills, "sample/other").exists())
    }
    @Test fun `file management is staged and previous version can be restored`() {
        seed()
        assertOk(call("skill_manage_files", "name" to "sample", "revision" to revision(), "action" to "mkdir", "path" to "scripts"))
        assertOk(call("skill_manage_files", "name" to "sample", "revision" to revision(), "action" to "copy", "path" to "SKILL.md", "destination" to "scripts/help.md"))
        assertOk(call("skill_manage_files", "name" to "sample", "revision" to revision(), "action" to "move", "path" to "scripts/help.md", "destination" to "scripts/guide.md"))
        assertOk(call("skill_manage_files", "name" to "sample", "revision" to revision(), "action" to "delete", "path" to "scripts"))
        assertOk(call("skill_manage_files", "name" to "sample", "revision" to revision(), "action" to "restore"))
        assertTrue(File(skills, "sample/scripts/guide.md").isFile)
    }
    @Test fun `paths and manifest are protected and disabled skills cannot be edited`() {
        seed()
        for (path in listOf("../escape", "/outside", ".user-edited")) {
            assertEquals(JsonPrimitive(false), call("skill_write_file", "name" to "sample", "path" to path, "revision" to revision(), "create" to true, "content" to "bad")["ok"])
        }
        assertEquals(JsonPrimitive(false), call("skill_manage_files", "name" to "sample", "revision" to revision(), "action" to "delete", "path" to "SKILL.md")["ok"])
        val before = revision()
        val hash = call("skill_read_file", "name" to "sample", "path" to "SKILL.md").getValue("sha256").jsonPrimitive.content
        assertEquals(JsonPrimitive(false), call("skill_write_file", "name" to "sample", "path" to "SKILL.md", "revision" to before, "sha256" to hash, "create" to false, "content" to "invalid manifest")["ok"])
        assertEquals(before, revision())
        allowed = false
        assertEquals(JsonPrimitive(false), call("skill_delete", "name" to "sample", "revision" to revision())["ok"])
        allowed = true
        enabled.clear()
        assertEquals(JsonPrimitive(false), call("skill_list_files", "name" to "sample")["ok"])
        assertTrue(File(skills, "sample/SKILL.md").isFile)
    }
    @Test fun `read paginates bytes and binary limits are enforced`() {
        seed()
        val content = "a".repeat(20_000)
        assertOk(call("skill_write_file", "name" to "sample", "path" to "large.txt", "revision" to revision(), "create" to true, "content" to content))
        val first = call("skill_read_file", "name" to "sample", "path" to "large.txt")
        val second = call("skill_read_file", "name" to "sample", "path" to "large.txt", "offset" to first.getValue("next_offset").jsonPrimitive.int)
        assertEquals(content, first.getValue("content").jsonPrimitive.content + second.getValue("content").jsonPrimitive.content)
        assertFalse(second.containsKey("next_offset"))
        assertEquals(JsonPrimitive(false), call("skill_write_file", "name" to "sample", "path" to "large.bin", "revision" to revision(), "create" to true, "encoding" to "base64", "content" to Base64.getEncoder().encodeToString(ByteArray(65537)))["ok"])
    }
    @Test fun `whole package deletion requires current revision`() {
        seed()
        assertEquals(JsonPrimitive("revision_conflict"), call("skill_delete", "name" to "sample", "revision" to "stale")["error"])
        assertOk(call("skill_delete", "name" to "sample", "revision" to revision()))
        assertFalse(File(skills, "sample").exists())
        assertFalse("sample" in enabled)
    }
}
