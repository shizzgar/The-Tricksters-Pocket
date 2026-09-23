package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class WebFetchState { PENDING, RUNNING, APPROVAL, DENIED, RECEIVED, HTTP_ERROR, FAILED, UNKNOWN }

internal data class WebFetchPresentation(
    val state: WebFetchState,
    val result: JsonObject?,
    val url: String?,
    val requestedUrl: String?,
    val method: String,
    val mode: String,
    val status: Int?,
) {
    val body get() = result.webText("body") ?: result.webText("text")
    val sourceTruncated get() = result.webBool("body_truncated") == true
    val truncated get() = result.webBool("truncated") == true || sourceTruncated
    val nextIndex get() = (result?.get("next_start_index") as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
}

internal fun JsonObject?.webText(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject?.webBool(key: String): Boolean? = (this?.get(key) as? JsonPrimitive)?.booleanOrNull
internal fun webHttpUrl(value: String?): String? = value?.toHttpUrlOrNull()?.toString()
internal fun formatWebFetchBody(text: String): String = runCatching {
    val value = Json.parseToJsonElement(text)
    if (value is JsonObject || value is JsonArray) Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), value) else text
}.getOrDefault(text)

internal fun presentWebFetch(
    name: String, arguments: JsonElement, result: JsonElement?,
    loading: Boolean, started: Boolean, hasResult: Boolean,
    denied: Boolean = false, pending: Boolean = false,
): WebFetchPresentation {
    val args = arguments as? JsonObject
    val out = result as? JsonObject
    val status = (out?.get("status") as? JsonPrimitive)?.intOrNull?.takeIf { it in 100..599 }
    val error = out?.get("error")?.takeIf { it != JsonNull && it != JsonPrimitive(false) && it != JsonPrimitive("") }
    val state = when {
        denied -> WebFetchState.DENIED
        pending -> WebFetchState.APPROVAL
        error != null -> WebFetchState.FAILED
        status != null && status !in 200..299 -> WebFetchState.HTTP_ERROR
        out.webBool("ok") == false -> WebFetchState.HTTP_ERROR
        status != null && status in 200..299 -> WebFetchState.RECEIVED
        !hasResult && loading && started -> WebFetchState.RUNNING
        !hasResult && !started -> WebFetchState.PENDING
        else -> WebFetchState.UNKNOWN
    }
    return WebFetchPresentation(state, out, out.webText("final_url") ?: args.webText("url"),
        args.webText("url"), args.webText("method")?.uppercase() ?: "GET",
        out.webText("extract_mode") ?: args.webText("extract_mode") ?: args.webText("mode")
            ?: if (name == "web_extract") "article" else "raw", status)
}
