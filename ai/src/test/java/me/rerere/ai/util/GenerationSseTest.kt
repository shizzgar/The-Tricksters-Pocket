package me.rerere.ai.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import kotlin.concurrent.thread

class GenerationSseTest {
    @Test fun `actual SSE parser ignores heartbeat comments and delivers data`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
            .forTextGeneration(TextGenerationParams(Model(modelId = "test"), readTimeoutMillis = 2_000, requestTimeoutMillis = 5_000))
        val received = mutableListOf<String>()
        val done = CompletableDeferred<Unit>()
        val worker = thread(isDaemon = true) {
            server.accept().use { socket ->
                socket.soTimeout = 5_000
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                val body = ": keepalive\n\n: another comment\r\n\r\ndata: {\"text\":\"hello\"}\n\n: keepalive\n\ndata: [DONE]\n\n"
                socket.getOutputStream().apply {
                    write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n" + body).toByteArray())
                    flush()
                }
            }
        }
        val source = EventSources.createFactory(client).newEventSource(
            Request.Builder().url("http://127.0.0.1:${server.localPort}/").build(),
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) { received.add(data) }
                override fun onClosed(eventSource: EventSource) { done.complete(Unit) }
                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) { done.completeExceptionally(t ?: IllegalStateException("SSE failed")) }
            },
        )
        try {
            withTimeout(5_000) { done.await() }
            assertEquals(listOf("{\"text\":\"hello\"}", "[DONE]"), received)
        } finally {
            source.cancel(); server.close()
            client.dispatcher.cancelAll(); client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow(); worker.join(5_000)
        }
    }
}
