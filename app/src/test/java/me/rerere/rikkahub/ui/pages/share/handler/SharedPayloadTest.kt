package me.rerere.rikkahub.ui.pages.share.handler

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedPayloadTest {
    @Test fun `duplicate parcelable and clip streams are copied only once with order preserved`() {
        assertEquals(listOf("content://provider/a", "content://provider/b", "file:///external/c"), normalizeSharedUris(listOf("content://provider/a", "content://provider/b"), listOf("content://provider/a", "file:///external/c")))
    }
    @Test fun `web and data payloads are not treated as file grants`() {
        assertEquals(listOf("content://provider/a"), normalizeSharedUris(listOf("https://example.org/a", "data:image/png;base64,x", "content://provider/a"), emptyList()))
    }
}
