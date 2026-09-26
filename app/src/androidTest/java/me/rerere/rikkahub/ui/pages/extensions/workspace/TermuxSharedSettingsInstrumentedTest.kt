package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.preferences.TermuxPreferences
import me.rerere.rikkahub.data.preferences.TermuxRuntime
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.pages.setting.termux.SettingTermuxViewModel
import me.rerere.rikkahub.ui.pages.setting.termux.TermuxRuntimeSettings
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File

class TermuxSharedSettingsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun workspaceEditsPersistAndAppearInStandaloneSettingsWithoutChangingItsDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = GlobalContext.get()
        val preferences = koin.get<TermuxPreferences>()
        val before = runBlocking { preferences.snapshot() }
        val vm = koin.get<SettingTermuxViewModel>()
        var workspaceMode by mutableStateOf(true)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalNavController provides Navigator(mutableListOf())) {
                    RikkahubTheme(colorMode = ColorMode.DARK) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                            if (workspaceMode) WorkspaceTermuxSettings("/data/data/com.termux/files/home/project", vm)
                            else TermuxRuntimeSettings(vm)
                        }
                    }
                }
            }
            compose.onNodeWithText(context.getString(R.string.setting_termux_working_dir)).assertDoesNotExist()
            compose.onNodeWithTag("termux-command-timeout").performScrollTo().performTextReplacement("137")
            compose.onNodeWithTag("termux-command-timeout").performImeAction()
            compose.waitUntil(5_000) {
                runBlocking { preferences.snapshot().commandTimeoutMs } == 137_000L &&
                    TermuxRuntime.commandTimeoutMs == 137_000L
            }
            assertEquals(before.defaultWorkingDir, runBlocking { preferences.snapshot().defaultWorkingDir })
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            File(context.filesDir, "trajectory-qa/termux-workspace-shared-settings.png").apply {
                parentFile!!.mkdirs()
                outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            compose.runOnIdle { workspaceMode = false }
            compose.onNodeWithTag("termux-command-timeout").performScrollTo().assertTextContains("137")
            compose.onNodeWithText(context.getString(R.string.setting_termux_working_dir)).assertExists()
        } finally {
            runBlocking { preferences.setCommandTimeoutMs(before.commandTimeoutMs) }
        }
    }
}
