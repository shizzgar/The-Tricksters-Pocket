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
            compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun markdownInstructionsAndPartialFailureAreVisible() {
        val view = presentSkill("use_skill", buildJsonObject { put("name", "Pocket guide") }, listOf(
            "# Working with files\n\nRead the instructions, then verify the result.\n\n- Keep user changes\n- Report the output",
            "Termux skill package: {\"success\":false,\"error\":\"Termux unavailable\",\"recovery\":\"Reconnect and retry sync\"}"))
        compose.setContent { CompositionLocalProvider(LocalNavController provides Navigator(mutableListOf()),
            me.rerere.rikkahub.ui.context.LocalSettings provides me.rerere.rikkahub.data.datastore.Settings()) {
            RikkahubTheme { SkillPreviewContent(view, {}, {}) }
        } }
        compose.onNodeWithText("Pocket guide").assertIsDisplayed()
        compose.onNodeWithText("Termux unavailable", substring = true).assertIsDisplayed()
        capture("pocket-skill-markdown")
    }
    @Test fun scriptPreviewRendersWithoutRunningCode() {
        val view = presentSkill("skill_read_file", buildJsonObject { put("name", "Pocket guide"); put("path", "scripts/check.py") },
            listOf("{\"ok\":true,\"content\":\"print(42)\",\"revision\":\"r1\"}"))
        compose.setContent { CompositionLocalProvider(LocalNavController provides Navigator(mutableListOf()),
            me.rerere.rikkahub.ui.context.LocalSettings provides me.rerere.rikkahub.data.datastore.Settings()) {
            RikkahubTheme { SkillPreviewContent(view, {}, {}) }
        } }
        compose.onNodeWithText("scripts/check.py").assertIsDisplayed()
        capture("pocket-skill-code")
    }
    @Test fun taskDashboardShowsChildResultsAndPreparesIndependentVerification() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = org.koin.core.context.GlobalContext.get()
        val repository = koin.get<me.rerere.rikkahub.data.repository.ConversationRepository>()
        val workspaces = koin.get<me.rerere.rikkahub.data.repository.WorkspaceRepository>()
        val store = me.rerere.rikkahub.data.task.TaskArtifactStore.at(context.filesDir)
        val parentId = kotlin.uuid.Uuid.random()
        val childId = kotlin.uuid.Uuid.random()
        val parent = Conversation.ofId(parentId, me.rerere.rikkahub.data.datastore.createOrchbroAssistant().id).copy(title = "Build a checked release")
        val child = Conversation.ofId(childId, me.rerere.rikkahub.data.datastore.createDevbroAssistant().id).copy(title = "Implement the release", parentConversationId = parentId)
        lateinit var workspace: me.rerere.rikkahub.data.db.entity.WorkspaceEntity
        lateinit var artifact: me.rerere.rikkahub.data.task.TaskArtifact
        kotlinx.coroutines.runBlocking {
            workspace = workspaces.create("Task QA " + parentId.toString().take(8))
            workspaces.writeText(workspace.id, "result.txt", "verified fixture", false)
            repository.insertConversation(parent)
            repository.insertConversation(child)
            store.saveBrief(parentId.toString(), me.rerere.rikkahub.data.task.TaskBrief("Build a checked release", "Tests and signature must pass"))
            artifact = store.register(childId.toString(), child.assistantId.toString(), workspace.id, "/workspace/result.txt", workspaces, "Release evidence", diff = "--- a/result.txt\n+++ b/result.txt\n@@ -0,0 +1 @@\n+verified fixture")
        }
        val stack = mutableListOf<NavKey>(Screen.Chat(parentId.toString()), Screen.TaskDashboard(parentId.toString()))
        val navigator = Navigator(stack)
        try {
            compose.setContent { CompositionLocalProvider(
                LocalNavController provides navigator,
                me.rerere.rikkahub.ui.context.LocalSettings provides me.rerere.rikkahub.data.datastore.Settings(assistants = listOf(
                    me.rerere.rikkahub.data.datastore.createOrchbroAssistant(), me.rerere.rikkahub.data.datastore.createDevbroAssistant(), me.rerere.rikkahub.data.datastore.createVerifybroAssistant(),
                )),
            ) { RikkahubTheme { me.rerere.rikkahub.ui.pages.chat.TaskDashboardScreen(parentId) } } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Build a checked release").fetchSemanticsNodes().isNotEmpty() }
            capture("pocket-task-dashboard")
            compose.onNodeWithTag("task-dashboard-list").performScrollToNode(hasText(context.getString(me.rerere.rikkahub.R.string.task_results)))
            // LazyColumn does not compose off-screen result cards. Scroll while the async
            // conversation/artifact snapshot settles, then verify the actual visible card.
            compose.waitUntil(10_000) {
                runCatching {
                    compose.onNodeWithTag("task-dashboard-list").performScrollToNode(hasText("Release evidence"))
                    compose.onNodeWithText("Release evidence").assertIsDisplayed()
                    true
                }.getOrDefault(false)
            }
            compose.onNodeWithText("Release evidence").assertIsDisplayed()
            compose.onNodeWithText("SHA-256: " + artifact.sha256, substring = true).assertIsDisplayed()
            capture("pocket-task-results")
            val verify = context.getString(me.rerere.rikkahub.R.string.task_verify)
            compose.onNodeWithTag("task-dashboard-list").performScrollToNode(hasText(verify))
            compose.onNodeWithText(verify).performClick()
            val reviewerId = me.rerere.rikkahub.data.datastore.createDevbroAssistant().id
            compose.onNodeWithTag("task-review-assistant-$reviewerId").performClick()
            capture("pocket-task-review-picker")
            compose.onNodeWithText(context.getString(me.rerere.rikkahub.R.string.task_review_prepare)).performClick()
            compose.waitUntil(10_000) { stack.last() is Screen.Chat }
            val review = stack.last() as Screen.Chat
            assertTrue(review.text!!.contains("Tests and signature must pass"))
            assertTrue(review.text!!.contains(artifact.sha256))
            assertTrue(review.text!!.contains(childId.toString()))
            kotlinx.coroutines.runBlocking {
                val reviewChat = requireNotNull(repository.getConversationById(kotlin.uuid.Uuid.parse(review.id)))
                assertEquals(parentId, reviewChat.parentConversationId)
                assertEquals(reviewerId, reviewChat.assistantId)
                assertEquals(reviewerId.toString(), store.brief(parentId.toString()).reviewAssistantId)
                assertTrue(me.rerere.rikkahub.data.ai.AgentTaskPolicy.get(reviewChat.id.toString())!!.readOnly)
                assertNotNull(store.reviewScope(reviewChat.id.toString()))
                repository.deleteConversation(reviewChat)
            }
        } finally { kotlinx.coroutines.runBlocking {
            repository.deleteConversation(child)
            repository.deleteConversation(parent)
            workspaces.delete(workspace.id)
        } }
    }


    @Test fun taskCardRequiresExplicitCreationAndClosingKeepsDetails() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = org.koin.core.context.GlobalContext.get().get<me.rerere.rikkahub.data.repository.ConversationRepository>()
        val store = me.rerere.rikkahub.data.task.TaskArtifactStore.at(context.filesDir)
        val conversation = Conversation.ofId(id = kotlin.uuid.Uuid.random(), assistantId = me.rerere.rikkahub.data.datastore.createDevbroAssistant().id).copy(title = "Task opt-in fixture")
        val showEditor = androidx.compose.runtime.mutableStateOf(false)
        kotlinx.coroutines.runBlocking { repository.insertConversation(conversation) }
        try {
            compose.setContent {
                CompositionLocalProvider(
                    LocalNavController provides Navigator(mutableListOf<NavKey>(Screen.Chat(conversation.id.toString()))),
                    me.rerere.rikkahub.ui.context.LocalToaster provides com.dokar.sonner.rememberToasterState(),
                ) {
                    RikkahubTheme {
                        me.rerere.rikkahub.ui.pages.chat.TaskChatControls(conversation, showEditor.value, { showEditor.value = false })
                    }
                }
            }
            compose.onNodeWithTag("chat-task-card").assertDoesNotExist()
            compose.runOnIdle { showEditor.value = true }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("task-goal-input").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("task-goal-input").performTextInput("Inspect the build")
            compose.onNodeWithTag("task-save-button").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("chat-task-card").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Inspect the build").assertIsDisplayed()
            capture("pocket-task-opt-in")
            compose.onNodeWithContentDescription(context.getString(me.rerere.rikkahub.R.string.task_close)).performClick()
            compose.onNodeWithTag("task-close-confirm").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("chat-task-card").fetchSemanticsNodes().isEmpty() }
            kotlinx.coroutines.runBlocking {
                assertFalse(store.brief(conversation.id.toString()).isActive)
                assertEquals("Inspect the build", store.brief(conversation.id.toString()).goal)
            }
            compose.runOnIdle { showEditor.value = true }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("task-goal-input").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Inspect the build").assertIsDisplayed()
        } finally { kotlinx.coroutines.runBlocking { repository.deleteConversation(conversation) } }
    }

    @Test fun childCardOpensNestedChatAndNewAvatarsDecode() {
        val child = Conversation.ofId(assistantId = kotlin.uuid.Uuid.random(), id = kotlin.uuid.Uuid.random(), newConversation = true).copy(title = "ThinkBro · Compare options")
        val stack = mutableListOf<NavKey>(Screen.Chat("parent", text = "Keep this entry"))
        val navigator = Navigator(stack)
        compose.setContent { CompositionLocalProvider(me.rerere.rikkahub.ui.context.LocalToaster provides com.dokar.sonner.rememberToasterState()) { RikkahubTheme {
            ChildChatCard(child, "RUNNING", me.rerere.rikkahub.data.datastore.createThinkbroAssistant()) { TextButton(onClick = { navigator.navigate(Screen.Chat(child.id.toString())) }) { Text("Open specialist") } }
        } } }
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
