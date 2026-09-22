package me.rerere.rikkahub.ui.pages.extensions.skills

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.skills.TermuxSkillBridge
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

abstract class WorkbenchFixture {
    @get:Rule val compose = createComposeRule()
    protected val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    protected lateinit var vm: SkillDetailVM
    protected lateinit var root: File
    protected fun show(russian: Boolean = false) {
        val manager = GlobalContext.get().get<SkillManager>()
        val name = "workbench-ui-fixture"
        check(manager.saveSkillFileBytesAtomically(name, mapOf(
            "SKILL.md" to "---\nname: $name\ndescription: Report generator with scripts and assets\n---\n# Reports\nUse scripts/report.py to build a report.\n".toByteArray(),
            "scripts/report.py" to "from pathlib import Path\n\nROOT = Path(__file__).parent.parent\ndata = (ROOT / 'assets/data.bin').read_bytes()\nprint(f'Read {len(data)} bytes')\n".toByteArray(),
            "assets/data.bin" to byteArrayOf(0, -1, -128, 10, 65, 66),
            "references/format.md" to "# Output format\n\nSave the report as a PDF.\n".toByteArray(),
            "README.md" to "# Report toolkit\n\nInstructions, scripts and resources in one package.\n".toByteArray(),
        )))
        File(context.filesDir, "skill_workbench/$name").deleteRecursively()
        root = requireNotNull(manager.getSkillDir(name))
        vm = SkillDetailVM(context, manager, GlobalContext.get().get<TermuxSkillBridge>())
        val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(if (russian) "ru" else "en")) })
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration, LocalResources provides localized.resources) {
                MaterialTheme(colorScheme = if (russian) darkColorScheme() else lightColorScheme()) { SkillWorkbenchScreen(vm, {}, {}) }
            }
        }
        compose.runOnUiThread { vm.init(name) }
        compose.waitUntil(15_000) { vm.state.value.snapshot != null && !vm.state.value.busy }
    }
    protected fun shot(name: String) {
        val output = File(context.filesDir, "trajectory-qa").apply { mkdirs() }.resolve("$name.png")
        compose.onNodeWithTag("skill-workbench").captureToImage().asAndroidBitmap().let { bitmap -> output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    protected fun ready() { compose.waitUntil(15_000) { !vm.state.value.busy } }
}

class SkillWorkbenchInstrumentedTest : WorkbenchFixture() {
    @Test fun browseEditSaveScriptKeepsBinaryResourceIntact() {
        show()
        shot("skill-workbench-files")
        compose.onNodeWithText("scripts").performClick()
        compose.onNodeWithText("report.py").performClick()
        compose.waitUntil(10_000) { vm.state.value.editor != null && !vm.state.value.busy }
        shot("skill-workbench-editor")
        val replacement = "print('edited in the app')\n"
        compose.onNodeWithTag("skill-code-input").performTextReplacement(replacement)
        compose.onNodeWithTag("skill-save").performClick()
        ready()
        assertEquals(replacement, root.resolve("scripts/report.py").readText())
        assertArrayEquals(byteArrayOf(0, -1, -128, 10, 65, 66), root.resolve("assets/data.bin").readBytes())
        assertTrue(vm.state.value.snapshot?.canRestore == true)
        assertFalse(vm.state.value.editor?.dirty == true)
    }
    @Test fun unsavedCloseIsGuardedAndRecoveredDraftCannotOverwriteAnExternalEdit() {
        show()
        compose.onNodeWithText("scripts").performClick()
        compose.onNodeWithText("report.py").performClick()
        compose.waitUntil(10_000) { vm.state.value.editor != null && !vm.state.value.busy }
        val baseline = root.resolve("scripts/report.py").readBytes()
        compose.onNodeWithTag("skill-code-input").performTextReplacement("print('my draft')\n")
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Save changes?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(10_000) { File(context.filesDir, "skill_workbench/workbench-ui-fixture/draft.json").isFile }
        val manager = GlobalContext.get().get<SkillManager>()
        check(manager.saveSkillFile("workbench-ui-fixture", "scripts/report.py", "print('agent change')\n"))
        val restored = SkillDetailVM(context, manager, GlobalContext.get().get<TermuxSkillBridge>())
        compose.runOnUiThread { restored.init("workbench-ui-fixture") }
        compose.waitUntil(10_000) { !restored.state.value.busy && restored.state.value.editor != null }
        assertEquals("print('my draft')\n", restored.state.value.editor?.value?.text)
        assertArrayEquals(baseline, restored.state.value.editor?.document?.bytes)
        compose.runOnUiThread { restored.save() }
        compose.waitUntil(10_000) { !restored.state.value.busy }
        assertEquals("print('agent change')\n", root.resolve("scripts/report.py").readText())
        assertTrue(restored.state.value.editor?.dirty == true)
    }
    @Test fun russianBinaryViewerHexEditAndUndoPreserveBytes() {
        show(russian = true)
        compose.onNodeWithText("assets").performClick()
        compose.onNodeWithText("data.bin").performClick()
        compose.waitUntil(10_000) { vm.state.value.editor != null && !vm.state.value.busy }
        compose.onNodeWithText("Бинарный файл").assertIsDisplayed()
        shot("skill-workbench-binary-ru")
        compose.onNodeWithText("Редактировать байты в HEX").performClick()
        compose.onNodeWithTag("skill-code-input").performTextReplacement("00 FF 80 0A 43 44")
        compose.onNodeWithContentDescription("Отменить правку").performClick()
        assertEquals("00 FF 80 0A 41 42", vm.state.value.editor?.value?.text)
        compose.onNodeWithContentDescription("Повторить правку").performClick()
        compose.onNodeWithTag("skill-save").performClick()
        ready()
        assertArrayEquals(byteArrayOf(0, -1, -128, 10, 67, 68), root.resolve("assets/data.bin").readBytes())
    }
}

class SkillWorkbenchWideInstrumentedTest : WorkbenchFixture() {
    @Test fun filesAndEditorRemainVisibleTogether() {
        show()
        compose.onNodeWithText("scripts").performClick()
        compose.onNodeWithText("report.py").performClick()
        compose.waitUntil(10_000) { vm.state.value.editor != null && !vm.state.value.busy }
        compose.onNodeWithTag("skill-file-list").assertIsDisplayed()
        compose.onNodeWithTag("skill-editor").assertIsDisplayed()
        shot("skill-workbench-wide")
    }
}
