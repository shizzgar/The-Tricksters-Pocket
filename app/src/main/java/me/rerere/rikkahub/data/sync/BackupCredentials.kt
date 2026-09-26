package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.*

/** Removes structured credential fields. Chat text and user files are deliberately not rewritten. */
internal object BackupCredentials {
    private val secretNames = setOf("apikey", "password", "privatekey", "passphrase", "secret", "secretaccesskey",
        "accesskeyid", "accesstoken", "refreshtoken", "idtoken", "clientsecret", "authorization", "cookie", "token",
        "serviceaccountjson", "serviceaccount", "credentials", "webserveraccesspassword", "vertexserviceaccountjson")
    private val secretContainers = setOf("headers", "httpheaders", "env", "environment", "customheaders", "custombodies")
    fun strip(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (key, value) ->
            val normalized = key.lowercase().filter(Char::isLetterOrDigit)
            when {
                normalized in secretContainers || normalized in secretNames || normalized.endsWith("apikey") ||
                    normalized.endsWith("password") || normalized.endsWith("secret") || normalized.endsWith("accesstoken") ||
                    normalized.endsWith("refreshtoken") || normalized.endsWith("serviceaccountjson") -> empty(value)
                else -> strip(value)
            }
        })
        is JsonArray -> JsonArray(element.map(::strip))
        is JsonPrimitive -> if (element.isString) JsonPrimitive(stripUrlCredentials(element.content)) else element
    }
    private fun empty(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(emptyMap())
        is JsonArray -> JsonArray(emptyList())
        JsonNull -> JsonNull
        else -> JsonPrimitive("")
    }
    // Integrations use arbitrary query/fragment names for credentials, including custom names.
    // Removing all URL parameters is safer than guessing which provider-specific name is secret.
    private fun stripUrlCredentials(value: String): String = if (value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)) {
        value.substringBefore('?').substringBefore('#')
            .replace(Regex("(?i)^(https?://)[^/@\\s]+@"), "$1")
    } else value
}
