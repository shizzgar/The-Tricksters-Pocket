package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AIIconMatcherTest {
    @Test fun `unknown model uses a recognized provider`() {
        assertEquals("OpenAI", computeModelIconName("deployment-17", "Private model", "OpenAI"))
    }

    @Test fun `recognized model identity takes precedence over gateway provider`() {
        assertEquals("claude-sonnet-4", computeModelIconName("claude-sonnet-4", "Sonnet", "OpenRouter"))
        assertEquals("Gemini Pro", computeModelIconName("deployment-17", "Gemini Pro", "OpenRouter"))
    }

    @Test fun `automatic and legacy aliases do not hide a recognized provider`() {
        listOf("auto", "rikka", "pocket").forEach { alias ->
            assertEquals("OpenAI", computeModelIconName(alias, alias, "OpenAI"))
        }
    }

    @Test fun `unidentified model and provider keep the Pocket fallback`() {
        val name = computeModelIconName("local-workbench", "Private model", "Custom endpoint")
        assertEquals("local-workbench", name)
        assertNull(computeAIIconByName(name))
    }

    @Test fun `search services never use app fallback or app aliases`() {
        listOf("Built-in", "Custom JS", "auto", "RikkaHub", "Pocket search").forEach { name ->
            assertNull(name, computeSearchIconByName(name))
        }
        assertEquals("bing.png", computeSearchIconByName("Bing"))
        assertEquals("brave.svg", computeSearchIconByName("Brave"))
    }
}
