package me.rerere.rikkahub.data.ai.hooks

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** The original managed-job launch, retained separately from later poll arguments. */
@Serializable
data class ToolHookJobOrigin(
    val toolName: String,
    val arguments: JsonElement?,
    val callId: String = "",
)

/** Facts from a returned observation, not an assertion that an external process stopped. */
data class ToolHookEvent(
    val callId: String,
    val toolName: String,
    val originToolName: String? = null,
    val command: String = "",
    val executableNames: Set<String> = emptySet(),
    val completed: Boolean = false,
    val exitCode: Int? = null,
    val toolError: Boolean = false,
    val timedOut: Boolean = false,
    val stdout: String = "",
    val stderr: String = "",
    val jobId: String? = null,
    val deduplicationKey: String = "call:$callId",
    val successful: Boolean = false,
)

object ToolHookEventNormalizer {
    const val MAX_OBSERVATION_CHARS = 65_536

    fun normalize(
        toolName: String,
        arguments: JsonElement?,
        result: JsonElement?,
        callId: String,
        origin: ToolHookJobOrigin? = null,
    ): ToolHookEvent {
        val args = arguments as? JsonObject
        val launch = (origin?.arguments as? JsonObject) ?: args
        val out = result as? JsonObject
        val workspaceJob = toolName in setOf("workspace_run_background", "workspace_background_status", "workspace_background_kill")
        val jobId = out.string("job_id")?.takeIf { it.isNotBlank() }
            ?: args.string("job_id")?.takeIf { it.isNotBlank() }
            ?: if (workspaceJob) (out.string("id") ?: args.string("id"))?.takeIf { it.isNotBlank() } else null
        val managedJob = jobId != null || toolName.startsWith("termux_job_") || workspaceJob
        val rawExit = out.int("exit_code") ?: out.int("exitCode")
        val state = out.string("state") ?: when {
            workspaceJob && out.string("status") == "exited" -> "completed"
            workspaceJob -> out.string("status")
            toolName == "workspace_shell" && out.bool("timedOut") == true -> "timed_out"
            toolName == "workspace_shell" && rawExit != null && rawExit != -1 -> if (rawExit == 0) "completed" else "failed"
            else -> out.string("status")
        }
        val error = out.string("error")?.takeIf { it.isNotBlank() }
        val jobTerminal = managedJob && state in setOf("completed", "failed", "cancelled", "timed_out")
        val directTimeout = error in setOf("timeout", "command_timeout", "execution_timeout") || out.bool("timed_out") == true || out.bool("timedOut") == true
        // wait_timed_out only ends polling; it says nothing about the job's outcome.
        val timedOut = directTimeout || (managedJob && state == "timed_out")
        val detached = !managedJob && launch.bool("background") == true && launch.string("command") != null
        val interactive = launch.bool("interactive") == true || out.string("mode") == "interactive"
        // A supervisor/transport error may have its own exit code. Never call that a job exit.
        val exit = when {
            toolName == "workspace_shell" && rawExit == -1 -> null
            managedJob -> rawExit.takeIf { state in setOf("completed", "failed") && error == null }
            detached || interactive -> null
            error != null && out.bool("transport_success") != true -> null
            else -> rawExit
        }
        val toolError = error != null || out.bool("isError") == true || (out.bool("success") == false && exit == null && !timedOut)
        val completed = when {
            result == null || result == JsonNull || interactive || detached -> false
            managedJob -> jobTerminal
            directTimeout || state in setOf("unknown", "running", "starting", "cancelling") -> false
            else -> true
        }
        val rawCommand = launch.string("command") ?: out.string("command") ?: launch.string("input")
        val executable = launch.string("executable")
        val argv = (launch?.get("arguments") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val command = (rawCommand ?: (listOfNotNull(executable) + argv).joinToString(" ")).take(MAX_OBSERVATION_CHARS)
        val executables = when {
            executable != null -> ToolHookCommandParser.executables(executable, argv)
            else -> ToolHookCommandParser.executables(command)
        }
        var stdout = (out.string("stdout") ?: out.string("partial_stdout")).orEmpty()
        var stderr = (out.string("stderr") ?: out.string("partial_stderr")).orEmpty()
        if (out.string("stream") == "stdout") stdout = out.string("text") ?: stdout
        if (out.string("stream") == "stderr") stderr = out.string("text") ?: stderr
        if (out == null && result is JsonPrimitive && result.isString) stdout = result.content
        val successful = !toolError && !timedOut && completed && out.bool("success") != false && state != "failed" && (exit == 0 || (exit == null && out.bool("success") == true))
        return ToolHookEvent(
            callId = callId,
            toolName = toolName,
            originToolName = origin?.toolName,
            command = command,
            executableNames = executables,
            completed = completed,
            exitCode = exit,
            toolError = toolError,
            timedOut = timedOut,
            stdout = stdout.take(MAX_OBSERVATION_CHARS),
            stderr = stderr.take(MAX_OBSERVATION_CHARS),
            jobId = jobId,
            deduplicationKey = if (jobId != null && jobTerminal) "job:$jobId:terminal" else "call:$callId",
            successful = successful,
        )
    }

    private fun JsonObject?.string(key: String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject?.bool(key: String) = (this?.get(key) as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject?.int(key: String) = (this?.get(key) as? JsonPrimitive)?.intOrNull
}
