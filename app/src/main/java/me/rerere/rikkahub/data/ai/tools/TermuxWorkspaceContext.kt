package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.preferences.TermuxRuntime
import java.nio.file.Paths

/** Immutable invocation context; never changes the process-wide Termux preferences. */
data class TermuxWorkspaceContext(
    val id: String,
    val root: String,
    val workingDirectory: String,
    val approvals: Map<String, Boolean>,
) {
    val owner: String get() = "workspace:$id"
}

fun WorkspaceEntity.termuxContext(cwd: String? = null): TermuxWorkspaceContext? = termuxPath?.let {
    TermuxWorkspaceContext(id, it, resolveTermuxWorkingDirectory(cwd, it), toolApprovalOverrides())
}

/** Workspace is a starting directory, not a sandbox or a replacement for Termux's HOME. */
fun resolveTermuxWorkingDirectory(requested: String?, base: String? = null): String {
    val directory = base ?: TermuxRuntime.defaultWorkingDir
    val path = requested?.takeIf { it.isNotBlank() } ?: return directory
    val home = "/data/data/com.termux/files/home"
    val expanded = when {
        path == "~" -> home
        path.startsWith("~/") -> home + path.drop(1)
        else -> path
    }
    return Paths.get(directory).resolve(expanded).normalize().toString()
}

fun isScopedWorkspaceTool(name: String, termuxWorkspace: Boolean): Boolean =
    name.startsWith("workspace_") || (termuxWorkspace && name.startsWith("termux_"))

val TermuxWorkspaceTools = listOf(
    "termux_run_command", "termux_session_start", "termux_session_send",
    "termux_session_read", "termux_session_kill", "termux_session_list", "termux_session_manage",
    "termux_job_start", "termux_job_read", "termux_job_wait", "termux_job_cancel",
    "termux_job_list", "termux_job_forget", "termux_output_read", "termux_skill_sync",
)
