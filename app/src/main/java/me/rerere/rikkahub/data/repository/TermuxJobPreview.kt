package me.rerere.rikkahub.data.repository

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.rikkahub.data.ai.tools.local.takeFirstUtf8Bytes

internal data class TermuxJobPreview(val text: String, val truncated: Boolean)

/** The job protocol pages at 32 kB; the shared stdout preference allows up to 64 kB. */
internal suspend fun readTermuxJobPreview(
    maxBytes: Int,
    readPage: suspend (cursor: Long, maxBytes: Int) -> JsonObject,
): TermuxJobPreview {
    require(maxBytes > 0)
    val text = StringBuilder()
    var remaining = maxBytes
    var cursor = 0L
    while (remaining > 0) {
        val page = readPage(cursor, remaining.coerceIn(256, 32_000))
        val full = page["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val bounded = takeFirstUtf8Bytes(full, remaining)
        text.append(bounded)
        remaining -= bounded.toByteArray(Charsets.UTF_8).size
        val more = page["has_more"]?.jsonPrimitive?.booleanOrNull == true
        if (bounded != full) return TermuxJobPreview(text.toString(), truncated = true)
        if (!more) return TermuxJobPreview(text.toString(), truncated = false)
        val next = page["next_cursor"]?.jsonPrimitive?.longOrNull ?: cursor
        // An unavailable or malformed page must never trap the console in an infinite loop.
        if (next <= cursor || bounded.isEmpty()) return TermuxJobPreview(text.toString(), truncated = true)
        cursor = next
    }
    return TermuxJobPreview(text.toString(), truncated = true)
}
