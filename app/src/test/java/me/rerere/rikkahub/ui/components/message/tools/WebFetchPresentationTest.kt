package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WebFetchPresentationTest {
    private fun view(result: String?, tool: String = "web_fetch") = presentWebFetch(tool,
        Json.parseToJsonElement("""{"url":"https://example.com/start"}"""), result?.let(Json::parseToJsonElement),
        loading = false, started = true, hasResult = result != null)

    @Test fun `HTTP errors override an inconsistent ok flag and empty extraction is not success`() {
        assertEquals(WebFetchState.HTTP_ERROR, view("""{"status":404,"ok":true,"body":"not found"}""").state)
        assertEquals(WebFetchState.FAILED, view("""{"status":200,"ok":true,"error":"empty_extraction"}""").state)
        assertEquals(WebFetchState.RECEIVED, view("""{"status":204,"body":""}""").state)
        assertEquals(WebFetchState.UNKNOWN, view("[]").state)
    }
    @Test fun `legacy body cap and new continuation metadata stay distinct`() {
        val legacy = view("""{"status":200,"body":"<p>raw</p>","body_truncated":true}""")
        assertTrue(legacy.truncated)
        assertTrue(legacy.sourceTruncated)
        assertNull(legacy.nextIndex)
        val page = view("""{"status":200,"text":"page","truncated":true,"body_truncated":false,"next_start_index":100}""")
        assertTrue(page.truncated)
        assertFalse(page.sourceTruncated)
        assertEquals(100, page.nextIndex)
    }
    @Test fun `redirect source and readable content are preserved without interpreting HTML`() {
        val v = view("""{"status":200,"final_url":"https://example.org/end","text":"<script>bad()</script>"}""", "web_extract")
        assertEquals("https://example.com/start", v.requestedUrl)
        assertEquals("https://example.org/end", v.url)
        assertEquals("article", v.mode)
        assertEquals("<script>bad()</script>", v.body)
        assertNull(webHttpUrl("javascript:alert(1)"))
        assertNull(webHttpUrl("intent://target"))
        assertNotNull(webHttpUrl("https://example.org/a?b=c"))
    }
    @Test fun `pending approval and running are not fabricated completed responses`() {
        val args = JsonObject(emptyMap())
        assertEquals(WebFetchState.RUNNING, presentWebFetch("web_fetch", args, null, true, true, false).state)
        assertEquals(WebFetchState.APPROVAL, presentWebFetch("web_fetch", args, null, false, false, false, pending = true).state)
        assertEquals(WebFetchState.DENIED, presentWebFetch("web_fetch", args, null, false, false, false, denied = true).state)
    }
    @Test fun `JSON formatting preserves values while markup and broken JSON remain literal`() {
        val compact = """{"line":"one\ntwo","n":12}"""
        assertEquals(Json.parseToJsonElement(compact), Json.parseToJsonElement(formatWebFetchBody(compact)))
        assertTrue(formatWebFetchBody(compact).contains("\n"))
        for (source in listOf("<script>alert(1)</script>", "{broken", "plain text")) assertEquals(source, formatWebFetchBody(source))
    }
}
