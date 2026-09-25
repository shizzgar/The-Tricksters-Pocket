package me.rerere.rikkahub.subagent

import androidx.navigation3.runtime.NavKey
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.context.Navigator
import org.junit.Assert.*
import org.junit.Test

class NestedChatNavigationTest {
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
