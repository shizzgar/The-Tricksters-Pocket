package me.rerere.rikkahub.ui.components.message.tools

import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.ai.CompactionEvidence
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CompactionPresentationTest {
    private fun tool(input: String, output: String? = null) = UIMessagePart.Tool(
        toolCallId = "event", toolName = "context_compaction", input = input,
        output = output?.let { listOf(UIMessagePart.Text(it)) } ?: emptyList(),
    )

    @Test fun `live timer survives reopening and does not depend on wall clock`() {
        val event = tool("""{"state":"running","mode":"manual","runtime_id":"runtime","started_elapsed_ms":1000,"started_at_ms":999999999}""")
        assertEquals(42_000L, compressionDetails(event, "runtime").elapsedMs(43_000))
        assertEquals(72_000L, compressionDetails(event, "runtime").elapsedMs(73_000))
        assertTrue(compressionDetails(event, "runtime").isManual)
    }

    @Test fun `final duration is frozen and summary stays markdown`() {
        val markdown = "# Findings\n\n- **Verified**\n\n```sh\nprintf done\n```"
        val event = tool("""{"state":"completed","elapsed_ms":12345}""", markdown)
        val view = compressionDetails(event, "runtime")
        assertEquals(CompressionState.COMPLETED, view.state)
        assertEquals(12_345L, view.elapsedMs(1_000_000))
        assertEquals(markdown, view.summary)
    }

    @Test fun `app restart cannot leave a live stopwatch for an abandoned operation`() {
        val event = tool("""{"state":"running","runtime_id":"old","started_elapsed_ms":1}""")
        val view = compressionDetails(event, "new")
        assertEquals(CompressionState.INTERRUPTED, view.state)
        assertNull(view.elapsedMs(100_000))
    }

    @Test fun `legacy cards are readable without inventing timing or model metrics`() {
        val view = compressionDetails(tool("""{"mode":"automatic","source_token_estimate":202210}""", "## Old summary"), "runtime")
        assertEquals(CompressionState.COMPLETED, view.state)
        assertNull(view.elapsedMs(100_000))
        assertNull(view.number("summary_token_estimate"))
        assertEquals(202210L, view.number("source_token_estimate"))
    }

    @Test fun `failures cancellation and malformed legacy fields are distinct`() {
        assertEquals(CompressionState.FAILED, compressionDetails(tool("""{"state":"failed","error":"timeout"}"""), "runtime").state)
        assertEquals(CompressionState.CANCELLED, compressionDetails(tool("""{"state":"cancelled"}"""), "runtime").state)
        assertEquals(CompressionState.UNKNOWN, compressionDetails(tool("bad json"), "runtime").state)
        assertNull(compressionDetails(tool("""{"elapsed_ms":-1}"""), "runtime").elapsedMs(1000))
    }

    @Test fun `long operations display hours and missing time stays absent`() {
        assertEquals("0:00", formatCompressionDuration(-10))
        assertEquals("1:05", formatCompressionDuration(65_000))
        assertEquals("1:30:00", formatCompressionDuration(5_400_000))
    }

    private fun evidence(output: String = """{"success":true,"exit_code":3,"stdout":"hello\\nworld","stderr":"failure"}"""): String {
        val call = UIMessagePart.Tool(toolCallId = "call-1", toolName = "termux_run_command",
            input = """{"command":"printf 'hello\\nworld'","working_dir":"/private/case","timeout_seconds":30}""",
            output = listOf(UIMessagePart.Text(output)))
        return CompactionEvidence.index(listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(call))), 4000)
    }

    @Test fun `real generated handoff renders structured sections in order without changing stored text`() {
        val requests = CompactionEvidence.recentUserRequests(listOf(UIMessage.user("First request"), UIMessage.user("Only diagnose now")), 1000)
        val index = evidence()
        val source = "[Summary of previous conversation]\n## Findings\n\n**Kept**\n\n$requests\n\n$index\n\nTail note"
        val view = compressionDetails(tool("""{"state":"completed"}""", source), "runtime")
        val sections = compressionSummarySections(view.summary)
        assertEquals(source, view.summary)
        assertEquals(4, sections.size)
        assertTrue((sections[0] as CompressionSection.Markdown).text.contains("**Kept**"))
        val quotes = (sections[1] as CompressionSection.Requests).entries
        assertEquals(listOf("First request", "Only diagnose now"), quotes.map { it.compressionText("text_excerpt") })
        val tools = sections[2] as CompressionSection.Evidence
        assertEquals(1, tools.recorded)
        assertEquals(0, tools.omitted)
        assertEquals("call-1", tools.entries.single().compressionText("call_id"))
        assertEquals("failure", tools.entries.single().compressionText("stderr_excerpt"))
        assertEquals("Tail note", (sections[3] as CompressionSection.Markdown).text.trim())
    }

    @Test fun `malformed incomplete future or inconsistent blocks fall back without dropping evidence`() {
        val index = evidence()
        val malformed = index.replace("\"call_id\":\"call-1\"", "\"call_id\":")
        val incomplete = index.removeSuffix(CompactionEvidence.FOOTER)
        val future = index.replace("index v2", "index v3")
        val inconsistent = index.replace("indexed: 1", "indexed: 2")
        val overflow = index.replace("Recorded calls: 1", "Recorded calls: 999999999999999999999")
        for (source in listOf(malformed, incomplete, future, inconsistent, overflow)) {
            assertEquals(listOf(CompressionSection.Markdown(source)), compressionSummarySections(source))
        }
    }

    @Test fun `quoted fenced examples stay markdown and real block after fence still renders`() {
        val index = evidence()
        for (fence in listOf("```", "~~~~")) {
            val quoted = "$fence\n$index\n$fence"
            assertEquals(listOf(CompressionSection.Markdown(quoted)), compressionSummarySections(quoted))
            val sections = compressionSummarySections("$quoted\n\n$index")
            assertEquals(2, sections.size)
            assertTrue(sections[0] is CompressionSection.Markdown)
            assertTrue(sections[1] is CompressionSection.Evidence)
        }
    }

    @Test fun `unknown record fields and omission counts remain accessible`() {
        val index = evidence().replace("Recorded calls: 1; indexed: 1; omitted from index: 0.",
            "Recorded calls: 8; indexed: 1; omitted from index: 7.")
            .replace("\"preview_only\":true", "\"preview_only\":true,\"future_field\":{\"important\":\"keep me\"}")
        val parsed = compressionSummarySections(index).single() as CompressionSection.Evidence
        assertEquals(8, parsed.recorded)
        assertEquals(7, parsed.omitted)
        assertTrue(parsed.entries.single()["future_field"].toString().contains("keep me"))
    }

    @Test fun `nonzero exit takes precedence over transport success and unknown is not successful execution`() {
        fun record(source: String) = Json.parseToJsonElement(source).jsonObject
        assertEquals(CompressionEvidenceState.FAILED, compressionEvidenceState(record("""{"success":true,"exit_code":3}""")))
        assertEquals(CompressionEvidenceState.TIMEOUT, compressionEvidenceState(record("""{"success":true,"timed_out":true}""")))
        assertEquals(CompressionEvidenceState.RECORDED, compressionEvidenceState(record("""{"success":true}""")))
        assertEquals(CompressionEvidenceState.RECORDED, compressionEvidenceState(record("""{"error":null,"exit_code":0}""")))
        assertEquals(CompressionEvidenceState.RECORDED, compressionEvidenceState(record("""{"state":"running"}""")))
    }

    @Test fun `complete terminal arguments decode without changing command whitespace`() {
        val command = "printf '%s\\n' 'some text'\n  echo done"
        val record = buildJsonObject {
            put("tool", "termux_run_command")
            put("input_excerpt", buildJsonObject { put("command", command); put("timeout_seconds", 30) }.toString())
        }
        assertEquals(command, compressionEvidenceCommand(record))
        assertEquals(30, compressionEvidenceInput(record)!!["timeout_seconds"]!!.jsonPrimitive.int)
        val broken = JsonObject(record + ("input_excerpt" to JsonPrimitive("{\"command\":\"start [excerpt omitted; retrieve original] end")))
        assertNull(compressionEvidenceCommand(broken))
        assertNull(compressionEvidenceInput(broken))
    }

    @Test fun `literal argv display quotes arguments and nonterminal fields never become shell commands`() {
        val input = """{"executable":"/bin/python","arguments":["script with spaces.py","a'b"]}"""
        val terminal = buildJsonObject { put("tool", "termux_run_command"); put("input_excerpt", input) }
        assertEquals("/bin/python 'script with spaces.py' 'a'\"'\"'b'", compressionEvidenceCommand(terminal))
        val web = buildJsonObject { put("tool", "search_web"); put("input_excerpt", """{"input":"hello"}""") }
        assertNull(compressionEvidenceCommand(web))
    }

    @Test fun `windows line endings and marker text inside quoted JSON do not break section boundaries`() {
        val requestText = "Пример 😀\n[End recent user requests]\nДальше"
        val requests = CompactionEvidence.recentUserRequests(listOf(UIMessage.user(requestText)), 1000)
        val parsed = compressionSummarySections(requests.replace("\n", "\r\n")).single() as CompressionSection.Requests
        assertEquals(requestText, parsed.entries.single().compressionText("text_excerpt"))
    }
}
