package me.rerere.rikkahub.subagent

import androidx.navigation3.runtime.NavKey
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.Navigator
import org.junit.Assert.*
import org.junit.Test

class NestedChatNavigationTest {
    @Test fun resultEnvelopeCarriesDurableChatIdentityWithoutLeakingSuppressedText() {
        val run = SubAgentRun(id = "run", parentChatId = "parent", parentAssistantId = "assistant", label = "Task", task = "Task",
            modelId = null, tools = null, runInBackground = false, timeoutSeconds = 60, maxTrips = 10,
            status = SubAgentStatus.SUCCEEDED, startedAtMs = 1, conversationId = "child", noResult = true, result = "Private result")
        val json = encodeRun(run)
        assertEquals(kotlinx.serialization.json.JsonPrimitive("child"), json["conversation_id"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive("parent"), json["parent_conversation_id"])
        assertFalse(json.containsKey("result"))
    }

    @Test fun returningToParentKeepsOriginalEntryIncludingLaunchArguments() {
        val parent = Screen.Chat("parent", text = "initial task")
        val stack = mutableListOf<NavKey>(parent, Screen.Chat("child"))
        Navigator(stack).returnToChat("parent")
        assertEquals(listOf(parent), stack)
    }
    @Test fun parentCanBeOpenedWhenChildWasOpenedFromHistory() {
        val stack = mutableListOf<NavKey>(Screen.Chat("child"))
        Navigator(stack).returnToChat("parent")
        assertEquals(Screen.Chat("parent"), stack.last())
    }
}
