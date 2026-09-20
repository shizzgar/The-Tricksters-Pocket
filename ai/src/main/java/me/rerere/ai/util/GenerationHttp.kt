package me.rerere.ai.util

import me.rerere.ai.provider.TextGenerationParams
import me.rerere.common.http.awaitBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Derived clients share the connection pool/dispatcher and retain proxy/auth interceptors.
 * Do not change the application's singleton timeout for an unrelated chat or tool request. */
internal fun OkHttpClient.forTextGeneration(params: TextGenerationParams): OkHttpClient {
    val timeoutMs = params.requestTimeoutMillis ?: return this
    require(timeoutMs in 1..Int.MAX_VALUE.toLong()) { "Invalid generation request timeout" }
    return newBuilder()
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()
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
