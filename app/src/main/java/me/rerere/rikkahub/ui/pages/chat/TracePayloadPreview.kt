package me.rerere.rikkahub.ui.pages.chat

import kotlinx.serialization.json.*

internal data class TracePayloadPreview(val title: String?, val detail: String)

internal fun traceToolMatches(value: JsonElement, query: String): Boolean {
    if (query.isBlank()) return true
    val obj = value as? JsonObject
    val function = obj?.get("function") as? JsonObject
    return listOf(obj?.get("name"), obj?.get("description"), function?.get("name"), function?.get("description"))
        .any { (it as? JsonPrimitive)?.contentOrNull?.contains(query, ignoreCase = true) == true }
}

/** Inspect only a few fields and a bounded text prefix; never stringify a large payload for a label. */
internal fun tracePayloadPreview(value: JsonElement): TracePayloadPreview {
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    fun compact(text: String) = text.take(240).replace(Regex("\\s+"), " ").trim().let {
        if (text.length > 240) "$it…" else it
    }
    fun content(item: JsonObject): String? {
        item.text("text")?.let { return it }
        item.text("content")?.let { return it }
        item.text("reasoning")?.let { return it }
        for (key in listOf("parts", "content")) {
            (item[key] as? JsonArray)?.take(3)?.forEach { part ->
                (part as? JsonObject)?.let { it.text("text") ?: it.text("reasoning") }?.let { return it }
            }
        }
        return null
    }
    return when (value) {
        is JsonObject -> {
            val function = value["function"] as? JsonObject
            val name = value.text("name") ?: value.text("tool") ?: value.text("toolName") ?: value.text("tool_name") ?: function?.text("name")
            val role = value.text("role")
            val title = name ?: role ?: value.text("title") ?: value.text("source") ?: value.text("type") ?: value.text("id")
            val details = value.text("description") ?: function?.text("description") ?: content(value)
                ?: value.text("command") ?: value.text("url") ?: value.text("path") ?: value.text("status")
                ?: (value["properties"] as? JsonObject)?.keys?.take(5)?.joinToString(" · ")
                ?: value.entries.asSequence().filter { it.key !in setOf("name", "role", "type", "id") }.take(4).joinToString(" · ") { (key, item) ->
                    val primitive = (item as? JsonPrimitive)?.contentOrNull
                    if (primitive != null && primitive.length < 60) "$key: $primitive" else key
                }
            TracePayloadPreview(title?.let(::compact), compact(details))
        }
        is JsonArray -> {
            val detail = value.take(4).joinToString(" · ") { item ->
                when (item) {
                    is JsonPrimitive -> compact(item.contentOrNull ?: "null").take(48)
                    is JsonObject -> compact(item.text("name") ?: (item["function"] as? JsonObject)?.text("name") ?: item.text("role") ?: item.text("type") ?: item.keys.take(2).joinToString(", ")).take(64)
                    is JsonArray -> "[${item.size}]"
                }
            } + if (value.size > 4) " …" else ""
            TracePayloadPreview(null, detail)
        }
        is JsonPrimitive -> TracePayloadPreview(null, compact(value.contentOrNull ?: "null"))
    }
}
