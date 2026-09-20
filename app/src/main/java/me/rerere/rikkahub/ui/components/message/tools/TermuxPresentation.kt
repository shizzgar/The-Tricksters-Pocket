package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.*

/** Presentation only: never interpret terminal output as shell/HTML/Markdown instructions. */
internal enum class TermuxStatus {
    PENDING, APPROVAL, DENIED, RUNNING, UNKNOWN, COMPLETED, FAILED, TIMEOUT, DISPATCHED, SESSION_UPDATED,
}

internal data class TermuxPresentation(
    val status: TermuxStatus,
    val command: String?,
    val exitCode: Int?,
    val sessionId: String?,
    val pid: String?,
    val mode: String?,
    val output: JsonObject?,
)

internal fun presentTermux(
    name: String,
    arguments: JsonElement,
    result: JsonElement?,
    loading: Boolean,
    started: Boolean,
    hasResult: Boolean = result != null,
    denied: Boolean = false,
    pendingApproval: Boolean = false,
): TermuxPresentation {
    val args = arguments as? JsonObject
    val out = result as? JsonObject
    fun JsonObject?.str(key: String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull
    fun JsonObject?.bool(key: String) = (this?.get(key) as? JsonPrimitive)?.booleanOrNull
    val exit = (out?.get("exit_code") as? JsonPrimitive)?.intOrNull
    val interactive = out.str("mode") == "interactive" || args.bool("interactive") == true
    // background is ignored by executable mode in the actual integration.
    val detached = !interactive && args.str("command") != null && args.bool("background") == true
    val pid = if (detached) Regex("(?m)^rikkahub_bg_pid=(\\d+)\\s*$")
        .find(out.str("stdout").orEmpty())?.groupValues?.get(1) else null
    val status = when {
        denied -> TermuxStatus.DENIED
        pendingApproval -> TermuxStatus.APPROVAL
        out.str("error") == "timeout" || out.bool("timed_out") == true -> TermuxStatus.TIMEOUT
        out.str("error") != null || out.bool("success") == false || (exit != null && exit != 0) -> TermuxStatus.FAILED
        out == null && loading && started -> TermuxStatus.RUNNING
        out == null && !hasResult && !started -> TermuxStatus.PENDING
        out == null -> TermuxStatus.UNKNOWN
        interactive && out.bool("success") == true -> TermuxStatus.DISPATCHED
        detached && exit == 0 && pid != null -> TermuxStatus.DISPATCHED
        detached -> TermuxStatus.UNKNOWN
        name.startsWith("termux_session_") && out.bool("success") == true -> TermuxStatus.SESSION_UPDATED
        exit == 0 -> TermuxStatus.COMPLETED
        else -> TermuxStatus.UNKNOWN
    }
    val command = args.str("command") ?: args.str("input") ?: args.str("executable")?.let { executable ->
        // This is a quoted display of argv, not the shell command executed by the tool.
        (listOf(executable) + ((args?.get("arguments") as? JsonArray)?.map {
            (it as? JsonPrimitive)?.contentOrNull ?: it.toString()
        } ?: emptyList())).joinToString(" ", transform = ::quoteTermuxArgument)
    }
    return TermuxPresentation(
        status, command, exit, out.str("session_id") ?: args.str("session_id") ?: out.str("killed"),
        pid, if (detached) "background" else out.str("mode"), out,
    )
}

internal fun quoteTermuxArgument(value: String): String =
    if (value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in "_@%+=:,./-" }) value
    else "'" + value.replace("'", "'\"'\"'") + "'"
