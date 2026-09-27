package me.rerere.rikkahub.ui.components.message.tools

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import me.rerere.ai.ui.ToolHookNotice
import me.rerere.ai.ui.ToolHookNoticeStatus
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.hooks.HookRuntimeStore
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock

/** Stored transport status takes precedence, while old/imported message snapshots remain readable. */
internal fun mergeToolHookNotices(snapshot: List<ToolHookNotice>, current: List<ToolHookNotice>): List<ToolHookNotice> =
    linkedMapOf<String, ToolHookNotice>().apply {
        snapshot.forEach { put(it.id, it) }
        current.forEach { put(it.id, it) }
    }.values.toList()

internal data class ToolHookNoticeState(
    val notices: List<ToolHookNotice>,
    val unavailablePendingIds: Set<String> = emptySet(),
)

internal fun toolHookNoticeState(snapshot: List<ToolHookNotice>, current: List<ToolHookNotice>, loaded: Boolean): ToolHookNoticeState =
    ToolHookNoticeState(
        notices = mergeToolHookNotices(snapshot, current),
        unavailablePendingIds = if (loaded) snapshot.filter { old ->
            old.status == ToolHookNoticeStatus.PENDING && current.none { it.id == old.id }
        }.map { it.id }.toSet() else emptySet(),
    )

@Composable
internal fun rememberToolHookNotices(tool: UIMessagePart.Tool, conversationId: String?): ToolHookNoticeState {
    if (conversationId == null) return ToolHookNoticeState(tool.hookNotices,
        tool.hookNotices.filter { it.status == ToolHookNoticeStatus.PENDING }.map { it.id }.toSet())
    val context = LocalContext.current
    val store = remember(context) { HookRuntimeStore.at(context.filesDir) }
    val revision by store.revision.collectAsState()
    var loaded by remember(store, conversationId) { mutableStateOf(false) }
    LaunchedEffect(store, conversationId) {
        try { withContext(Dispatchers.IO) { store.ensureLoaded(conversationId) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* A readable message snapshot is the fallback for unavailable state. */ }
        finally { loaded = true }
    }
    return remember(tool.hookNotices, tool.toolCallId, conversationId, revision, loaded) {
        toolHookNoticeState(tool.hookNotices, store.noticesCached(conversationId, tool.toolCallId), loaded)
    }
}

/** Compact shared hook affordance: detailed instructions never crowd a tool's inline summary. */
@Composable
internal fun ToolHookNotices(notices: List<ToolHookNotice>, modifier: Modifier = Modifier, unavailablePendingIds: Set<String> = emptySet()) {
    if (notices.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Surface(
        onClick = { expanded = true },
        modifier = modifier.fillMaxWidth().testTag("tool-hook-notices"),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .65f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (notices.size == 1) stringResource(R.string.hook_event_fired, notices.first().name)
                else stringResource(R.string.hook_event_fired_count, notices.size),
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val statuses = notices.map { it.status }.distinct()
            Text(
                text = if (unavailablePendingIds.size == notices.size) stringResource(R.string.hook_event_unavailable)
                else if (statuses.size == 1 && unavailablePendingIds.isEmpty()) stringResource(statuses.first().label())
                else stringResource(R.string.hook_event_view_details),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (expanded) ModalBottomSheet(onDismissRequest = { expanded = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ToolHookNoticeDetails(notices, unavailablePendingIds)
    }
}

@Composable
internal fun ToolHookNoticeDetails(notices: List<ToolHookNotice>, unavailablePendingIds: Set<String> = emptySet()) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(.85f).testTag("tool-hook-details"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(stringResource(R.string.hook_event_details), style = MaterialTheme.typography.headlineSmall) }
        items(notices, key = { it.id }) { notice ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(notice.name, style = MaterialTheme.typography.titleMedium)
                    val unavailable = notice.id in unavailablePendingIds
                    Text(stringResource(if (unavailable) R.string.hook_event_unavailable else notice.status.label()), color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge)
                    val explanation = when {
                        unavailable -> R.string.hook_event_unavailable_hint
                        notice.status == ToolHookNoticeStatus.PENDING && notice.requestId != null -> R.string.hook_event_retry_hint
                        else -> notice.status.explanation()
                    }
                    Text(stringResource(explanation), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.hook_event_reason), style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(notice.reason, style = MaterialTheme.typography.bodyMedium) }
                    notice.source?.let { source ->
                        Text(stringResource(R.string.hook_event_source, source), fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    notice.requestId?.let { id ->
                        SelectionContainer { Text(stringResource(R.string.hook_event_request, id),
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (notice.content.isNotBlank()) ToolHookPromptPreview(notice.content)
                }
            }
        }
    }
}

@Composable
internal fun ToolHookPromptPreview(content: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.hook_event_instruction), style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f).padding(top = 12.dp))
        TextButton(onClick = { scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Hook", content)))
        } }) { Text(stringResource(R.string.code_block_copy)) }
    }
    // The resolver bounds instruction size. Code fences are displayed by the existing
    // Markdown renderer; previewing an instruction never invokes a tool or a script.
    SelectionContainer { MarkdownBlock(content, style = MaterialTheme.typography.bodyMedium) }
}

private fun ToolHookNoticeStatus.label(): Int = when (this) {
    ToolHookNoticeStatus.PENDING -> R.string.hook_event_pending
    ToolHookNoticeStatus.DISPATCHED -> R.string.hook_event_dispatched
    ToolHookNoticeStatus.SKIPPED -> R.string.hook_event_skipped
}
private fun ToolHookNoticeStatus.explanation(): Int = when (this) {
    ToolHookNoticeStatus.PENDING -> R.string.hook_event_pending_hint
    ToolHookNoticeStatus.DISPATCHED -> R.string.hook_event_dispatched_hint
    ToolHookNoticeStatus.SKIPPED -> R.string.hook_event_skipped_hint
}
