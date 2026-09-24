package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.subagent.defaultBroSubAgents
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.pages.setting.SettingSubAgentsPage
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File

class TermuxWorkspaceUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun capture(name: String, dialog: Boolean = false) {
        val file = File(context.filesDir, "trajectory-qa/$name.png").apply { parentFile!!.mkdirs() }
        (if (dialog) compose.onNode(isDialog()) else compose.onRoot()).captureToImage().asAndroidBitmap().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun termuxChoiceExplainsOriginalFilesAndConnection() {
        compose.setContent { RikkahubTheme(colorMode = ColorMode.DARK) { CreateWorkspaceDialog(emptySet()) {} } }
        compose.onNodeWithText("Termux").performClick()
        compose.onNodeWithText(context.getString(R.string.workspace_termux_link_hint)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.workspace_termux_directory)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.common_save)).assertIsNotEnabled()
        capture("termux-workspace-create", dialog = true)
    }

    @Test fun savedBroLinksAreVisibleInSubAgentSettings() {
        val koin = GlobalContext.get()
        val store = koin.get<SettingsStore>()
        val before = runBlocking { withTimeout(15_000) { store.settingsFlow.first { !it.init } } }
        val vm = SettingVM(store, koin.get<McpManager>(), koin.get<AppScope>())
        runBlocking { store.update { it.copy(subAgents = defaultBroSubAgents(), broSubAgentsSeeded = true) } }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalNavController provides Navigator(mutableListOf())) {
                    RikkahubTheme(colorMode = ColorMode.DARK) { SettingSubAgentsPage(vm) }
                }
            }
            compose.onNodeWithText("ReBro").assertIsDisplayed()
            compose.onNodeWithText("NetBro").assertIsDisplayed()
            capture("bro-subagent-profiles")
        } finally {
            runBlocking { store.update { before } }
        }
    }
}
