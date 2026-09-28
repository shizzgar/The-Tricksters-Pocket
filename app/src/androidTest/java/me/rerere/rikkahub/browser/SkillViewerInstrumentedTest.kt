package me.rerere.rikkahub.browser

import android.webkit.WebView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.skills.js.JsSkillRunner
import me.rerere.rikkahub.skills.js.SkillViewerAssets
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Loads the production BrowserView, not a test-only WebViewClient. */
class SkillViewerInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun viewerReadsItsAssetsButCannotReadPrivateFilesOrAnotherSkill() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.filesDir, "viewer-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val root = File(fixture, "one").apply { mkdirs() }
        val other = File(fixture, "two").apply { mkdirs() }
        val secret = File(fixture, "secret.txt").apply { writeText("PRIVATE_SENTINEL") }
        File(root, "public.txt").writeText("PUBLIC_ASSET")
        File(other, "secret.txt").writeText("OTHER_SKILL_SENTINEL")
        val result = AtomicReference<String>()
        val done = CountDownLatch(1)
        var viewer: WebView? = null
        val otherUrl = "https://${JsSkillRunner.skillOriginHost(other.name)}/skill/secret.txt"
        val page = File(root, "ui.html").apply { writeText("""
            <!doctype html><html><body>Viewer isolation fixture<script>
            (async () => {
              async function read(url) {
                try { return await Promise.race([
                  fetch(url).then(r => r.text()),
                  new Promise(resolve => setTimeout(() => resolve('timed out'), 3000))
                ]); } catch (_) { return 'blocked'; }
              }
              const own = await read('public.txt');
              const privateFile = await read(${JSONObject.quote(secret.toURI().toString())});
              const traversal = await read('/skill/%2e%2e/secret.txt');
              const otherSkill = await read(${JSONObject.quote(otherUrl)});
              document.title = JSON.stringify({own, privateFile, traversal, otherSkill});
            })();
            </script></body></html>
        """.trimIndent()) }
        val assets = SkillViewerAssets(context, root)
        try {
            compose.setContent { RikkahubTheme {
                BrowserView(
                    onWebViewReady = { viewer = it }, onUrlChange = {},
                    onTitleChange = { if (it.startsWith("{\"own\":")) { result.set(it); done.countDown() } },
                    onLoadProgress = {}, onCanGoBackChange = {}, onCanGoForwardChange = {},
                    canGoBackState = mutableStateOf(false), canGoForwardState = mutableStateOf(false),
                    currentUrlState = mutableStateOf(""), currentTitleState = mutableStateOf(""), loadProgressState = mutableStateOf(0),
                    onClose = {}, onBackTap = {}, onForwardTap = {}, onRefreshTap = {}, onStopAi = {}, onNavigate = {},
                    // Covers old saved cards as well as new virtual HTTPS output.
                    initialUrl = page.toURI().toString(), conversationId = null, skillAssets = assets,
                )
            } }
            assertTrue("Viewer did not finish the isolation fixture", done.await(15, TimeUnit.SECONDS))
            val observed = JSONObject(result.get())
            assertEquals("PUBLIC_ASSET", observed.getString("own"))
            assertFalse(observed.toString().contains("PRIVATE_SENTINEL"))
            assertFalse(observed.toString().contains("OTHER_SKILL_SENTINEL"))
            compose.runOnIdle {
                assertFalse(viewer!!.settings.allowFileAccess)
                assertFalse(viewer!!.settings.allowFileAccessFromFileURLs)
                assertFalse(viewer!!.settings.allowUniversalAccessFromFileURLs)
                assertTrue(viewer!!.url!!.startsWith("https://${JsSkillRunner.skillOriginHost(root.name)}/skill/"))
            }
        } finally {
            compose.runOnIdle { viewer?.stopLoading(); viewer?.destroy() }
            fixture.deleteRecursively()
        }
    }
}
