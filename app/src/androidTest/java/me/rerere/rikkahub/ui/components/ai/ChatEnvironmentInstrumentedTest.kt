package me.rerere.rikkahub.ui.components.ai

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantReadiness
import me.rerere.rikkahub.data.model.ChatEnvironmentSnapshot
import me.rerere.rikkahub.data.repository.EffectiveChatEnvironment
import me.rerere.rikkahub.data.repository.PocketProject
import me.rerere.rikkahub.data.repository.WorkspaceSource
import me.rerere.rikkahub.ui.pages.setting.WebAuthenticationSwitch
import me.rerere.rikkahub.ui.theme.findPresetTheme
import me.rerere.rikkahub.web.WebServerState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.uuid.Uuid

class ChatEnvironmentInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun russianEnvironmentShowsInheritedWorkspaceAndExplainsUnavailableTools() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("ru"))
        })
        val assistant = Assistant(name = "DevBro", workspaceId = Uuid.random(), readOnlyTools = true)
        val project = PocketProject(name = "Pocket · Android", workspaceId = assistant.workspaceId.toString())
        val snapshot = ChatEnvironmentSnapshot(
            EffectiveChatEnvironment(assistant, WorkspaceSource.PROJECT, project, null, true),
            AssistantReadiness("Review model", "Android workspace", true, false, true, true, 1, emptySet(), emptyList()),
            "/data/data/com.termux/files/home/pocket/app",
            listOf("read_project_reference", "termux_read_file"),
            listOf("termux_run_command"), listOf("skill_write_file"), listOf("android-review"), false,
        )
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration,
                LocalResources provides localized.resources) {
                MaterialTheme(colorScheme = findPresetTheme("rebro-blue").getColorScheme(true)) { Surface {
                    Column(Modifier.width(360.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(localized.getString(R.string.chat_environment_title), style = MaterialTheme.typography.titleLarge)
                        ChatEnvironmentDetails(snapshot)
                    }
                } }
            }
        }
        compose.onNodeWithText("Источник workspace: проект").assertIsDisplayed()
        compose.onNodeWithText("Проект: Pocket · Android").assertIsDisplayed()
        compose.onNodeWithText(localized.getString(R.string.chat_environment_cwd, snapshot.workingDirectory)).assertIsDisplayed()
        compose.onNodeWithText("Доступные инструменты: 2", substring = true).performClick()
        compose.onNodeWithText("read_project_reference\ntermux_read_file").assertIsDisplayed()
        capture("pocket-chat-environment-ru")
        compose.onNodeWithText(localized.getString(R.string.chat_environment_excluded), substring = true).performScrollTo().performClick()
        compose.onNodeWithText("termux_run_command").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(localized.getString(R.string.chat_environment_skills_blocked)).performScrollTo().assertIsDisplayed()
    }

    @Test fun runningAndStartingServerKeepEffectiveAuthLockedUntilStopped() {
        val state = mutableStateOf(WebServerState(isRunning = true, jwtEnabled = false))
        var changes = 0
        compose.setContent {
            MaterialTheme { Surface {
                // Deliberate difference: an imported setting must not claim that the live server is protected.
                WebAuthenticationSwitch(state.value, configuredEnabled = true, passwordConfigured = true) { changes++ }
            } }
        }
        compose.onNodeWithTag("web-auth-switch").assertIsNotEnabled().assertIsOff().performClick()
        compose.runOnIdle { assertEquals(0, changes); state.value = WebServerState(isLoading = true, jwtEnabled = true) }
        compose.onNodeWithTag("web-auth-switch").assertIsNotEnabled().assertIsOn().performClick()
        compose.runOnIdle { assertEquals(0, changes); state.value = WebServerState() }
        compose.onNodeWithTag("web-auth-switch").assertIsEnabled().assertIsOn().performClick()
        compose.runOnIdle { assertEquals(1, changes) }
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "trajectory-qa/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
