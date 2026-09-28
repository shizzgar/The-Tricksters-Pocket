package me.rerere.rikkahub.skills.js

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class SkillViewerPathsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root by lazy { temporary.newFolder("skill") }
    private val script by lazy { File(root, "scripts/index.html") }

    @Test fun `relative assets and legacy local URLs share one confined HTTPS origin`() {
        val target = File(root, "assets/my page.html")
        val expected = "https://${JsSkillRunner.skillOriginHost(root.name)}/skill/assets/my%20page.html?x=1#section"
        assertEquals(expected, resolveSkillWebviewUrl("../assets/my%20page.html?x=1#section", script, root))
        assertEquals(expected, resolveSkillWebviewUrl(target.toURI().toASCIIString() + "?x=1#section", script, root))
    }

    @Test fun `traversal absolute paths symlinks and unsupported schemes cannot escape`() {
        val secret = temporary.newFile("secret.txt")
        for (url in listOf("../../secret.txt", "%2e%2e/%2e%2e/secret.txt", secret.toURI().toString(), secret.path,
            "content://private/file", "javascript:alert(1)", "file://host/path", "//host/path")) {
            assertNull("Must reject $url", resolveSkillWebviewUrl(url, script, root))
        }
        Files.createSymbolicLink(File(root, "escape").toPath(), secret.toPath())
        assertNull(resolveSkillWebviewUrl("../escape", script, root))
    }

    @Test fun `ordinary remote skill output stays remote`() {
        assertEquals("https://example.org/view", resolveSkillWebviewUrl("https://example.org/view", script, root))
    }
}
