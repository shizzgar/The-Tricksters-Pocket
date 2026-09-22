package me.rerere.rikkahub.skills

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillWorkspaceTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture(): Triple<File, File, SkillWorkspace> {
        val skills = temp.newFolder(); val root = File(skills, "reports").apply { mkdirs() }
        val state = temp.newFolder()
        root.resolve("SKILL.md").writeText("---\nname: reports\ndescription: Makes reports\n---\n# Reports\n")
        root.resolve("scripts/report.py").apply { parentFile.mkdirs(); writeText("print('old')\n") }
        root.resolve("assets/данные.bin").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(0, -1, -128, 4, 10)) }
        return Triple(root, state, SkillWorkspace(root, state))
    }
    @Test fun `editing one script preserves binary resources and records previous version`() {
        val (root, _, workspace) = fixture()
        val doc = workspace.open("scripts/report.py"); val old = workspace.snapshot()
        val next = workspace.write(doc.path, "print('new')\n".toByteArray(), old.revision, doc.hash)
        assertNotEquals(old.revision, next.revision)
        assertTrue(next.canRestore)
        assertArrayEquals(byteArrayOf(0,-1,-128,4,10), root.resolve("assets/данные.bin").readBytes())
        assertTrue(root.resolve(".user-edited").isFile)
        workspace.restore(next.revision)
        assertEquals("print('old')\n", workspace.open(doc.path).text)
    }
    @Test fun `folder rename duplicate move and multi delete are reversible`() {
        val (root, _, w) = fixture()
        w.rename("scripts", "code", w.snapshot().revision)
        w.rename("code/report.py", "code/copy.py", w.snapshot().revision, copy = true)
        w.createDirectory("archive", w.snapshot().revision)
        w.move(setOf("code"), "archive", w.snapshot().revision)
        assertTrue(root.resolve("archive/code/copy.py").isFile)
        val previous = w.snapshot()
        w.delete(setOf("archive", "archive/code/report.py", "assets/данные.bin"), previous.revision)
        assertFalse(root.resolve("archive").exists())
        w.restore(w.snapshot().revision)
        assertTrue(root.resolve("archive/code/report.py").isFile)
        assertArrayEquals(byteArrayOf(0,-1,-128,4,10), w.open("assets/данные.bin").bytes)
    }
    @Test fun `import collision and invalid instructions roll back entire operation`() {
        val (root, _, w) = fixture(); val before = w.snapshot()
        assertTrue(runCatching { w.importFiles(linkedMapOf("new.txt" to byteArrayOf(1), "SKILL.md" to byteArrayOf(2)), before.revision) }.isFailure)
        assertFalse(root.resolve("new.txt").exists())
        assertTrue(runCatching { w.write("SKILL.md", "---\nname: other\ndescription: changed\n---".toByteArray(), before.revision) }.isFailure)
        assertEquals(before.revision, w.snapshot().revision)
        assertFalse(w.snapshot().canRestore)
    }
    @Test fun `external file edit rejects stale save without destroying draft or newer bytes`() {
        val (root, _, w) = fixture(); val before = w.snapshot(); val doc = w.open("scripts/report.py")
        root.resolve(doc.path).writeText("print('bot')\n")
        assertTrue(runCatching { w.write(doc.path, "draft".toByteArray(), before.revision, doc.hash) }.exceptionOrNull() is SkillConflict)
        assertTrue(runCatching { w.write(doc.path, "draft".toByteArray(), w.snapshot().revision, doc.hash) }.exceptionOrNull() is SkillConflict)
        assertEquals("print('bot')\n", w.open(doc.path).text)
    }
    @Test fun `text detection refuses corrupt utf8 and binary while preserving line endings`() {
        assertNull(SkillWorkspace.decodeText(byteArrayOf(-1, -2)))
        assertNull(SkillWorkspace.decodeText(byteArrayOf(65, 0, 66)))
        val text = "\uFEFFпривет\r\nsecond\tline\r\n"
        assertEquals(text, SkillWorkspace.decodeText(text.toByteArray()))
        val (_, _, w) = fixture()
        w.write("notes.txt", text.toByteArray(), w.snapshot().revision, create = true)
        assertArrayEquals(text.toByteArray(), w.open("notes.txt").bytes)
    }
    @Test fun `hex editor roundtrips every byte and refuses partial or invalid values`() {
        val bytes = ByteArray(256) { it.toByte() }
        assertArrayEquals(bytes, SkillWorkspace.decodeHex(SkillWorkspace.encodeHex(bytes)))
        listOf("0", "GG", "00 01 X2", "0xFF").forEach { assertTrue(runCatching { SkillWorkspace.decodeHex(it) }.isFailure) }
    }
    @Test fun `root instructions reserved paths traversal and symlinks cannot be mutated`() {
        val (root, _, w) = fixture()
        listOf("../escape", "/absolute", "a/../../escape", "a\\b", ".user-edited", ".", "a//b").forEach { path ->
            assertTrue(path, runCatching { w.write(path, byteArrayOf(1), w.snapshot().revision, create = true) }.isFailure)
        }
        assertTrue(runCatching { w.delete(setOf("SKILL.md"), w.snapshot().revision) }.isFailure)
        assertTrue(runCatching { w.rename("SKILL.md", "other.md", w.snapshot().revision) }.isFailure)
        val outside = temp.newFile().apply { writeText("secret") }
        Files.createSymbolicLink(root.resolve("linked").toPath(), outside.toPath())
        assertTrue(runCatching { w.open("linked") }.isFailure)
        assertTrue(runCatching { w.snapshot() }.isFailure)
        assertEquals("secret", outside.readText())
    }
    @Test fun `oversized or too many imported files never partially publish`() {
        val (root, _, w) = fixture(); val before = w.snapshot()
        val files = (1..201).associate { "new/file-$it" to byteArrayOf(1) }
        assertTrue(runCatching { w.importFiles(files, before.revision) }.isFailure)
        assertFalse(root.resolve("new").exists())
        assertEquals(before.revision, w.snapshot().revision)
    }
    @Test fun `move into descendant is rejected without changing the source`() {
        val (_, _, w) = fixture(); val before = w.snapshot()
        assertTrue(runCatching { w.rename("scripts", "scripts/sub", before.revision) }.isFailure)
        assertEquals(before.revision, w.snapshot().revision)
    }
    @Test fun `startup restores package interrupted between renames`() {
        val (root, state, _) = fixture()
        val work = File(state, root.name).apply { mkdirs() }
        assertTrue(root.renameTo(File(work, "pending")))
        SkillWorkspace.recoverAll(root.parentFile, state)
        assertTrue(root.resolve("SKILL.md").isFile)
        assertEquals("print('old')\n", SkillWorkspace(root, state).open("scripts/report.py").text)
    }
    @Test fun `published transaction interrupted before backup cleanup keeps new package and recoverable old one`() {
        val (root, state, _) = fixture()
        val work = File(state, root.name).apply { mkdirs() }
        root.copyRecursively(File(work, "pending"))
        root.resolve("scripts/report.py").writeText("new")
        SkillWorkspace.recoverAll(root.parentFile, state)
        val w = SkillWorkspace(root, state)
        assertEquals("new", w.open("scripts/report.py").text)
        assertTrue(w.snapshot().canRestore)
        w.restore(w.snapshot().revision)
        assertEquals("print('old')\n", w.open("scripts/report.py").text)
    }
    @Test fun `export includes edited bytes but no workbench state or ownership markers`() {
        val (root, _, w) = fixture()
        root.resolve(".core-bundled-hash").writeText("bundled")
        w.write("assets/data.bin", byteArrayOf(0, 1, -1), w.snapshot().revision, create = true)
        val zip = temp.newFile()
        w.export(zip)
        ZipFile(zip).use { z ->
            assertNull(z.getEntry(".user-edited")); assertNull(z.getEntry(".core-bundled-hash")); assertNull(z.getEntry("draft.json"))
            assertArrayEquals(byteArrayOf(0,1,-1), z.getInputStream(z.getEntry("assets/data.bin")).readBytes())
            assertNotNull(z.getEntry("SKILL.md"))
        }
    }
}
