package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Maximum source bytes read per request, independently of the returned text window. */
internal const val WEB_FETCH_READ_CAP = 256 * 1024

/** Cancellation closes the actual HTTP call, including a response body currently being read. */
internal suspend fun fetchWebResponse(client: OkHttpClient, request: Request, read: (Response) -> String): String =
    suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use { if (continuation.isActive) read(it) else null }
                    if (result != null && continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

internal fun buildRawFetchEnvelope(
    status: Int, finalUrl: String, text: String, contentType: String?,
    maxChars: Int, startIndex: Int, bodyTruncated: Boolean,
    headers: Map<String, String>?, method: String = "GET",
): String {
    val start = startIndex.coerceIn(0, text.length)
    // UTF-16 offsets match the extraction API. Never emit half a surrogate pair.
    val from = if (start > 0 && start < text.length && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start - 1 else start
    var end = (from.toLong() + maxChars.coerceAtLeast(1)).coerceAtMost(text.length.toLong()).toInt()
    if (end < text.length && end > from && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) {
        end = if (end - from == 1) end + 1 else end - 1
    }
    return buildJsonObject {
        put("status", status)
        put("ok", status in 200..299)
        put("final_url", finalUrl)
        put("extract_mode", "raw")
        contentType?.let { put("content_type", it) }
        put("body", text.substring(from, end))
        put("start_index", from)
        put("returned_chars", end - from)
        put("truncated", end < text.length || bodyTruncated)
        put("body_truncated", bodyTruncated)
        if (end < text.length && method == "GET") put("next_start_index", end)
        if (end < text.length) put("pagination_note", if (method == "GET")
            "Continuation fetches the URL again; content may change between requests. Offsets use UTF-16 characters."
            else "No continuation cursor for POST: repeating the request may repeat its side effects.")
        if (bodyTruncated) put("recovery", "Source exceeded the 262144-byte read limit. Continuation only covers the downloaded prefix; use a scoped download for the complete resource.")
        headers?.let { h -> put("headers", buildJsonObject { h.forEach { (k, v) -> put(k, v) } }) }
    }.toString()
}
