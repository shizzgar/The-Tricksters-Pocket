package me.rerere.rikkahub.ui.components.message.tools

import me.rerere.ai.ui.UIMessagePart
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
}
