package me.rerere.rikkahub.skills

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real fetch/import boundary without a live DNS or localhost exception. */
class SkillUrlImporterHttpTest {
    private val document = "---\nname: http-test\ndescription: HTTP fixture\n---\nBody"
    private val requested = mutableListOf<String>()
    private fun importer(respond: (Request) -> Response): SkillUrlImporter {
        val client = OkHttpClient.Builder().followRedirects(true).addInterceptor { chain ->
            requested += chain.request().url.toString()
            respond(chain.request())
        }.build()
        return SkillUrlImporter(TempDirSkillSaver(), client)
    }
    private fun response(request: Request, body: ResponseBody = document.toResponseBody(), redirect: String? = null) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (redirect == null) 200 else 302)
            .message("fixture").body(body).apply { if (redirect != null) header("Location", redirect) }.build()

    @Test fun `streaming body stops at cap without trusting advertised length and closes`() = runBlocking {
        for (length in listOf(-1L, 12L, Long.MAX_VALUE)) {
            val body = CountingBody(length)
            val result = importer { response(it, body) }.importFromUrl("https://192.0.2.1/skill.md")
            assertEquals("body_too_large", (result as SkillUrlImporter.Result.Err).code)
            // Okio may fill one 8 KiB segment beyond the requested single byte.
            assertTrue(body.readBytes <= SkillUrlImporter.MAX_BODY_BYTES + 8192L)
            assertTrue(body.closed)
        }
    }

    @Test fun `redirect validates new destination before sending any request to it`() = runBlocking {
        for (target in listOf("http://127.0.0.1/private", "http://localhost:8080/private", "http://[::1]/private", "http://0.0.0.0/private")) {
            requested.clear()
            val result = importer { response(it, redirect = target) }.importFromUrl("https://192.0.2.1/skill.md")
            assertEquals("loopback_host_rejected", (result as SkillUrlImporter.Result.Err).code)
            assertEquals(listOf("https://192.0.2.1/skill.md"), requested)
        }
    }

    @Test fun `relative redirects import normally and RFC1918 policy is preserved`() = runBlocking {
        val result = importer { request ->
            if (request.url.encodedPath == "/start") response(request, redirect = "/raw/skill.md") else response(request)
        }.importFromUrl("https://192.168.42.12/start")
        assertTrue(result.toString(), result is SkillUrlImporter.Result.Ok)
        assertEquals(listOf("https://192.168.42.12/start", "https://192.168.42.12/raw/skill.md"), requested)
    }

    @Test fun `redirect cycles are bounded`() = runBlocking {
        val result = importer { response(it, redirect = "/again") }.importFromUrl("https://192.0.2.1/start")
        assertEquals("too_many_redirects", (result as SkillUrlImporter.Result.Err).code)
        assertEquals(6, requested.size)
    }

    @Test fun `UTF8 byte limit applies to pasted content too`() {
        val result = importer { response(it) }.importFromText(document + "я".repeat(SkillUrlImporter.MAX_BODY_BYTES / 2))
        assertEquals("body_too_large", (result as SkillUrlImporter.Result.Err).code)
    }

    @Test fun `cancellation is not converted to network failure`() = runBlocking {
        try {
            importer { throw CancellationException("cancel fixture") }.importFromUrl("https://192.0.2.1/start")
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("cancel fixture", expected.message)
        }
    }

    private class CountingBody(private val advertisedLength: Long) : ResponseBody() {
        var readBytes = 0L
        var closed = false
        private val data = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                sink.write(ByteArray(byteCount.toInt()) { 'x'.code.toByte() })
                readBytes += byteCount
                return byteCount
            }
            override fun timeout() = Timeout.NONE
            override fun close() { closed = true }
        }.buffer()
        override fun contentType(): MediaType = "text/plain; charset=utf-8".toMediaType()
        override fun contentLength() = advertisedLength
        override fun source(): BufferedSource = data
    }
}
