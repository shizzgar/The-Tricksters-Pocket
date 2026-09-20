package me.rerere.ai.util

import android.util.Log
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Response
import java.io.IOException

/** Allow-listed routing diagnostics only: no request bodies, URLs, credentials or session IDs. */
internal class GenerationTelemetry : EventListener() {
    private var started = System.nanoTime()
    private var backend = "unspecified"

    override fun callStart(call: Call) { started = System.nanoTime() }
    override fun responseHeadersEnd(call: Call, response: Response) {
        backend = response.header("X-Triage-Backend")?.takeIf { it in setOf("primary", "secondary") } ?: "unspecified"
        Log.i("GenerationTransport", "headers backend=$backend elapsedMs=${elapsedMs()} status=${response.code}")
    }
    override fun callEnd(call: Call) {
        Log.i("GenerationTransport", "completed backend=$backend elapsedMs=${elapsedMs()}")
    }
    override fun callFailed(call: Call, ioe: IOException) {
        Log.i("GenerationTransport", "failed backend=$backend elapsedMs=${elapsedMs()} type=${ioe.javaClass.simpleName}")
    }
    private fun elapsedMs() = (System.nanoTime() - started) / 1_000_000
}
