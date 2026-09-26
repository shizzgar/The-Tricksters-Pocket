package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
import me.rerere.rikkahub.data.preferences.TermuxRuntime
import java.security.MessageDigest
import java.util.Base64

/** The immutable helper stays on Termux private storage while workers are running. */
internal suspend fun termuxJobRequest(context: Context, owner: String, request: JsonObject): JsonObject = withContext(Dispatchers.IO) {
    val source = context.assets.open("termux/job_runtime.py").use { it.readBytes() }
    val hash = MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) }
    val base = "$TERMUX_HOME/.local/share/rikkahub-jobs/${context.packageName}"
    val helper = "$base/runtime-$hash.py"
    val encodedSource = Base64.getEncoder().encodeToString(source)
    val payload = buildJsonObject {
        request.filterKeys { it != "platform_boot_marker" }.forEach { (key, value) -> put(key, value) }
        put("owner", sessionOwner(owner))
        val bootCount = runCatching {
            android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)
        if (bootCount >= 0) put("platform_boot_marker", "android-boot-count:$bootCount")
    }
    val encodedRequest = Base64.getEncoder().encodeToString(payload.toString().toByteArray())
    // Every interpolated value is app-owned or base64. User commands are data in the RPC.
    val script = """
        umask 077
        command -v python3 >/dev/null || { printf '%s\n' '{"success":false,"error":"python3_required","recovery":"Install Python in Termux to use managed jobs."}'; exit 1; }
        mkdir -p '$base' || exit 1
        if [ ! -f '$helper' ]; then
          printf '%s' '$encodedSource' | '$TERMUX_BIN/base64' -d > '$helper.'${'$'}${'$'}.tmp || exit 1
          mv '$helper.'${'$'}${'$'}.tmp '$helper' || exit 1
        fi
        exec '$TERMUX_BIN/python3' '$helper' '$encodedRequest'
    """.trimIndent()
    val timeout = ((request["timeout_seconds"] as? JsonPrimitive)?.intOrNull ?: 20).coerceIn(1, 60)
    when (val result = runCommandCapture(context, "$TERMUX_BIN/bash", arrayOf("-c", script), TERMUX_HOME, (timeout + 15) * 1000L)) {
        is CaptureResult.Success -> runCatching { Json.parseToJsonElement(result.stdout.trim()).jsonObject }.getOrElse {
            buildJsonObject { put("success", false); put("error", "invalid_supervisor_response"); put("exit_code", result.exitCode); put("stderr", result.stderr.take(2000)) }
        }
        else -> buildJsonObject {
            put("success", false); put("state", "unknown"); put("error", "supervisor_response_unavailable")
            put("reason", when (result) {
                CaptureResult.Timeout -> "Observation timed out; the job may have started. Reconcile with termux_job_list or the SAME operation_id."
                CaptureResult.Denied -> "Termux RUN_COMMAND permission denied."
                is CaptureResult.OtherError -> result.message
                else -> "Unknown transport state"
            })
            request["operation_id"]?.let { put("operation_id", it) }
        }
    }
}

fun termuxJobTools(context: Context, owner: String?, defaultWorkingDir: String? = null): List<Tool> =
    listOf("start", "read", "wait", "cancel", "list", "forget").map { action ->
        Tool(
            name = "termux_job_$action",
            description = when (action) {
                "start" -> "Start a durable Termux batch job owned by this conversation. Required operation_id deduplicates identical retries; a different command with the same ID is rejected. Returns job_id; read stdout/stderr by cursor, wait or cancel separately. Survives The Trickster's Pocket restart. Python in Termux required. Runs as Termux UID. Logs capped at 8 MiB per stream, store 256 MiB; quota loss is explicit."
                "read" -> "Read one page of a job's private stdout/stderr. Use returned next_cursor unchanged (UTF-8 byte cursor). Status includes log truncation; this does not rerun the command."
                "wait" -> "Observe job completion for up to 60 seconds and return stdout/stderr pages. Pass stdout_next_cursor and stderr_next_cursor back as stdout_cursor/stderr_cursor to read only new output. wait_timed_out does not terminate the job. After interruption/restart, list/wait/read existing jobs instead of relaunching."
                "cancel" -> "Request cancellation of an owned job and wait for its supervisor. Signals only its managed process group; detached/new-session or privileged descendants may escape. cancel_confirmed is distinct from requesting cancellation."
                "forget" -> "Remove logs of a confirmed finished job to reclaim quota. Keeps its operation receipt to prevent duplicate execution. Does not remove running or unknown jobs."
                else -> "Reconcile/list durable jobs for this conversation after a lost response, app restart or device reboot. Process identity uses boot ID and start ticks. Unknown outcome is never silently restarted."
            },
            parameters = {
                fun field(type: String, description: String) = buildJsonObject { put("type", type); put("description", description) }
                InputSchema.Obj(properties = buildJsonObject {
                    if (action == "start") {
                        put("operation_id", field("string", "Stable ID for this one intended launch; reuse on uncertain retry. New intended execution needs a new ID."))
                        put("command", field("string", "Bash command/script to execute"))
                        put("working_dir", field("string", "Private working directory; defaults to configured Termux directory"))
                        put("execution_timeout_seconds", field("integer", "Job lifetime, 1–86400 seconds; default 3600. Separate from waiting."))
                    } else if (action != "list") put("job_id", field("string", "ID returned by start/list"))
                    if (action in listOf("read", "list")) put("cursor", field("integer", "next_cursor from the previous page; default 0"))
                    if (action == "read") {
                        put("stream", field("string", "stdout or stderr; default stdout"))
                        put("max_bytes", field("integer", "Page size 256–32000 bytes; default 12000"))
                    }
                    if (action in listOf("wait", "cancel")) put("timeout_seconds", field("integer", "Wait 1–60 seconds; default 20"))
                    if (action in listOf("start", "wait", "cancel")) {
                        put("stdout_cursor", field("integer", "Previous stdout_next_cursor; UTF-8 byte offset, default 0"))
                        put("stderr_cursor", field("integer", "Previous stderr_next_cursor; UTF-8 byte offset, default 0"))
                        put("output_max_bytes", field("integer", "Bytes per output stream, 256–6000; default 6000"))
                    }
                }, required = when (action) { "start" -> listOf("operation_id", "command"); "list" -> emptyList(); else -> listOf("job_id") })
            },
            execute = execute@{ input ->
                if (owner == null) return@execute listOf(UIMessagePart.Text("{\"error\":\"conversation_identity_required\"}"))
                if (action == "start") {
                    val command = input.jsonObject["command"]?.jsonPrimitive?.content.orEmpty()
                    HardlineCommandGuard.checkCommand(command)?.let {
                        return@execute listOf(UIMessagePart.Text(buildJsonObject { put("error", "blocked_by_safety_floor"); put("reason", it) }.toString()))
                    }
                }
                val request = buildJsonObject {
                    input.jsonObject.forEach { (k,v) -> put(k,v) }; put("action", action)
                    if (action == "start") put("working_dir", me.rerere.rikkahub.data.ai.tools.resolveTermuxWorkingDirectory(
                        input.jsonObject["working_dir"]?.jsonPrimitive?.contentOrNull, defaultWorkingDir))
                }
                // Cancellation propagates to the agent loop; the external job remains observable.
                val result = termuxJobRequest(context, owner, request)
                listOf(UIMessagePart.Text(result.toString()))
            },
        )
    }
