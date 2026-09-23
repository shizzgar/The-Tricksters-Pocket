package me.rerere.ai.util

import me.rerere.ai.provider.TextGenerationParams
import me.rerere.common.http.awaitBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Derived clients share the connection pool/dispatcher and retain proxy/auth interceptors.
 * Do not change the application's singleton timeout for an unrelated chat or tool request. */
internal fun OkHttpClient.forTextGeneration(params: TextGenerationParams): OkHttpClient {
    if (params.requestTimeoutMillis == null && params.readTimeoutMillis == null && params.connectTimeoutMillis == null && params.requestObserver == null) return this
    val builder = newBuilder().eventListenerFactory { GenerationTelemetry(params.requestObserver) }
    fun checkTimeout(value: Long) = require(value in 1..Int.MAX_VALUE.toLong()) { "Invalid generation timeout" }
    (params.readTimeoutMillis ?: params.requestTimeoutMillis)?.let {
        checkTimeout(it)
        builder.readTimeout(it, TimeUnit.MILLISECONDS)
    }
    params.requestTimeoutMillis?.let { checkTimeout(it); builder.callTimeout(it, TimeUnit.MILLISECONDS) }
    params.connectTimeoutMillis?.let { checkTimeout(it); builder.connectTimeout(it, TimeUnit.MILLISECONDS) }
    return builder.build()
}

/** Cancellation remains wired to the call until the complete response body has been read. */
internal suspend fun OkHttpClient.generateResponseBody(
    request: Request,
    params: TextGenerationParams,
): String {
    val response = forTextGeneration(params).newCall(request).awaitBody()
    if (response.code !in 200..299) {
        throw IllegalStateException("Failed to get response: ${response.code} ${response.body}")
    }
    return response.body
}
