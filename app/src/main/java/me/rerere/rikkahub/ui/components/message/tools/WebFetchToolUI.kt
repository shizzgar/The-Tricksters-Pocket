package me.rerere.rikkahub.ui.components.message.tools

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.openUrl

internal val WebFetchToolUIs: List<ToolUIRenderer> = listOf("web_fetch", "web_extract").map(::WebFetchToolUI)

private class WebFetchToolUI(override val toolName: String) : ToolUIRenderer {
    override fun icon(context: ToolUIContext) = HugeIcons.Tools
    override fun hasSummary(context: ToolUIContext) = true
    @Composable override fun title(context: ToolUIContext) = stringResource(R.string.web_fetch_title)

    private fun view(context: ToolUIContext) = presentWebFetch(toolName, context.arguments, context.content,
        context.loading, context.tool.executionStartedAt != null, context.tool.output.isNotEmpty(),
        denied = context.tool.approvalState is ToolApprovalState.Denied && !context.tool.isExecuted,
        pending = context.tool.isPending)

    @Composable override fun Summary(context: ToolUIContext) {
        val view = remember(context) { view(context) }
        Text(stringResource(view.state.label()) + (view.status?.let { " · HTTP $it" } ?: ""),
            style = MaterialTheme.typography.labelMedium,
            color = if (view.state.isFailure()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        view.result.webText("title")?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (view.truncated) Text(stringResource(R.string.web_fetch_partial), style = MaterialTheme.typography.bodySmall)
    }

    @Composable override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val view = remember(context) { view(context) }
        val result = view.result
        var technical by remember(context.tool.toolCallId) { mutableStateOf(false) }
        var headers by remember(context.tool.toolCallId) { mutableStateOf(false) }
        val links = result?.get("links") as? JsonArray
        LazyColumn(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.web_fetch_title), style = MaterialTheme.typography.titleLarge)
                        Text("${view.method} · ${view.mode}", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.compression_close)) }
                }
            }
            item {
                Surface(shape = MaterialTheme.shapes.large, color = if (view.state.isFailure()) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(view.state.label()), style = MaterialTheme.typography.titleMedium)
                        view.status?.let { Text("HTTP $it", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace) }
                        if (view.state == WebFetchState.RUNNING) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        result.webText("error")?.let { WebField(stringResource(R.string.termux_preview_error), it) }
                        result.webText("detail")?.let { SelectionContainer { Text(it, softWrap = true) } }
                    }
                }
            }
            view.url?.let { url -> item {
                WebUrl(stringResource(R.string.web_fetch_address), url)
                if (view.requestedUrl != url) view.requestedUrl?.let {
                    WebField(stringResource(R.string.web_fetch_requested_address), it)
                }
            } }
            if (view.truncated || view.nextIndex != null || result.webText("recovery") != null || result.webText("pagination_note") != null) item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (view.truncated) Text(stringResource(R.string.web_fetch_partial), style = MaterialTheme.typography.titleSmall)
                        if (view.sourceTruncated) Text(stringResource(R.string.web_fetch_source_cut), style = MaterialTheme.typography.bodySmall)
                        view.nextIndex?.let { Text(stringResource(R.string.web_fetch_next_page, it), style = MaterialTheme.typography.bodyMedium) }
                        result.webText("pagination_note")?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        result.webText("recovery")?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (result != null) item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        result.webText("title")?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
                        result.webText("description")?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        listOf("site_name", "language", "content_type", "start_index", "returned_chars").forEach { key ->
                            result.webText(key)?.let { WebField(fieldLabel(key), it) }
                        }
                        if (view.body == null && links == null) Text(stringResource(R.string.web_fetch_no_body), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            view.body?.let { body -> item {
                WebContent(stringResource(if (view.mode == "raw") R.string.web_fetch_response_body else R.string.web_fetch_readable_text),
                    body, code = view.mode == "raw")
            } }
            if (links != null) {
                item { Text(stringResource(R.string.web_fetch_links, links.size), style = MaterialTheme.typography.titleMedium) }
                links.forEach { link -> item {
                    val obj = link as? JsonObject
                    val href = obj.webText("href")
                    if (href != null) WebUrl(obj.webText("text")?.takeIf { it.isNotBlank() } ?: stringResource(R.string.web_fetch_link), href)
                    else WebContent(stringResource(R.string.web_fetch_link), link.toString(), code = true)
                } }
            }
            (result?.get("headers") as? JsonObject)?.let { responseHeaders ->
                item { TextButton(onClick = { headers = !headers }) { Text(stringResource(R.string.web_fetch_headers, responseHeaders.size)) } }
                if (headers) responseHeaders.forEach { (name, value) -> item { WebField(name, value.asText()) } }
            }
            // Preserve malformed/legacy data and extra output parts instead of silently discarding them.
            context.tool.output.filterIsInstance<UIMessagePart.Text>().forEachIndexed { index, part ->
                if (result == null || index > 0) item { WebContent(stringResource(R.string.web_fetch_response_body), part.text, code = true) }
            }
            item { TextButton(onClick = { technical = !technical }) {
                Text(stringResource(if (technical) R.string.compression_hide_metadata else R.string.compression_show_metadata))
            } }
            if (technical) {
                item { WebContent(stringResource(R.string.web_fetch_request), context.tool.input, code = true) }
                context.tool.output.filterIsInstance<UIMessagePart.Text>().forEach { part ->
                    item { WebContent(stringResource(R.string.web_fetch_raw_result), part.text, code = true) }
                }
            }
        }
    }
}

@Composable
private fun WebUrl(label: String, url: String) {
    val context = LocalContext.current
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            WebField(label, url)
            webHttpUrl(url)?.let { safeUrl ->
                TextButton(onClick = { context.openUrl(safeUrl) }) { Text(stringResource(R.string.web_fetch_open)) }
            }
        }
    }
}

@Composable
private fun WebField(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, modifier = Modifier.fillMaxWidth(), softWrap = true, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun WebContent(label: String, original: String, code: Boolean) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var shown by remember(original) { mutableIntStateOf(12000) }
    val display = remember(original, code) { if (code) formatWebFetchBody(original) else original }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, original))) } }) {
                    Text(stringResource(R.string.code_block_copy))
                }
            }
            // Untrusted HTML is displayed as text, never executed in a WebView or Markdown HTML renderer.
            SelectionContainer { Text(if (display.isEmpty()) stringResource(R.string.web_fetch_empty) else display.take(shown),
                modifier = Modifier.fillMaxWidth(), softWrap = true,
                style = if (code) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr)
                    else MaterialTheme.typography.bodyMedium) }
            if (shown < display.length) TextButton(onClick = { shown += 12000 }) {
                Text(stringResource(R.string.termux_preview_more, display.length - shown))
            }
        }
    }
}

private fun JsonElement.asText() = (this as? JsonPrimitive)?.contentOrNull ?: toString()
private fun WebFetchState.isFailure() = this in setOf(WebFetchState.HTTP_ERROR, WebFetchState.FAILED, WebFetchState.DENIED)
private fun WebFetchState.label() = when (this) {
    WebFetchState.PENDING -> R.string.termux_preview_pending
    WebFetchState.RUNNING -> R.string.web_fetch_running
    WebFetchState.APPROVAL -> R.string.termux_preview_approval
    WebFetchState.DENIED -> R.string.termux_preview_denied
    WebFetchState.RECEIVED -> R.string.web_fetch_received
    WebFetchState.HTTP_ERROR -> R.string.web_fetch_http_error
    WebFetchState.FAILED -> R.string.web_fetch_failed
    WebFetchState.UNKNOWN -> R.string.compression_unknown
}
@Composable private fun fieldLabel(key: String) = stringResource(when (key) {
    "site_name" -> R.string.web_fetch_site
    "language" -> R.string.web_fetch_language
    "content_type" -> R.string.web_fetch_content_type
    "start_index" -> R.string.web_fetch_offset
    else -> R.string.web_fetch_chars
})
