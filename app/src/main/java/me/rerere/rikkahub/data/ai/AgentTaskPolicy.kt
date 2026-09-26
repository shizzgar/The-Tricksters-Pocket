package me.rerere.rikkahub.data.ai

import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.core.Tool

@Serializable
data class ScopedAgentPolicy(
    val maxSteps: Int,
    val usedSteps: Int = 0,
    val deadlineAtMs: Long = Long.MAX_VALUE,
    val allowedTools: Set<String>? = null,
    val readOnly: Boolean = false,
    val systemPrompt: String? = null,
    val stopped: Boolean = false,
    val scopedWorkspaceId: String? = null,
) {
    init {
        require(scopedWorkspaceId == null || runCatching { java.util.UUID.fromString(scopedWorkspaceId) }.isSuccess) { "Invalid scoped workspace ID" }
    }
}

class AgentExecutionLimitException(val stopReason: GenerationStopReason) : IllegalStateException("Execution stopped: ${stopReason.name}")

/** Persist BEFORE a provider request. A crash can consume a step, never restore it. */
object AgentTaskPolicy {
    private var directory: File? = null
    private val policies = mutableMapOf<String, ScopedAgentPolicy>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized fun initialize(filesDir: File) {
        val target = File(filesDir, "agent-policies")
        if (directory == target) return
        check(target.isDirectory || target.mkdirs())
        directory = target
        policies.clear()
        target.listFiles().orEmpty().filter { it.extension == "json" }.forEach {
            // Damaged policy must never become unrestricted.
            policies[it.nameWithoutExtension] = runCatching { json.decodeFromString<ScopedAgentPolicy>(it.readText()) }
                .getOrElse { ScopedAgentPolicy(1, 1, stopped = true, allowedTools = emptySet()) }
        }
    }

    @Synchronized fun get(id: String): ScopedAgentPolicy? = policies[id]
    @Synchronized fun set(id: String, policy: ScopedAgentPolicy) {
        require(runCatching { java.util.UUID.fromString(id) }.isSuccess)
        directory?.let { dir ->
            val tmp = File(dir, "$id.tmp")
            FileOutputStream(tmp).use { it.write(json.encodeToString(ScopedAgentPolicy.serializer(), policy).toByteArray()); it.fd.sync() }
            check(tmp.renameTo(File(dir, "$id.json"))) { "Could not persist run policy" }
        }
        policies[id] = policy
    }
    @Synchronized fun setStepLimit(id: String, limit: Int) { require(limit > 0); set(id, ScopedAgentPolicy(limit)) }
    @Synchronized fun stepLimit(id: String): Int? = policies[id]?.let { (it.maxSteps - it.usedSteps).coerceAtLeast(0) }
    @Synchronized fun stop(id: String) { policies[id]?.let { set(id, it.copy(stopped = true)) } }
    /** Durable review metadata survives portable backup even when execution policies do not. */
    @Synchronized fun ensureReview(id: String, workspaceId: String?, resumeStopped: Boolean = false) {
        val existing = policies[id]
        val next = (existing ?: ScopedAgentPolicy(Int.MAX_VALUE)).copy(
            readOnly = true,
            scopedWorkspaceId = workspaceId,
            stopped = existing?.stopped == true && !resumeStopped,
        )
        if (next != existing) set(id, next)
    }
    @Synchronized fun check(id: String, now: Long = System.currentTimeMillis()): GenerationStopReason? {
        val p = policies[id] ?: return null
        return when {
            p.stopped -> GenerationStopReason.CANCELLED
            now >= p.deadlineAtMs -> GenerationStopReason.TASK_DEADLINE
            p.usedSteps >= p.maxSteps -> GenerationStopReason.RUN_STEP_LIMIT
            else -> null
        }
    }
    @Synchronized fun reserveStep(id: String, now: Long = System.currentTimeMillis()): GenerationStopReason? {
        check(id, now)?.let { return it }
        policies[id]?.let { set(id, it.copy(usedSteps = it.usedSteps + 1)) }
        return null
    }
    @Synchronized fun clear(id: String) { policies.remove(id); directory?.let { File(it, "$id.json").delete() } }
}

/** Explicit read operations only: shell, scripts, arbitrary MCP and mutating skills never qualify. */
object AgentToolPolicy {
    private val readTools = setOf(
        "workspace_read_file", "workspace_read_folder", "workspace_background_status",
        "read_text_file", "read_file", "find_files", "list_files", "list_directory", "file_info", "show_image",
        "termux_read_file", "termux_list_files", "termux_read_file_chunk", "termux_stat",
        "web_search", "search", "search_web", "fetch_webpage", "fetch_url", "scrape_webpage",
        "get_time_info", "get_current_time", "get_time", "time_info",
        "skill_get_content", "skill_list_files", "skill_read_file", "web_fetch", "conversation_search", "conversation_history_read",
        "termux_session_read", "termux_session_list", "termux_output_read", "check_token_usage", "search_memory",
        "search_memories", "get_memories", "subagent_get", "subagent_list", "search_conversations",
        "get_conversation", "search_chat_history", "get_recent_chats", "task_artifacts", "register_artifact",
    )
    fun isReadOnlyTool(name: String): Boolean = name in readTools
    fun permits(name: String, conversationId: String?, readOnly: Boolean): Boolean {
        val p = conversationId?.let(AgentTaskPolicy::get)
        return (!readOnly && p?.readOnly != true || isReadOnlyTool(name)) &&
            (p?.allowedTools == null || name in p.allowedTools)
    }
    fun filter(tools: List<Tool>, conversationId: String?, readOnly: Boolean): List<Tool> =
        tools.filter { permits(it.name, conversationId, readOnly) }
}
