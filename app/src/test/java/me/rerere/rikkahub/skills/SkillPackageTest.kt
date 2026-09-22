package me.rerere.rikkahub.skills

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillPackageTest {
    @get:Rule val folder = TemporaryFolder()
    private fun skill(): File = folder.newFolder().apply { resolve("SKILL.md").writeText("---\nname: fixture\ndescription: test\n---\n# Test") }

    @Test fun `archive preserves binary assets unicode names and nested scripts`() {
        val root = skill()
        val bytes = ByteArray(1024) { it.toByte() }
        root.resolve("assets/данные.bin").apply { parentFile.mkdirs(); writeBytes(bytes) }
        root.resolve("scripts/run.py").apply { parentFile.mkdirs(); writeText("print('hello')") }
        val zip = folder.newFile()
        SkillPackage.archive(root, zip)
        ZipFile(zip).use { archive ->
            assertArrayEquals(bytes, archive.getInputStream(archive.getEntry("assets/данные.bin")).readBytes())
            assertNotNull(archive.getEntry("scripts/run.py"))
        }
    }

    @Test fun `archive is deterministic and changes with any file content`() {
        val root = skill()
        val first = SkillPackage.archive(root, folder.newFile())
        root.resolve("SKILL.md").setLastModified(1)
        assertEquals(first, SkillPackage.archive(root, folder.newFile()))
        root.resolve("SKILL.md").appendText("new")
        assertNotEquals(first, SkillPackage.archive(root, folder.newFile()))
    }

    @Test fun `symlink cannot smuggle app private files`() {
        val root = skill()
        val outside = folder.newFile().apply { writeText("private") }
        Files.createSymbolicLink(root.resolve("secret").toPath(), outside.toPath())
        assertTrue(runCatching { SkillPackage.archive(root, folder.newFile()) }.isFailure)
    }

    @Test fun `file count cap and missing instructions fail before transfer`() {
        val root = skill()
        repeat(200) { root.resolve("file-$it").writeText("") }
        assertTrue(runCatching { SkillPackage.archive(root, folder.newFile()) }.isFailure)
        assertTrue(runCatching { SkillPackage.archive(folder.newFolder(), folder.newFile()) }.isFailure)
    }

    @Test fun `case insensitive imported instructions are normalized without changing bytes`() {
        val root = folder.newFolder()
        root.resolve("skill.md").writeBytes(byteArrayOf(10, 20, -1))
        assertArrayEquals(byteArrayOf(10, 20, -1), SkillPackage.readFiles(root).getValue("SKILL.md"))
    }
}
