package me.rerere.rikkahub.ui.components.message.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation3.runtime.NavKey
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PocketExperienceInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "trajectory-qa/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun markdownInstructionsAndPartialFailureAreVisible() {
        val view = presentSkill("use_skill", buildJsonObject { put("name", "Pocket guide") }, listOf(
            "# Working with files\n\nRead the instructions, then verify the result.\n\n- Keep user changes\n- Report the output",
            "Termux skill package: {\"success\":false,\"error\":\"Termux unavailable\",\"recovery\":\"Reconnect and retry sync\"}"))
        compose.setContent { CompositionLocalProvider(LocalNavController provides Navigator(mutableListOf())) {
            RikkahubTheme { SkillPreviewContent(view, {}, {}) }
        } }
        compose.onNodeWithText("Pocket guide").assertIsDisplayed()
        compose.onNodeWithText("Termux unavailable", substring = true).assertIsDisplayed()
        capture("pocket-skill-markdown")
    }
    @Test fun scriptPreviewRendersWithoutRunningCode() {
        val view = presentSkill("skill_read_file", buildJsonObject { put("name", "Pocket guide"); put("path", "scripts/check.py") },
            listOf("{\"ok\":true,\"content\":\"print(42)\",\"revision\":\"r1\"}"))
        compose.setContent { CompositionLocalProvider(LocalNavController provides Navigator(mutableListOf())) {
            RikkahubTheme { SkillPreviewContent(view, {}, {}) }
        } }
        compose.onNodeWithText("scripts/check.py").assertIsDisplayed()
        capture("pocket-skill-code")
    }
    @Test fun childCardOpensNestedChatAndNewAvatarsDecode() {
        val child = Conversation.ofId(assistantId = kotlin.uuid.Uuid.random(), id = kotlin.uuid.Uuid.random(), newConversation = true).copy(title = "ThinkBro · Compare options")
        val stack = mutableListOf<NavKey>(Screen.Chat("parent", text = "Keep this entry"))
        val navigator = Navigator(stack)
        compose.setContent { RikkahubTheme {
            ChildChatCard(child, "RUNNING") { TextButton(onClick = { navigator.navigate(Screen.Chat(child.id.toString())) }) { Text("Open specialist") } }
        } }
        compose.onNodeWithText("Open specialist").performClick()
        assertEquals(Screen.Chat(child.id.toString()), stack.last())
        navigator.returnToChat("parent")
        assertEquals(Screen.Chat("parent", text = "Keep this entry"), stack.single())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf("thinkbro", "pocketbro").forEach { name -> context.assets.open("branding/$name-avatar.webp").use {
            val avatar = requireNotNull(BitmapFactory.decodeStream(it)); assertEquals(512, avatar.width); avatar.recycle()
        } }
        capture("pocket-child-chat")
    }
}
