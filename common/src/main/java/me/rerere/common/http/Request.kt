package me.rerere.common.http

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okio.IOException
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.resume

data class TextResponse(val code: Int, val body: String)

/** Unlike await(), this suspension covers headers AND body. Reading a response body after
 * await() returns would detach coroutine cancellation from a stalled socket read. */
suspend fun Call.awaitBody(): TextResponse = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use { TextResponse(it.code, it.body.string()) }
                if (continuation.isActive) continuation.resume(result)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    })
}

suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
        // A coroutine timeout/cancellation must terminate the underlying OkHttp call too.
        // Without this hook, a timed-out request remains in the dispatcher until the client's
        // read timeout (currently ten minutes for AI calls), consuming sockets and keeping a
        // cancelled operation alive in the background.
        continuation.invokeOnCancellation {
            cancel()
        }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { cause, _, _ ->
                    response.closeQuietly()
                }
            }
        })
    }
}
