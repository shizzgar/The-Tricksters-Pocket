package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.*

/** Presentation only: never interpret terminal output as shell/HTML/Markdown instructions. */
internal enum class TermuxStatus {
    PENDING, APPROVAL, DENIED, RUNNING, UNKNOWN, COMPLETED, FAILED, TIMEOUT, DISPATCHED, SESSION_UPDATED,
    STARTING, OBSERVED_RUNNING, CANCELLING, CANCELLED, JOB_TIMEOUT, RESPONSE_RECEIVED, LOGS_REMOVED,
}

internal enum class TermuxOutputState { ARCHIVED, MORE_AVAILABLE, PREVIEW_SHORTENED, TRUNCATED, UNAVAILABLE, REMOVED }

internal data class TermuxPresentation(
    val status: TermuxStatus,
    val command: String?,
    val exitCode: Int?,
    val sessionId: String?,
    val pid: String?,
    val mode: String?,
    val output: JsonObject?,
) {
    val isJobSnapshot: Boolean get() = output?.stringValue("job_id") != null
    val waitExpired: Boolean get() = output?.booleanValue("wait_timed_out") == true
    val outputState: TermuxOutputState? = terminalOutputState(output)
}

private fun JsonObject.stringValue(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.booleanValue(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull

/** Stored results are observations, not a live connection to a supervisor. */
internal fun terminalOutputState(out: JsonObject?): TermuxOutputState? {
    if (out == null) return null
    val logLoss = when (val value = out["logs_truncated"]) {
        is JsonObject -> value.values.any { (it as? JsonPrimitive)?.booleanOrNull == true }
        is JsonPrimitive -> value.booleanOrNull == true
        else -> false
    }
    val shortened = listOf("stdout", "stderr").any { key ->
        Regex("\\n…\\[truncated(?:; \\d+ bytes more)?]$").containsMatchIn(out.stringValue(key).orEmpty())
    }
    return when {
        out.booleanValue("logs_removed") == true -> TermuxOutputState.REMOVED
        out.stringValue("archive_error") != null -> TermuxOutputState.UNAVAILABLE
        out.booleanValue("archive_truncated") == true || logLoss -> TermuxOutputState.TRUNCATED
        out.booleanValue("has_more") == true && (out["text"] as? JsonPrimitive)?.isString == true -> TermuxOutputState.MORE_AVAILABLE
        out.booleanValue("stdout_has_more") == true || out.booleanValue("stderr_has_more") == true -> TermuxOutputState.MORE_AVAILABLE
        !out.stringValue("output_ref").isNullOrBlank() -> TermuxOutputState.ARCHIVED
        shortened -> TermuxOutputState.PREVIEW_SHORTENED
        else -> null
    }
}

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
    val job = name.startsWith("termux_job_")
    val state = out.str("state")
    val interactive = out.str("mode") == "interactive" || args.bool("interactive") == true
    // background is ignored by executable mode in the actual integration.
    val detached = !interactive && args.str("command") != null && args.bool("background") == true
    val pid = if (detached) Regex("(?m)^rikkahub_bg_pid=(\\d+)\\s*$")
        .find(out.str("stdout").orEmpty())?.groupValues?.get(1) else null
    val status = when {
        denied -> TermuxStatus.DENIED
        pendingApproval -> TermuxStatus.APPROVAL
        out.str("state") == "unknown" -> TermuxStatus.UNKNOWN
        out.str("error") == "timeout" || out.bool("timed_out") == true -> TermuxStatus.TIMEOUT
        job && name == "termux_job_forget" && out.bool("success") == true && out.bool("logs_removed") == true -> TermuxStatus.LOGS_REMOVED
        out.str("error") != null || out.bool("success") == false -> TermuxStatus.FAILED
        job && state == "cancelled" -> if (out.bool("cancel_confirmed") == false) TermuxStatus.UNKNOWN else TermuxStatus.CANCELLED
        job && state == "timed_out" -> TermuxStatus.JOB_TIMEOUT
        job && state == "failed" -> TermuxStatus.FAILED
        job && (state == "cancelling" || (name == "termux_job_cancel" && state in setOf("starting", "running"))) -> TermuxStatus.CANCELLING
        job && state == "starting" -> TermuxStatus.STARTING
        job && state == "running" -> TermuxStatus.OBSERVED_RUNNING
        exit != null && exit != 0 -> TermuxStatus.FAILED
        out == null && loading && started -> TermuxStatus.RUNNING
        out == null && !hasResult && !started -> TermuxStatus.PENDING
        out == null -> TermuxStatus.UNKNOWN
        interactive && out.bool("success") == true -> TermuxStatus.DISPATCHED
        detached && exit == 0 && pid != null -> TermuxStatus.DISPATCHED
        detached -> TermuxStatus.UNKNOWN
        name.startsWith("termux_session_") && out.bool("success") == true -> TermuxStatus.SESSION_UPDATED
        name == "termux_job_list" && out.bool("success") == true && out?.get("jobs") is JsonArray -> TermuxStatus.RESPONSE_RECEIVED
        name == "termux_output_read" && (out?.get("text") as? JsonPrimitive)?.isString == true && !out.str("output_ref").isNullOrBlank() -> TermuxStatus.RESPONSE_RECEIVED
        exit == 0 -> TermuxStatus.COMPLETED
        else -> TermuxStatus.UNKNOWN
    }
    val command = args.str("command") ?: args.str("input") ?: args.str("executable")?.let { executable ->
        // This is a quoted display of argv, not the shell command executed by the tool.
        (listOf(executable) + ((args?.get("arguments") as? JsonArray)?.map {
            (it as? JsonPrimitive)?.contentOrNull ?: it.toString()
        } ?: emptyList())).joinToString(" ", transform = ::quoteTermuxArgument)
    } ?: if (job) out.str("command") else null
    return TermuxPresentation(
        status, command, exit, out.str("session_id") ?: args.str("session_id") ?: out.str("killed"),
        pid, if (detached) "background" else out.str("mode"), out,
    )
}

internal fun quoteTermuxArgument(value: String): String =
    if (value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in "_@%+=:,./-" }) value
    else "'" + value.replace("'", "'\"'\"'") + "'"
