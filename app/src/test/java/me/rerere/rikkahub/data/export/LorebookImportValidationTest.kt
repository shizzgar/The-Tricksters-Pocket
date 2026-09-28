package me.rerere.rikkahub.data.export

import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.*
import org.junit.Test

class LorebookImportValidationTest {
    @Test fun `native import normalizes legacy negative depth and preserves valid entries`() {
        val book = Lorebook(name = "legacy", entries = listOf(
            PromptInjection.RegexInjection(scanDepth = -10),
            PromptInjection.RegexInjection(scanDepth = 7),
        ))
        val imported = LorebookSerializer.tryImportNative(LorebookSerializer.exportToJson(book))!!
        assertEquals(listOf(0, 7), imported.entries.map { it.scanDepth })
        assertNotEquals(book.id, imported.id)
        assertNotEquals(book.entries.first().id, imported.entries.first().id)
    }

    @Test fun `SillyTavern import also normalizes depth`() {
        val imported = LorebookSerializer.tryImportSillyTavern(
            """{"entries":{"0":{"key":["trigger"],"content":"guide","scanDepth":-1}}}""", "legacy",
        )!!
        assertEquals(0, imported.entries.single().scanDepth)
        assertEquals("guide", imported.entries.single().content)
    }
}
