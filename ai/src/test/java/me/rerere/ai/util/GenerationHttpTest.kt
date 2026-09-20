package me.rerere.ai.util

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class GenerationHttpTest {
    @Test fun `connect idle and total deadlines are independent and tool client stays unchanged`() {
        val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.MINUTES).build()
        val params = me.rerere.ai.provider.GenerationRuntimeSettings().applyTo(TextGenerationParams(Model(modelId = "qwen")))
        val scoped = client.forTextGeneration(params)
        assertEquals(20_000, scoped.connectTimeoutMillis)
        assertEquals(1_800_000, scoped.readTimeoutMillis)
        assertEquals(3_600_000, scoped.callTimeoutMillis)
        assertEquals(600_000, client.readTimeoutMillis)
        assertEquals(0, client.callTimeoutMillis)
    }
    @Test fun `compaction gets its own read and call timeout without changing ordinary chat`() {
        val original = OkHttpClient.Builder().readTimeout(10, TimeUnit.MINUTES).build()
        val params = TextGenerationParams(Model(modelId = "test"), requestTimeoutMillis = 15 * 60_000L)
        val scoped = original.forTextGeneration(params)
        assertEquals(900_000, scoped.readTimeoutMillis)
        assertEquals(900_000, scoped.callTimeoutMillis)
        assertEquals(600_000, original.readTimeoutMillis)
        assertEquals(0, original.callTimeoutMillis)
        assertEquals(original.connectTimeoutMillis, scoped.connectTimeoutMillis)
        assertSame(original.connectionPool, scoped.connectionPool)
        assertSame(original.dispatcher, scoped.dispatcher)
        assertSame(original, original.forTextGeneration(params.copy(requestTimeoutMillis = null)))
    }
}
