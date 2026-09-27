package me.rerere.rikkahub.data.ai.hooks

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolHookNotice
import me.rerere.ai.ui.ToolHookNoticeStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.ToolHook
import me.rerere.rikkahub.data.model.ToolHookAction
import me.rerere.rikkahub.data.model.TOOL_HOOK_MAX_FIRINGS_PER_TURN
import java.util.UUID

/** One generation slice; its outbox and launch correlation survive later slices/restarts. */
class ToolHookRuntime(
    private val store: HookRuntimeStore,
    private val conversationId: String,
    private val turnId: String,
    private val rules: () -> List<ToolHook>,
    private val scope: ToolHookScopeContext,
    private val resolveContent: (ToolHookAction) -> HookContentResolution,
) {
    fun pending(): HookPromptBatch = store.pending(conversationId, activeRuleIds())
    fun dispatched(batch: HookPromptBatch, requestId: String) = store.dispatched(conversationId, batch, requestId)
    fun received(requestId: String) = store.confirmResponse(conversationId, requestId)
    fun finished(progress: me.rerere.ai.provider.GenerationProgress) {
        if (progress.dispatchedAt == null) return
        if (progress.firstContentAt != null || progress.phase == me.rerere.ai.provider.GenerationPhase.COMPLETED)
            store.confirmResponse(conversationId, progress.requestId)
        else store.recoverUnreceived(conversationId, progress.requestId)
    }
    private fun activeRuleIds(): Set<String> = rules().filter { it.enabled && ToolHookMatcher.matchesScope(it.scope, scope) }
        .map { it.id.toString() }.toSet()

    /** Receives raw results before output spilling/truncation; never runs or retries a tool. */
    fun completed(tool: UIMessagePart.Tool): UIMessagePart.Tool = try {
        recordCompletion(tool)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A hook configuration/storage failure must not overwrite a genuine tool result
        // with a fabricated tool failure, or cause the side effect to run a second time.
        tool.copy(hookNotices = tool.hookNotices + ToolHookNotice(
            id = UUID.randomUUID().toString(), ruleId = "", name = "Hooks",
            reason = "Hook processing failed; the tool result was preserved", content = "",
            status = ToolHookNoticeStatus.SKIPPED,
        ))
    }

    private fun recordCompletion(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        if (tool.executionStartedAt == null || !tool.isExecuted) return tool
        val currentRules = rules().sortedWith(compareByDescending<ToolHook> { it.priority }.thenBy { it.id.toString() })
        val observations = observations(tool.output)
        val notices = store.transaction(conversationId) { initial ->
            var state = initial
            val added = mutableListOf<ToolHookNotice>()
            for (result in observations) {
                val first = ToolHookEventNormalizer.normalize(tool.toolName, tool.inputAsJson(), result, tool.toolCallId)
                val jobId = first.jobId
                if (jobId != null && isJobLaunchTool(tool.toolName)) {
                    state = state.copy(jobs = state.jobs + (jobId to ToolHookJobOrigin(tool.toolName, tool.inputAsJson(), tool.toolCallId)))
                }
                val origin = jobId?.let { state.jobs[it] }
                val event = if (origin == null) first else ToolHookEventNormalizer.normalize(
                    tool.toolName, tool.inputAsJson(), result, tool.toolCallId, origin,
                )
                // Only terminal job observations are deduplicated across poll calls. An
                // explicit rerun of a normal tool is a distinct execution attempt.
                val eventKey = if (event.deduplicationKey.startsWith("job:")) event.deduplicationKey
                    else "${tool.toolCallId}:${tool.executionAttemptId ?: tool.executionStartedAt}"
                for (rule in currentRules) {
                    val ruleId = rule.id.toString()
                    val key = "$ruleId:$eventKey"
                    if (key in state.seen) continue
                    val evaluated = ToolHookMatcher.evaluate(rule, event, scope)
                    if (!evaluated.matched) continue
                    val ruleTurnKey = "$turnId:$ruleId"
                    val fired = state.perTurnFirings[ruleTurnKey] ?: 0
                    val allFired = state.perTurnFirings.filterKeys { it.startsWith("$turnId:") }.values.sum()
                    val pending = state.deliveries.filter { it.notice.status == ToolHookNoticeStatus.PENDING }
                    val resolved = when {
                        fired >= rule.maxFiringsPerTurn.coerceIn(1, TOOL_HOOK_MAX_FIRINGS_PER_TURN) ->
                            HookContentResolution.Error("Per-rule limit for this turn reached")
                        allFired >= HookRuntimeStore.MAX_FIRINGS_PER_TURN ->
                            HookContentResolution.Error("Hook limit for this turn reached")
                        pending.size >= HookRuntimeStore.MAX_PENDING_INSTRUCTIONS ->
                            HookContentResolution.Error("Pending hook instruction limit reached")
                        else -> resolveContent(rule.action)
                    }
                    val text = (resolved as? HookContentResolution.Resolved)?.text.orEmpty()
                    val contentError = when {
                        resolved is HookContentResolution.Error -> resolved.reason
                        text.isBlank() -> "Hook instruction is empty"
                        text.length > HookRuntimeStore.MAX_INSTRUCTION_CHARS -> "Hook instruction is too large"
                        pending.sumOf { it.notice.content.length } + text.length > HookRuntimeStore.MAX_PENDING_CHARS -> "Pending hook content limit reached"
                        else -> null
                    }
                    val notice = ToolHookNotice(
                        id = UUID.randomUUID().toString(), ruleId = ruleId, name = rule.name.take(120),
                        reason = (evaluated.reasons + listOfNotNull(contentError)).joinToString(" · "),
                        content = if (contentError == null) text else "",
                        source = (resolved as? HookContentResolution.Resolved)?.source,
                        status = if (contentError == null) ToolHookNoticeStatus.PENDING else ToolHookNoticeStatus.SKIPPED,
                    )
                    state = state.copy(
                        seen = state.seen + key,
                        deliveries = state.deliveries + StoredHookDelivery(turnId, tool.toolCallId, eventKey, rule.priority, notice),
                        perTurnFirings = state.perTurnFirings + (ruleTurnKey to (fired + 1)),
                    )
                    added += notice
                }
            }
            state to added
        }
        return tool.copy(hookNotices = (tool.hookNotices + notices).distinctBy { it.id })
    }

    fun refreshNotices(messages: List<UIMessage>): List<UIMessage> = messages.map { message ->
        message.copy(parts = message.parts.map { part ->
            if (part !is UIMessagePart.Tool) part else {
                val saved = store.noticesCached(conversationId, part.toolCallId)
                if (saved.isEmpty()) part else part.copy(hookNotices = saved)
            }
        })
    }

    companion object {
        /** Adds user-configured instructions only to the request, never to user history. */
        fun inject(messages: List<UIMessage>, batch: HookPromptBatch): List<UIMessage> {
            if (batch.text.isBlank()) return messages
            val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
            val part = UIMessagePart.Text(batch.text)
            return if (systemIndex >= 0) messages.mapIndexed { index, message ->
                if (index == systemIndex) message.copy(parts = message.parts + part) else message
            } else listOf(UIMessage(role = MessageRole.SYSTEM, parts = listOf(part), isSynthetic = true)) + messages
        }

        internal fun isJobLaunchTool(name: String) = name in setOf("termux_job_start", "termux_run_command", "workspace_run_background", "workspace_shell")

        internal fun observations(parts: List<UIMessagePart>): List<JsonElement> {
            val observations = parts.filterIsInstance<UIMessagePart.Text>().flatMap { part ->
            val value = runCatching { Json.parseToJsonElement(part.text) }.getOrElse { JsonPrimitive(part.text) }
            val obj = value as? JsonObject
            when {
                obj?.get("jobs") is JsonArray -> (obj["jobs"] as JsonArray).toList()
                obj?.get("processes") is JsonArray -> (obj["processes"] as JsonArray).toList()
                obj?.get("job") is JsonObject -> listOf(obj["job"]!!)
                else -> listOf(value)
            }
        }
            // MCP marks isError at envelope level. A separate JSON content part cannot
            // turn that failed call into success or a known command exit.
            val envelopeError = observations.any { item ->
                ((item as? JsonObject)?.get("isError") as? JsonPrimitive)?.booleanOrNull == true
            }
            return if (!envelopeError) observations else observations.map { item ->
                val fields = (item as? JsonObject)?.toMap() ?: mapOf("stdout" to item)
                JsonObject(fields + ("isError" to JsonPrimitive(true)))
            }
        }
    }
}
