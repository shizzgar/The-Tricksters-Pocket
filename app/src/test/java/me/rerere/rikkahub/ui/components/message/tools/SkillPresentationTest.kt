package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SkillPresentationTest {
    private val args = buildJsonObject { put("name", "demo") }
    @Test fun markdownAndFailedSyncRemainSeparate() {
        val view = presentSkill("use_skill", args, listOf("# Guide\nDo this", "Termux skill package: {\"success\":false,\"error\":\"offline\",\"recovery\":\"Reconnect Termux\"}"))
        assertEquals(SkillOperationStatus.PARTIAL, view.status)
        assertEquals("markdown", view.documents.single().language)
        assertTrue(view.errors.single().contains("Reconnect Termux"))
    }
    @Test fun scriptEnvelopeIsDecodedExactlyOnce() {
        val text = "print(\"hello\")\n"
        val out = buildJsonObject { put("ok", true); put("path", "scripts/run.py"); put("encoding", "utf8"); put("content", text) }
        val view = presentSkill("skill_read_file", args, listOf(out.toString()))
        assertEquals(text, view.documents.single().text)
        assertEquals("python", view.documents.single().language)
        assertFalse(view.metadata.containsKey("content"))
    }
    @Test fun binaryIsNeverRenderedAsMarkdown() {
        val view = presentSkill("skill_read_file", args, listOf("{\"ok\":true,\"encoding\":\"base64\",\"content\":\"YWJj\"}"))
        assertTrue(view.binary); assertTrue(view.documents.isEmpty())
    }
    @Test fun malformedAndNonObjectResultsDoNotCrash() {
        listOf("{broken", "[]", "null", "42").forEach { text ->
            assertEquals(text, presentSkill("use_skill", args, listOf(text)).documents.single().text)
        }
    }
    @Test fun approvalAndDenialAreNotReportedAsExecution() {
        assertEquals(SkillOperationStatus.APPROVAL, presentSkill("use_skill", args, emptyList(), pending = true).status)
        assertEquals(SkillOperationStatus.DENIED, presentSkill("use_skill", args, emptyList(), denied = true).status)
        assertEquals(SkillOperationStatus.RUNNING, presentSkill("use_skill", args, emptyList(), loading = true, started = true).status)
    }
    @Test fun encodedMarkdownAndSnapshotMetadataAreReadable() {
        val view = presentSkill("skill_get_content", args, listOf("{\"content_md\":\"# Heading\",\"revision\":\"abc\"}"))
        assertEquals("# Heading", view.documents.single().text)
        assertEquals("abc", view.metadata["revision"]?.jsonPrimitive?.content)
    }
}
