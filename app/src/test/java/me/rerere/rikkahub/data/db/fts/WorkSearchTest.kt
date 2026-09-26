package me.rerere.rikkahub.data.db.fts

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class WorkSearchTest {
    @Test fun `tool output and documents remain separately searchable`() {
        val parts = extractWorkSearchText(listOf(UIMessagePart.Text("explanation"), UIMessagePart.Tool("call", "workspace_shell", "pwd", listOf(UIMessagePart.Text("/project/result"), UIMessagePart.Document("file:///upload/a.txt", "a.txt")))))
        assertEquals(listOf(WorkSearchKind.TEXT, WorkSearchKind.TOOL, WorkSearchKind.FILE), parts.map { it.kind })
        assertTrue(parts[1].text.contains("/project/result"))
        assertEquals("workspace_shell", parts[1].toolName)
    }
    @Test fun `binary base64 is excluded from the index`() {
        val rows = extractWorkSearchText(listOf(UIMessagePart.Image("data:image/png;base64,secret")))
        assertEquals("image", rows.single().text)
    }
    @Test fun `project subtree date and tool filters use bound parameters`() {
        val sql = workSearchSql(WorkSearchFilter(WorkSearchKind.TOOL, setOf("root' OR 1=1"), true, 10, 20, "shell'"), null)
        assertTrue(sql.where.contains("WITH RECURSIVE"))
        assertFalse(sql.where.contains("root'"))
        assertEquals(listOf("root' OR 1=1", "TOOL", 10L, 20L, "shell'"), sql.args)
    }
    @Test fun `empty project never expands to all conversations`() {
        assertEquals("0", workSearchSql(WorkSearchFilter(conversationIds = emptySet()), null).where)
    }
    @Test fun `long evidence is retained before chunking`() {
        val text = "a".repeat(25000) + "tail-evidence"
        assertTrue(extractWorkSearchText(listOf(UIMessagePart.Text(text))).single().text.endsWith("tail-evidence"))
    }
}
