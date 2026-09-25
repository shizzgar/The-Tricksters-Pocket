package me.rerere.rikkahub.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesThemePage
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class RebroBrandInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun applyAndCapture(mode: ColorMode, name: String) {
        val koin = GlobalContext.get()
        val store = koin.get<SettingsStore>()
        val before = runBlocking { withTimeout(15_000) { store.settingsFlow.first { !it.init } } }
        val vm = SettingVM(store, koin.get<McpManager>(), koin.get<AppScope>())
        runBlocking { store.update { it.copy(dynamicColor = true, themeId = "ocean") } }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalNavController provides Navigator(mutableListOf())) {
                    RikkahubTheme(colorMode = mode) { SettingPreferencesThemePage(vm) }
                }
            }
            compose.waitUntil(10_000) { vm.settings.value.themeId == "ocean" }
            compose.onNodeWithText(context.getString(R.string.rebro_theme_apply)).performClick()
            compose.waitUntil(10_000) {
                store.settingsFlow.value.themeId == "rebro-blue" && !store.settingsFlow.value.dynamicColor
            }
            compose.waitForIdle()
            compose.onNodeWithText("ReBro Blue").assertIsDisplayed()
            val output = File(context.filesDir, "trajectory-qa/$name.png").apply { parentFile!!.mkdirs() }
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally {
            runBlocking { store.update { before } }
        }
    }

    @Test fun darkThemeCanBeAppliedFromSettings() = applyAndCapture(ColorMode.DARK, "rebro-theme-dark")
    @Test fun lightThemeCanBeAppliedFromSettings() = applyAndCapture(ColorMode.LIGHT, "rebro-theme-light")

    @Test fun installedLauncherAndBundledAvatarDecode() {
        val launcher = context.getDrawable(R.mipmap.ic_launcher)
        assertTrue(launcher is AdaptiveIconDrawable)
        assertNotNull((launcher as AdaptiveIconDrawable).foreground)
        context.assets.open("branding/rebro-avatar.webp").use { stream ->
            val avatar = requireNotNull(BitmapFactory.decodeStream(stream))
            assertNotNull(avatar)
            assertEquals(512, avatar.width)
            avatar.recycle()
        }
    }

    @Test fun netbroAvatarAndNotificationIconDecode() {
        context.assets.open("branding/netbro-spider.webp").use { stream ->
            val avatar = requireNotNull(BitmapFactory.decodeStream(stream))
            assertTrue(avatar.width >= 512)
            avatar.recycle()
        }
        context.assets.open("branding/orchbro-avatar.webp").use { assertNotNull(BitmapFactory.decodeStream(it)) }
        assertNotNull(context.getDrawable(R.drawable.small_icon))
        assertEquals("The Trickster's Pocket", context.getString(R.string.app_name))
    }
}
