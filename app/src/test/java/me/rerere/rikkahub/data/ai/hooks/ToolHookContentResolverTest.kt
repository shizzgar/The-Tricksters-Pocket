package me.rerere.rikkahub.data.ai.hooks

import java.io.IOException
import java.util.concurrent.CancellationException
import me.rerere.rikkahub.data.model.TOOL_HOOK_MAX_PROMPT_CHARS
import me.rerere.rikkahub.data.model.ToolHookAction
import me.rerere.rikkahub.data.model.ToolHookActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolHookContentResolverTest {
    private fun skillAction(path: String = "SKILL.md", section: String = "") = ToolHookAction(
        type = ToolHookActionType.SKILL,
        skillName = "jadx-guide",
        skillPath = path,
        skillSection = section,
    )

    private fun resolver(content: String? = "# Usage\nRead the help first.") = ToolHookContentResolver(
        installedSkills = { setOf("jadx-guide") },
        readSkillFile = { _, _ -> content },
    )

    private fun assertError(expected: HookContentError, actual: HookContentResolution) {
        assertTrue("Expected a visible error, got $actual", actual is HookContentResolution.Error)
        actual as HookContentResolution.Error
        assertEquals(expected, actual.code)
        assertTrue(actual.reason.isNotBlank())
    }

    @Test
    fun `inline instructions are literal and do not access skills`() {
        val resolver = ToolHookContentResolver(
            installedSkills = { error("Inline hooks must not list skills") },
            readSkillFile = { _, _ -> error("Inline hooks must not read skills") },
        )
        val text = "Use `jadx --help`.\nDo not substitute {{stderr}} or execute shell examples."
        assertEquals(
            HookContentResolution.Resolved(text),
            resolver.resolve(ToolHookAction(prompt = " \n$text\n ")),
        )
    }

    @Test
    fun `blank instructions are rejected`() {
        assertError(HookContentError.EMPTY_CONTENT, resolver().resolve(ToolHookAction(prompt = " \n\t")))
        assertError(HookContentError.EMPTY_CONTENT, resolver("---\nname: guide\n---\n").resolve(skillAction()))
    }

    @Test
    fun `limit permits complete instructions but never silently truncates`() {
        val maximum = "x".repeat(TOOL_HOOK_MAX_PROMPT_CHARS)
        assertEquals(HookContentResolution.Resolved(maximum), resolver().resolve(ToolHookAction(prompt = maximum)))
        assertError(HookContentError.CONTENT_TOO_LARGE, resolver().resolve(ToolHookAction(prompt = maximum + "x")))
        assertError(HookContentError.CONTENT_TOO_LARGE, resolver(maximum + "x").resolve(skillAction()))
    }

    @Test
    fun `unsafe paths are rejected before any filesystem access`() {
        val resolver = ToolHookContentResolver(
            installedSkills = { error("Unsafe paths must not access skills") },
            readSkillFile = { _, _ -> error("Unsafe paths must not read files") },
        )
        listOf("", "/tmp/a.md", "../a.md", "docs/../a.md", "./a.md", "docs//a.md", "docs\\a.md", "C:/a.md", "https://host/a.md", "a.md\u0000", "~/a.md").forEach {
            assertError(HookContentError.INVALID_PATH, resolver.resolve(skillAction(path = it)))
        }
    }

    @Test
    fun `invalid skill identity is rejected before access`() {
        val resolver = ToolHookContentResolver(
            installedSkills = { error("Invalid identity must not access skills") },
            readSkillFile = { _, _ -> error("Invalid identity must not read files") },
        )
        listOf("", ".", "..", "../guide", "other/guide", "other\\guide", "guide\nname", " guide ").forEach {
            assertError(HookContentError.INVALID_SKILL, resolver.resolve(skillAction().copy(skillName = it)))
        }
    }

    @Test
    fun `skill must remain installed at resolution time`() {
        var installed = setOf("jadx-guide")
        var reads = 0
        val resolver = ToolHookContentResolver({ installed }, { _, _ -> reads++; "Use jadx --help." })
        assertTrue(resolver.resolve(skillAction()) is HookContentResolution.Resolved)
        installed = emptySet()
        assertError(HookContentError.SKILL_NOT_INSTALLED, resolver.resolve(skillAction()))
        assertEquals(1, reads)
    }

    @Test
    fun `script files cannot be hook instruction sources`() {
        listOf("run.sh", "run.py", "run.js", "image.png", "bundle.zip", "notes", "notes.md.sh").forEach {
            assertError(HookContentError.UNSUPPORTED_FILE_TYPE, resolver().resolve(skillAction(path = it)))
        }
    }

    @Test
    fun `nested text instruction sources preserve origin and exact selection`() {
        val requests = mutableListOf<Pair<String, String>>()
        val resolver = ToolHookContentResolver({ setOf("jadx-guide") }, { name, path ->
            requests += name to path
            "Read jadx --help before selecting flags."
        })
        listOf("docs/guide.md", "docs/guide.MARKDOWN", "docs/guide.txt", "docs/guide.text").forEach { path ->
            assertEquals(
                HookContentResolution.Resolved("Read jadx --help before selecting flags.", "jadx-guide/$path"),
                resolver.resolve(skillAction(path)),
            )
        }
        assertEquals(4, requests.size)
        assertTrue(requests.all { it.first == "jadx-guide" })
    }

    @Test
    fun `skill frontmatter is excluded from injected instructions`() {
        val content = "\uFEFF---\nname: jadx-guide\ndescription: A guide\n---\n# Usage\nRun `jadx --help`.\n"
        assertEquals(
            HookContentResolution.Resolved("# Usage\nRun `jadx --help`.", "jadx-guide/SKILL.md"),
            resolver(content).resolve(skillAction()),
        )
    }

    @Test
    fun `section includes child headings and stops before next peer`() {
        val content = "# Guide\nOverview\n## Errors ##\nRead stderr.\n### Unknown option\nCheck help.\n## Output\nNot included."
        assertEquals(
            HookContentResolution.Resolved("## Errors ##\nRead stderr.\n### Unknown option\nCheck help.", "jadx-guide/SKILL.md#errors"),
            resolver(content).resolve(skillAction(section = "errors")),
        )
    }

    @Test
    fun `fenced example headings neither match nor end a section`() {
        val content = "# Guide\n## Errors\n````md\n## Example\n```\n# Still in code\n````\n~~~text\n## Another example\n~~~\nFinal instruction.\n## Output\nNot included."
        val result = resolver(content).resolve(skillAction(section = "Errors")) as HookContentResolution.Resolved
        assertTrue(result.text.endsWith("Final instruction."))
        assertTrue(result.text.contains("# Still in code"))
        assertError(HookContentError.SECTION_NOT_FOUND, resolver(content).resolve(skillAction(section = "Example")))
    }

    @Test
    fun `setext headings are selectable and delimit sections`() {
        val content = "Guide\n=====\nOverview.\nErrors\n------\nRead stderr.\n### Child\nRead help.\nOutput\n------\nExcluded."
        assertEquals(
            HookContentResolution.Resolved("Errors\n------\nRead stderr.\n### Child\nRead help.", "jadx-guide/SKILL.md#Errors"),
            resolver(content).resolve(skillAction(section = "Errors")),
        )
    }

    @Test
    fun `missing or repeated headings require an explicit corrected selection`() {
        assertError(HookContentError.SECTION_NOT_FOUND, resolver().resolve(skillAction(section = "Absent")))
        assertError(
            HookContentError.AMBIGUOUS_SECTION,
            resolver("# Errors\nA\n# errors\nB").resolve(skillAction(section = "Errors")),
        )
    }

    @Test
    fun `a small section can be selected from a larger instruction file`() {
        val content = "# Whole guide\n${"x".repeat(TOOL_HOOK_MAX_PROMPT_CHARS + 500)}\n## Errors\nRead help.\n## Other\nExcluded."
        assertEquals(
            HookContentResolution.Resolved("## Errors\nRead help.", "jadx-guide/SKILL.md#Errors"),
            resolver(content).resolve(skillAction(section = "Errors")),
        )
        assertError(HookContentError.CONTENT_TOO_LARGE, resolver(content).resolve(skillAction()))
    }

    @Test
    fun `plain text permits the whole file but does not pretend to contain markdown sections`() {
        val content = "# Not a Markdown heading\nLiteral content."
        assertTrue(resolver(content).resolve(skillAction(path = "notes.txt")) is HookContentResolution.Resolved)
        assertError(HookContentError.SECTION_NOT_FOUND, resolver(content).resolve(skillAction(path = "notes.txt", section = "Not a Markdown heading")))
    }

    @Test
    fun `binary and terminal control sequences are rejected`() {
        listOf("text\u0000binary", "text\u001b[31mred", "text\u007f").forEach {
            assertError(HookContentError.INVALID_TEXT, resolver(it).resolve(skillAction()))
            assertError(HookContentError.INVALID_TEXT, resolver().resolve(ToolHookAction(prompt = it)))
        }
        assertTrue(resolver("Русская инструкция\n中文说明\t✅").resolve(skillAction()) is HookContentResolution.Resolved)
    }

    @Test
    fun `missing and unreadable files produce visible reasons`() {
        assertError(HookContentError.FILE_NOT_FOUND, resolver(null).resolve(skillAction()))
        val inaccessible = ToolHookContentResolver({ setOf("jadx-guide") }, { _, _ -> throw IOException("private filesystem path") })
        val result = inaccessible.resolve(skillAction())
        assertError(HookContentError.READ_FAILED, result)
        assertTrue(!(result as HookContentResolution.Error).reason.contains("private filesystem path"))
    }

    @Test(expected = CancellationException::class)
    fun `cancelled resolution does not become a hook failure`() {
        ToolHookContentResolver({ setOf("jadx-guide") }, { _, _ -> throw CancellationException() }).resolve(skillAction())
    }
}
