package me.rerere.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationFinishKindTest {
    @Test fun `provider token limit variants have one meaning`() {
        for (reason in listOf("length", "max_tokens", "MAX_TOKENS", "max_output_tokens", "incomplete:max_output_tokens")) {
            assertEquals(reason, GenerationFinishKind.OUTPUT_LIMIT, classifyGenerationFinish(reason))
        }
    }

    @Test fun `unknown incomplete reasons and safety responses cannot be accepted as complete compaction`() {
        for (reason in listOf("incomplete", "incomplete:new_reason", "failed", "cancelled")) {
            assertEquals(GenerationFinishKind.INCOMPLETE, classifyGenerationFinish(reason))
        }
        for (reason in listOf("content_filter", "incomplete:content_filter", "SAFETY", "RECITATION")) {
            assertEquals(GenerationFinishKind.BLOCKED, classifyGenerationFinish(reason))
        }
        for (reason in listOf(null, "stop", "STOP", "end_turn", "completed")) {
            assertEquals(GenerationFinishKind.COMPLETE, classifyGenerationFinish(reason))
        }
    }
}
