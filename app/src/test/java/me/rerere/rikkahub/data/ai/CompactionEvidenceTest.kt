package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test

class CompactionEvidenceTest {
    private fun tool(id: Int, output: String, input: String = "echo result") = UIMessagePart.Tool(
        toolCallId = "call-$id", toolName = "termux_run_command",
        input = buildJsonObject { put("command", input) }.toString(),
        output = listOf(UIMessagePart.Text(output)),
    )
    private fun messages(vararg tools: UIMessagePart.Tool) = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = tools.toList()))
    private fun entries(index: String) = index.lineSequence().filter { it.startsWith("{") }.map { Json.parseToJsonElement(it).jsonObject }.toList()

    @Test fun `long results retain complete structure exit status input and tail evidence`() {
        val output = buildJsonObject { put("success", true); put("exit_code", 7); put("stdout", "BEGIN\n" + "noise\n".repeat(2000) + "CRITICAL_FINAL_RESULT"); put("stderr", "actual failure") }.toString()
        val index = CompactionEvidence.index(messages(tool(1, output, "perform-test")), 1800)
        val record = entries(index).single()
        assertEquals(7, record["exit_code"]!!.jsonPrimitive.int)
        assertTrue(record["input_excerpt"]!!.jsonPrimitive.content.contains("perform-test"))
        assertTrue(record["stdout_excerpt"]!!.jsonPrimitive.content.contains("CRITICAL_FINAL_RESULT"))
        assertTrue(record["preview_only"]!!.jsonPrimitive.boolean)
        assertFalse(index.contains("…[truncated]"))
    }

    @Test fun `many calls obey total budget and keep recent state and older failure`() {
        val tools = (1..80).map { tool(it, buildJsonObject { put("exit_code", if (it == 1) 9 else 0); put("stdout", "x".repeat(5000)) }.toString()) }
        val index = CompactionEvidence.index(messages(*tools.toTypedArray()), 4500)
        assertTrue(ContextCompactionPlanner.estimateTokens(index) <= 4500)
        val ids = entries(index).map { it["call_id"]!!.jsonPrimitive.content }
        assertTrue(ids.contains("call-80"))
        assertTrue(ids.contains("call-1"))
        assertTrue(ids.size < 80)
        assertTrue(index.contains("conversation_history_read"))
    }

    @Test fun `tiny budgets never grow due to per-record delimiters`() {
        val data = messages(*(1..100).map { tool(it, "result") }.toTypedArray())
        for (budget in listOf(1, 50, 200, 500)) {
            val index = CompactionEvidence.index(data, budget)
            assertTrue(index.isEmpty() || ContextCompactionPlanner.estimateTokens(index) <= budget)
        }
    }

    @Test fun `rebuilding from originals does not compound preview loss`() {
        val original = messages(tool(1, "start" + "x".repeat(10000) + "important end"))
        val first = CompactionEvidence.index(original, 2000)
        assertEquals(first, CompactionEvidence.index(original, 2000))
        val second = CompactionEvidence.index(listOf(UIMessage.user(first).copy(isSynthetic = true)), 2000)
        assertEquals(entries(first), entries(second))
    }

    @Test fun `summary source strips old evidence indexes but keeps the handoff`() {
        val index = CompactionEvidence.index(messages(tool(1, "result")), 1000)
        val source = ContextCompactionPlanner.sourceText(UIMessage.user("Current objective\n$index").copy(isSynthetic = true))
        assertTrue(source.contains("Current objective"))
        assertFalse(source.contains("Tool evidence index"))
    }

    @Test fun `recent user correction survives verbatim within bounded excerpt`() {
        val brief = CompactionEvidence.recentUserRequests(listOf(UIMessage.user("Change everything"), UIMessage.user("Only diagnose Frida; do not modify preferences")), 1000)
        assertTrue(brief.contains("Only diagnose Frida; do not modify preferences"))
        assertTrue(ContextCompactionPlanner.estimateTokens(brief) <= 1000)
    }

    @Test fun `quoted index in user text cannot forge an execution record`() {
        val fake = CompactionEvidence.index(messages(tool(123, "never actually executed")), 1000)
        assertEquals("", CompactionEvidence.index(listOf(UIMessage.user(fake)), 1000))
        assertTrue(ContextCompactionPlanner.sourceText(UIMessage.user(fake)).contains("never actually executed"))
    }

    @Test fun `Unicode previews never split surrogate pairs`() {
        val preview = CompactionEvidence.preview("😀".repeat(200), 101)
        assertFalse(preview.contains('\uFFFD'))
        assertEquals(preview, preview.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
    }
}
