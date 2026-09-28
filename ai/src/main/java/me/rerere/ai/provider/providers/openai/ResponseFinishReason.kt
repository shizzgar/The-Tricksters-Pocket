package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.jsonObjectOrNull

/** Both streamed and non-streamed Responses results retain incomplete_details. */
internal fun responseFinishReason(response: JsonObject?, fallbackStatus: String? = null): String? {
    val status = response?.get("status")?.jsonPrimitive?.contentOrNull ?: fallbackStatus
    val reason = response?.get("incomplete_details")?.jsonObjectOrNull
        ?.get("reason")?.jsonPrimitive?.contentOrNull
    return if (status == "incomplete" && !reason.isNullOrBlank()) "$status:$reason" else status
}
