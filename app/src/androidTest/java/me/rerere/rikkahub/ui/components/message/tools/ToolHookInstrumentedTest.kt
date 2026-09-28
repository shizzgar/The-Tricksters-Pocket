package me.rerere.rikkahub.ui.components.message.tools

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolHookNotice
import me.rerere.ai.ui.ToolHookNoticeStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.hooks.HookRuntimeStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ToolHook
import me.rerere.rikkahub.data.model.ToolHookAction
import me.rerere.rikkahub.data.model.ToolHookCondition
import me.rerere.rikkahub.data.model.ToolHookScope
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

class ToolHookInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun capture(tag: String, name: String) {
        File(context.filesDir, "trajectory-qa/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Composable private fun Content(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalSettings provides Settings(), LocalNavController provides Navigator(mutableListOf())) {
            RikkahubTheme { Surface { content() } }
        }
    }

    @Test fun clickingHookShowsMarkdownSnapshotAndLiveDispatchStatus() {
        val pending = ToolHookNotice("notice", "rule", "JADX help", "Executable matches: jadx · NONZERO_EXIT",
            "# Read JADX help\n\nCheck the supported options before retrying.\n\n```sh\njadx --help\n```", "JADX/SKILL.md#Usage")
        var notices by mutableStateOf(listOf(pending))
        compose.setContent { Content { ToolHookNotices(notices) } }
        compose.onNodeWithText(context.getString(R.string.hook_event_pending)).assertIsDisplayed()
        compose.onNodeWithTag("tool-hook-notices").performClick()
        compose.onNodeWithTag("tool-hook-details").assertIsDisplayed()
        compose.onNodeWithText("Read JADX help", substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.hook_event_source, pending.source)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.hook_event_pending_hint)).assertIsDisplayed()
        capture("tool-hook-details", "pocket-hook-details")
        compose.runOnUiThread { notices = listOf(pending.copy(status = ToolHookNoticeStatus.DISPATCHED, requestId = "request-7")) }
        compose.onNodeWithText(context.getString(R.string.hook_event_dispatched_hint)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.hook_event_request, "request-7")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.hook_event_pending_hint)).assertDoesNotExist()
    }

    @Test fun skippedInstructionExplainsFailureWithoutDisplayingAnEmptyPrompt() {
        val notice = ToolHookNotice("skipped", "rule", "Missing skill", "Selected skill is no longer installed", "",
            status = ToolHookNoticeStatus.SKIPPED)
        compose.setContent { Content { ToolHookNoticeDetails(listOf(notice)) } }
        compose.onNodeWithText(context.getString(R.string.hook_event_skipped)).assertIsDisplayed()
        compose.onNodeWithText(notice.reason).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.hook_event_instruction)).assertDoesNotExist()
    }

    @Test fun anUnfinishedAttemptStaysQueuedWithoutClaimingItWasNeverSent() {
        val notice = ToolHookNotice("pending", "rule", "JADX help", "Original reason", "Original instruction", requestId = "unfinished-1")
        compose.setContent { Content { ToolHookNoticeDetails(listOf(notice)) } }
        compose.onNodeWithText(context.getString(R.string.hook_event_pending)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.hook_event_retry_hint)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.hook_event_pending_hint)).assertDoesNotExist()
    }

    @Test fun restoredPendingSnapshotDoesNotClaimDeliveryIsQueued() {
        val notice = ToolHookNotice("restored", "rule", "JADX help", "Original reason", "Original instruction")
        compose.setContent { Content { ToolHookNoticeDetails(listOf(notice), setOf(notice.id)) } }
        compose.onNodeWithText(context.getString(R.string.hook_event_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.hook_event_pending)).assertDoesNotExist()
        compose.onNodeWithText("Original instruction").assertIsDisplayed()
    }

    @Test fun historicalCallPickerMatchesErrorAndRejectsSuccessWithoutRunningOrQueuingAnything() {
        val koin = org.koin.core.context.GlobalContext.get()
        val repository = koin.get<ConversationRepository>()
        val settings = koin.get<SettingsStore>().settingsFlow.value
        val id = Uuid.random()
        fun call(callId: String, exit: Int) = UIMessagePart.Tool(callId, "termux_run_command",
            """{"command":"jadx --bad sample.apk"}""",
            output = listOf(UIMessagePart.Text("""{"success":true,"exit_code":$exit,"stderr":"unknown option --bad"}""")),
            executionStartedAt = 1L)
        val success = call("success", 0)
        val failure = call("failure", 2)
        val conversation = Conversation.ofId(id, settings.assistantId).copy(title = "Hook history preview")
            .updateCurrentMessages(listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(success, failure))))
        val rule = ToolHook(name = "JADX help", enabled = false, scope = ToolHookScope(global = true),
            condition = ToolHookCondition(toolNames = setOf("termux_run_command"), commandExecutable = "jadx", stderrContains = "unknown option"),
            action = ToolHookAction(prompt = "# JADX usage\n\nRead the command help before retrying."))
        runBlocking { repository.insertConversation(conversation) }
        try {
            compose.setContent { Content { ToolHookDryRunSheet(rule, {}, id) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("hook-dry-run-call").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("tool-hook-dry-run").performScrollToNode(hasTestTag("tool-hook-dry-run-result"))
            compose.onNodeWithText(context.getString(R.string.hook_dry_run_match)).assertIsDisplayed()
            compose.onNodeWithText("JADX usage", substring = true).assertExists()
            capture("tool-hook-dry-run-result", "pocket-hook-dry-run")
            compose.onNodeWithTag("tool-hook-dry-run").performScrollToNode(hasTestTag("hook-dry-run-call"))
            compose.onNodeWithTag("hook-dry-run-call").performClick()
            compose.onNodeWithTag("hook-dry-run-call-success:0").performClick()
            compose.onNodeWithTag("tool-hook-dry-run").performScrollToNode(hasTestTag("tool-hook-dry-run-result"))
            compose.onNodeWithText(context.getString(R.string.hook_dry_run_no_match)).assertIsDisplayed()
            compose.onNodeWithText("JADX usage", substring = true).assertDoesNotExist()
            val persisted = runBlocking { repository.getConversationById(id) }!!
            val persistedCalls = persisted.currentMessages.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>()
            assertEquals(listOf(success, failure), persistedCalls)
            assertTrue(HookRuntimeStore.at(context.filesDir).pendingPromptCached(id.toString()).isEmpty())
            assertFalse(rule.enabled)
        } finally { runBlocking { repository.deleteConversation(conversation) } }
    }

    @Test fun pendingInstructionCanBeDiscardedIndividually() {
        val first = ToolHookNotice("first", "rule", "First help", "Exit 2", "First prompt")
        val second = first.copy(id = "second", name = "Second help")
        var notices by mutableStateOf(listOf(first, second))
        compose.setContent { Content {
            ToolHookNoticeDetails(notices, onDiscardPending = { id ->
                notices = notices.map { if (it.id == id) it.copy(status = ToolHookNoticeStatus.SKIPPED) else it }
            })
        } }
        compose.onNodeWithTag("hook-discard-first").performScrollTo().performClick()
        compose.onNodeWithTag("hook-discard-first").assertDoesNotExist()
        compose.onNodeWithTag("hook-discard-second").assertExists()
    }

}
