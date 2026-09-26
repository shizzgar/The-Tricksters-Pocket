package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BackupCredentialsTest {
    @Test fun `default backup removes nested provider integration OAuth and SSH credentials`() {
        val source = Json.parseToJsonElement("""{
            "providers":[{"id":"p1","apiKey":"synthetic-secret","privateKey":"synthetic-secret",
                "customHeaders":[{"name":"X-Whatever","value":"synthetic-secret"}]}],
            "mcpServers":[{"commonOptions":{"headers":[["Authorization","synthetic-secret"]],
                "oauth":{"accessToken":"synthetic-secret","refresh_token":"synthetic-secret","clientSecret":"synthetic-secret"}}}],
            "tts":{"apiKey":"synthetic-secret"},"asr":{"api_key":"synthetic-secret"},
            "webDavConfig":{"password":"synthetic-secret"},
            "s3Config":{"accessKeyId":"synthetic-secret","secretAccessKey":"synthetic-secret"},
            "webServerAccessPassword":"synthetic-secret",
            "env":{"CUSTOM":"synthetic-secret"},
            "url":"https://user:synthetic-secret@example.test/api?token=synthetic-secret&limit=3",
            "name":"my assistant","maxTokens":4096
        }""")
        val result = BackupCredentials.strip(source).jsonObject
        assertFalse(result.toString().contains("synthetic-secret"))
        assertEquals("my assistant", result["name"]?.jsonPrimitive?.content)
        assertEquals(4096, result["maxTokens"]?.jsonPrimitive?.int)
        assertEquals("https://example.test/api", result["url"]!!.jsonPrimitive.content)
    }

    @Test fun `null credentials and nonsecret structural values preserve type`() {
        val result = BackupCredentials.strip(Json.parseToJsonElement("""{"password":null,"enabled":true,"items":["DATABASE"],"models":[]} """))
        assertEquals(JsonNull, result.jsonObject["password"])
        assertEquals(JsonPrimitive(true), result.jsonObject["enabled"])
        assertEquals(JsonArray(listOf(JsonPrimitive("DATABASE"))), result.jsonObject["items"])
    }
    @Test fun `query credentials with arbitrary names uppercase schemes and fragments are excluded`() {
        val value = JsonPrimitive("HTTPS://user:synthetic-secret@example.test/mcp?exaApiKey=synthetic-secret&api-key=synthetic-secret#synthetic-secret")
        assertEquals(JsonPrimitive("HTTPS://example.test/mcp"), BackupCredentials.strip(value))
    }
}
