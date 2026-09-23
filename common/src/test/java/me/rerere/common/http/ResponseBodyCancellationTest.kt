package me.rerere.common.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Proxy
import java.net.SocketException
import kotlin.concurrent.thread

class ResponseBodyCancellationTest {
    @Test fun `cancel after response headers closes the stalled body socket`() = runBlocking {
        val bodyStarted = CompletableDeferred<Unit>()
        val peerClosed = CompletableDeferred<Boolean>()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).eventListener(object : EventListener() {
            override fun responseBodyStart(call: Call) { bodyStarted.complete(Unit) }
        }).build()
        val worker = thread(isDaemon = true, name = "stalled-compaction-response") {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* request headers */ }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nx".toByteArray())
                        flush()
                    }
                    peerClosed.complete(reader.read() == -1)
                }
            } catch (e: SocketException) {
                // Depending on the TCP stack a cancelled body read closes with EOF or RST.
                peerClosed.complete(true)
            } catch (e: Exception) {
                peerClosed.completeExceptionally(e)
            }
        }
        try {
            val call = client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
            val job = launch { call.awaitBody() }
            withTimeout(5_000) { bodyStarted.await() }
            job.cancelAndJoin()
            assertTrue(call.isCanceled())
            assertTrue(withTimeout(5_000) { peerClosed.await() })
        } finally {
            server.close()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            worker.join(5_000)
        }
    }
}
