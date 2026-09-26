package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxJobPreviewTest {
    @Test fun sharedStdoutLimitReadsMoreThanOneProtocolPage() = runTest {
        val source = "a".repeat(50_000)
        val cursors = mutableListOf<Long>()
        val result = readTermuxJobPreview(64_000) { cursor, limit ->
            cursors += cursor
            val end = (cursor.toInt() + limit).coerceAtMost(source.length)
            buildJsonObject {
                put("text", source.substring(cursor.toInt(), end))
                put("next_cursor", end)
                put("has_more", end < source.length)
            }
        }
        assertEquals(source, result.text)
        assertEquals(listOf(0L, 32_000L), cursors)
        assertFalse(result.truncated)
    }

    @Test fun sharedStderrLimitDoesNotSplitUtf8OrHideTruncation() = runTest {
        val result = readTermuxJobPreview(501) { _, _ ->
            buildJsonObject {
                put("text", "a".repeat(499) + "🙂")
                put("next_cursor", 503)
                put("has_more", false)
            }
        }
        assertEquals("a".repeat(499), result.text)
        assertTrue(result.truncated)
    }

    @Test fun finalPageAtExactLimitIsNotTruncated() = runTest {
        val result = readTermuxJobPreview(1000) { _, _ -> buildJsonObject {
            put("text", "a".repeat(1000)); put("next_cursor", 1000); put("has_more", false)
        } }
        assertFalse(result.truncated)
    }

    @Test fun noProgressPageStopsWithoutRetryingForever() = runTest {
        var calls = 0
        val result = readTermuxJobPreview(1000) { _, _ ->
            calls++
            buildJsonObject { put("text", ""); put("next_cursor", 0); put("has_more", true) }
        }
        assertEquals(1, calls)
        assertTrue(result.truncated)
    }
}
