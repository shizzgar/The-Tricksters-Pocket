package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebFetchResponseTest {
    private fun raw(text: String, start: Int = 0, max: Int = 3, cut: Boolean = false, method: String = "GET") =
        Json.parseToJsonElement(buildRawFetchEnvelope(200, "https://example.org", text, "text/plain", max, start, cut, null, method)).jsonObject

    @Test fun `raw windows advance and distinguish missing source bytes from more buffered text`() {
        val first = raw("abcdef")
        assertEquals("abc", first["body"]!!.jsonPrimitive.content)
        assertEquals(3, first["next_start_index"]!!.jsonPrimitive.int)
        val last = raw("abcdef", start = 3, cut = true)
        assertEquals("def", last["body"]!!.jsonPrimitive.content)
        assertTrue(last["truncated"]!!.jsonPrimitive.boolean)
        assertNull(last["next_start_index"])
        assertTrue(last.containsKey("recovery"))
        assertFalse(raw("abc")["truncated"]!!.jsonPrimitive.boolean)
    }
    @Test fun `Unicode boundaries never produce isolated surrogate halves`() {
        val first = raw("😀abc", max = 1)
        assertEquals("😀", first["body"]!!.jsonPrimitive.content)
        assertEquals(2, first["next_start_index"]!!.jsonPrimitive.int)
        assertEquals("abc", raw("😀abc", start = 2)["body"]!!.jsonPrimitive.content)
    }
    @Test fun `POST never offers an automatic continuation that repeats the mutation`() {
        val result = raw("abcdef", method = "POST")
        assertNull(result["next_start_index"])
        assertTrue(result["truncated"]!!.jsonPrimitive.boolean)
        assertTrue(result["pagination_note"]!!.jsonPrimitive.content.contains("side effects"))
    }
    @Test fun `the real tool honors raw max chars and offset through its guarded client`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/plain").body("abcdef".toResponseBody()).build()
        }.build()
        try {
            val parts = webFetchTool(client).execute(Json.parseToJsonElement("""{"url":"https://example.org","max_chars":2,"start_index":2}"""))
            val result = Json.parseToJsonElement(parts.filterIsInstance<UIMessagePart.Text>().single().text).jsonObject
            assertEquals("cd", result["body"]!!.jsonPrimitive.content)
            assertEquals(4, result["next_start_index"]!!.jsonPrimitive.int)
            assertEquals("text/plain", result["content_type"]!!.jsonPrimitive.content)
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    @Test fun `cancellation cancels the in flight HTTP call without waiting for read timeout`() = runBlocking {
        val started = CompletableDeferred<Call>()
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            started.complete(chain.call())
            check(release.await(3, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("late".toResponseBody()).build()
        }.build()
        try {
            val worker = async { fetchWebResponse(client, Request.Builder().url("https://example.org").build()) { it.body.string() } }
            val call = withTimeout(3000) { started.await() }
            withTimeout(1000) { worker.cancelAndJoin() }
            assertTrue(call.isCanceled())
        } finally { release.countDown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
}
