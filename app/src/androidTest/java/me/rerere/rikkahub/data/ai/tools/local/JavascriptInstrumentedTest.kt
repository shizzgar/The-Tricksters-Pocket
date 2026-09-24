package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class JavascriptInstrumentedTest {
    @Test fun nativeTimeoutAndCancellationLeaveEngineUsable() = runBlocking {
        withTimeout(15_000) {
            val timeout = Json.parseToJsonElement(evaluateJavascript("while (true) {}", 150)).jsonObject
            assertTrue(timeout["error"]!!.jsonPrimitive.content.contains("timed out"))
            val execution = async(Dispatchers.Default) { evaluateJavascript("while (true) {}", 60_000) }
            delay(200)
            execution.cancel()
            withTimeout(2_000) { execution.join() }
            assertTrue(execution.isCancelled)
            val normal = Json.parseToJsonElement(evaluateJavascript("6 * 7")).jsonObject
            assertEquals("42", normal["result"]!!.jsonPrimitive.content)
        }
    }
}
