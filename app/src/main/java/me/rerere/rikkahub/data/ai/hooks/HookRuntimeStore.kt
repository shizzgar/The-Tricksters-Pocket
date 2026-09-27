package me.rerere.rikkahub.data.ai.hooks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.ToolHookNotice
import me.rerere.ai.ui.ToolHookNoticeStatus
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

@Serializable
internal data class StoredHookDelivery(
    val turnId: String,
    val toolCallId: String,
    val eventKey: String,
    val priority: Int,
    val notice: ToolHookNotice,
    val requestConfirmed: Boolean = false,
)

@Serializable
internal data class HookRuntimeState(
    val deliveries: List<StoredHookDelivery> = emptyList(),
    val seen: Set<String> = emptySet(),
    val jobs: Map<String, ToolHookJobOrigin> = emptyMap(),
    val perTurnFirings: Map<String, Int> = emptyMap(),
)

data class HookPromptBatch(val ids: Set<String>, val text: String, val instructions: Map<String, String> = emptyMap()) {
    companion object { val EMPTY = HookPromptBatch(emptySet(), "") }
}

/**
 * Durable per-chat outbox. Prompt preparation is read-only. Only the request dispatch
 * checkpoint consumes instructions, so queue cancellation and compaction cannot lose them.
 * Cached accessors are safe for UI/context counters; disk reads belong on the IO dispatcher.
 */
class HookRuntimeStore private constructor(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cache = ConcurrentHashMap<String, HookRuntimeState>()
    private val deleted = mutableSetOf<String>()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    private fun file(id: String): File {
        require(runCatching { java.util.UUID.fromString(id) }.isSuccess) { "Invalid conversation ID" }
        return File(directory, "$id.json")
    }

    @Synchronized fun ensureLoaded(conversationId: String) {
        if (cache.containsKey(conversationId)) return
        val file = file(conversationId)
        val value = if (file.exists()) json.decodeFromString<HookRuntimeState>(file.readText()) else HookRuntimeState()
        // A killed process cannot prove that a dispatched request received any model
        // response. Restore only these uncertain instructions, retaining their identity.
        val recovered = value.copy(deliveries = value.deliveries.map { entry ->
            if (entry.notice.status == ToolHookNoticeStatus.DISPATCHED && !entry.requestConfirmed)
                entry.copy(notice = entry.notice.copy(status = ToolHookNoticeStatus.PENDING))
            else entry
        })
        if (recovered != value) save(conversationId, recovered) else {
            cache[conversationId] = value
            mutableRevision.value++
        }
    }

    private fun save(id: String, state: HookRuntimeState) {
        check(id !in deleted) { "Conversation was deleted" }
        directory.mkdirs()
        val target = file(id)
        val temporary = File.createTempFile("hooks-", ".tmp", directory)
        try {
            temporary.outputStream().use { output ->
                output.write(json.encodeToString(state).toByteArray())
                output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            cache[id] = state
            mutableRevision.value++
        } finally { temporary.delete() }
    }

    @Synchronized internal fun <T> transaction(id: String, action: (HookRuntimeState) -> Pair<HookRuntimeState, T>): T {
        ensureLoaded(id)
        val old = cache.getValue(id)
        val (changed, result) = action(old)
        // Full notices also live with their tool in conversation history. Keep bounded
        // recent copies here while retaining every dedupe key and pending instruction.
        val retainedIds = changed.deliveries.filter { it.notice.status != ToolHookNoticeStatus.PENDING }
            .takeLast(256).map { it.notice.id }.toSet()
        val next = changed.copy(deliveries = changed.deliveries.filter {
            it.notice.status == ToolHookNoticeStatus.PENDING ||
                (it.notice.status == ToolHookNoticeStatus.DISPATCHED && !it.requestConfirmed) || it.notice.id in retainedIds
        })
        if (next != old) save(id, next)
        return result
    }

    fun pendingPromptCached(conversationId: String, enabledRuleIds: Set<String>? = null): String =
        pendingBatchCached(conversationId, enabledRuleIds).text

    fun pendingBatchCached(conversationId: String, enabledRuleIds: Set<String>? = null): HookPromptBatch {
        val state = cache[conversationId] ?: return HookPromptBatch.EMPTY
        return batch(state, enabledRuleIds)
    }

    fun noticesCached(conversationId: String, toolCallId: String): List<ToolHookNotice> =
        cache[conversationId]?.deliveries.orEmpty().filter { it.toolCallId == toolCallId }.map { it.notice }

    @Synchronized fun pending(conversationId: String, enabledRuleIds: Set<String>? = null): HookPromptBatch {
        ensureLoaded(conversationId)
        if (enabledRuleIds != null) transaction(conversationId) { old ->
            old.copy(deliveries = old.deliveries.map { entry ->
                if (entry.notice.status == ToolHookNoticeStatus.PENDING && entry.notice.ruleId !in enabledRuleIds)
                    entry.copy(notice = entry.notice.copy(status = ToolHookNoticeStatus.SKIPPED,
                        reason = entry.notice.reason + " · Rule disabled, removed, or outside current scope"))
                else entry
            }) to Unit
        }
        return batch(cache.getValue(conversationId), enabledRuleIds)
    }

    /** Runs before transport starts; a failed write aborts dispatch and preserves the outbox. */
    fun dispatched(conversationId: String, batch: HookPromptBatch, requestId: String) {
        if (batch.ids.isEmpty()) return
        transaction(conversationId) { old ->
            old.copy(deliveries = old.deliveries.map { entry ->
                if (entry.notice.id in batch.ids && entry.notice.status == ToolHookNoticeStatus.PENDING)
                    entry.copy(notice = entry.notice.copy(status = ToolHookNoticeStatus.DISPATCHED, requestId = requestId), requestConfirmed = false)
                else entry
            }) to Unit
        }
    }

    fun confirmResponse(conversationId: String, requestId: String) = transaction(conversationId) { old ->
        old.copy(deliveries = old.deliveries.map { entry ->
            if (entry.notice.requestId == requestId && entry.notice.status == ToolHookNoticeStatus.DISPATCHED)
                entry.copy(requestConfirmed = true) else entry
        }) to Unit
    }

    fun recoverUnreceived(conversationId: String, requestId: String) = transaction(conversationId) { old ->
        old.copy(deliveries = old.deliveries.map { entry ->
            if (entry.notice.requestId == requestId && entry.notice.status == ToolHookNoticeStatus.DISPATCHED && !entry.requestConfirmed)
                entry.copy(notice = entry.notice.copy(status = ToolHookNoticeStatus.PENDING)) else entry
        }) to Unit
    }

    @Synchronized fun removeConversation(id: String) {
        val target = file(id)
        require(!target.exists() || target.delete()) { "Could not remove hook metadata" }
        cache.remove(id)
        deleted.add(id)
        mutableRevision.value++
    }

    companion object {
        const val MAX_FIRINGS_PER_TURN = 16
        const val MAX_PENDING_INSTRUCTIONS = 16
        const val MAX_INSTRUCTION_CHARS = 16_000
        const val MAX_PENDING_CHARS = 32_000
        private val instances = ConcurrentHashMap<String, HookRuntimeStore>()
        fun at(filesDir: File): HookRuntimeStore = instances.getOrPut(filesDir.absolutePath) {
            HookRuntimeStore(File(filesDir, "tool-hook-runtime"))
        }
        internal fun isolated(directory: File) = HookRuntimeStore(directory)

        private fun batch(state: HookRuntimeState, enabledRuleIds: Set<String>? = null): HookPromptBatch {
            val pending = state.deliveries.filter { it.notice.status == ToolHookNoticeStatus.PENDING && (enabledRuleIds == null || it.notice.ruleId in enabledRuleIds) }
                .sortedByDescending { it.priority }
            val instructions = pending.associate { delivery ->
                delivery.notice.id to "User-configured tool hook: ${delivery.notice.name}\n${delivery.notice.content}"
            }
            return HookPromptBatch(instructions.keys, instructions.values.joinToString("\n\n"), instructions)
        }
    }
}
