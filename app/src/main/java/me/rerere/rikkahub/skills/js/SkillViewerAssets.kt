package me.rerere.rikkahub.skills.js

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI

/** Only the selected skill's subtree is mounted; no app-private file URL is readable. */
class SkillViewerAssets(context: Context, private val root: File) {
    private val host = JsSkillRunner.skillOriginHost(root.name)
    private val loader = WebViewAssetLoader.Builder()
        .setDomain(host)
        .addPathHandler("/skill/", WebViewAssetLoader.InternalStoragePathHandler(context, root))
        .build()

    fun initialUrl(url: String): String =
        resolveSkillWebviewUrl(url, File(root, "index.html"), root) ?: "about:blank"

    fun intercept(url: Uri): WebResourceResponse? {
        if (url.scheme.equals("file", true) || url.scheme.equals("content", true)) return denied()
        if (url.host?.endsWith(".appassets.androidplatform.net", true) == true ||
            url.host.equals("appassets.androidplatform.net", true)) {
            // Do not let missing/traversing paths or another skill's virtual origin fall
            // through to the network (and never install another skill's handler here).
            return loader.shouldInterceptRequest(url) ?: denied()
        }
        return null
    }

    private fun denied() = WebResourceResponse(
        "text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)),
    )
}

/** Pure URL conversion shared by tool output and legacy file:// viewer cards. */
internal fun resolveSkillWebviewUrl(url: String, scriptFile: File, skillDir: File): String? {
    val raw = url.trim()
    if (raw.isEmpty()) return null
    val uri = runCatching { URI(raw.replace(" ", "%20")) }.getOrNull() ?: return null
    when (uri.scheme?.lowercase()) {
        "https", "http", "data" -> return raw
        null, "file" -> Unit
        else -> return null
    }
    // Network-path references and file authorities are not local skill assets.
    if (uri.rawAuthority != null) return null
    val local = if (uri.scheme == null) scriptFile.toURI().resolve(uri) else uri
    val target = runCatching { File(local.path).canonicalFile }.getOrNull() ?: return null
    val root = runCatching { skillDir.canonicalFile }.getOrNull() ?: return null
    if (!target.path.startsWith(root.path + File.separator)) return null
    val relative = target.relativeTo(root).invariantSeparatorsPath
    return URI("https", JsSkillRunner.skillOriginHost(root.name), "/skill/$relative", local.query, local.fragment).toASCIIString()
}
