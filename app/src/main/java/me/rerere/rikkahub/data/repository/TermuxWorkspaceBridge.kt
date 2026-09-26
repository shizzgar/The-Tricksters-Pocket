package me.rerere.rikkahub.data.repository

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.tools.local.*
import me.rerere.rikkahub.data.preferences.TermuxPreferences
import me.rerere.workspace.*
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlin.uuid.Uuid

data class WorkspaceTextSnapshot(val text: String, val revision: String?)

/** Transport only: all project file access runs as Termux's UID, never through Android File. */
class TermuxWorkspaceBridge(private val context: Context, private val preferences: TermuxPreferences) {
    suspend fun request(root: String, action: String, path: String = "", extra: JsonObject = buildJsonObject {}): JsonObject =
        withContext(Dispatchers.IO) {
            check(TermuxIntegration.state(context) == TermuxIntegration.State.READY) {
                "Connect Termux and grant RUN_COMMAND in Settings > Termux."
            }
            val source = context.assets.open("termux/workspace_runtime.py").use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) }
            val runtime = "$TERMUX_HOME/.local/share/rebro-workspaces/${context.packageName}"
            val helper = "$runtime/runtime-$hash.py"
            val payload = buildJsonObject {
                put("root", root); put("action", action); put("path", relativePath(root, path))
                extra.forEach { (key, value) -> put(key, value) }
            }
            val script = """
                umask 077
                command -v python3 >/dev/null || { printf '%s\n' '{"success":false,"error":"python3_required","detail":"Install Python in Termux: pkg install python"}'; exit 1; }
                mkdir -p '$runtime' || exit 1
                if [ ! -f '$helper' ]; then
                  printf '%s' '${encode(source)}' | '$TERMUX_BIN/base64' -d > '$helper.'${'$'}${'$'}.tmp || exit 1
                  mv '$helper.'${'$'}${'$'}.tmp '$helper' || exit 1
                fi
                exec '$TERMUX_BIN/python3' '$helper' '${encode(payload.toString().toByteArray())}' '$runtime/transfers'
            """.trimIndent()
            when (val result = runCommandCapture(context, "$TERMUX_BIN/bash", arrayOf("-c", script), TERMUX_HOME, 60_000)) {
                is CaptureResult.Success -> checked(Json.parseToJsonElement(result.stdout.trim()).jsonObject)
                CaptureResult.Denied -> error("Termux RUN_COMMAND permission denied")
                CaptureResult.Timeout -> error("Termux file operation timed out. Refresh to inspect its result before retrying.")
                is CaptureResult.OtherError -> error(result.message)
            }
        }

    suspend fun list(root: String, path: String, limit: Int = 500): List<WorkspaceFileEntry> {
        val entries = mutableListOf<WorkspaceFileEntry>()
        var cursor = 0
        do {
            val result = request(root, "list", path, buildJsonObject { put("cursor", cursor) })
            entries += result.getValue("entries").jsonArray.map { it.jsonObject.toEntry() }
            cursor = result["next_cursor"]?.jsonPrimitive?.intOrNull ?: break
        } while (entries.size < limit)
        return entries.take(limit)
    }

    suspend fun tree(root: String, path: String): WorkspaceTreeResult {
        val entries = mutableListOf<WorkspaceTreeEntry>()
        var cursor = 0
        var truncated: Boolean
        do {
            val result = request(root, "tree", path, buildJsonObject { put("cursor", cursor) })
            entries += result.getValue("entries").jsonArray.map {
                val row = it.jsonObject
                val e = row.toEntry()
                WorkspaceTreeEntry("${root.trimEnd('/')}/${e.path}", e.name, e.isDirectory, e.sizeBytes, row.getValue("depth").jsonPrimitive.int)
            }
            truncated = result["truncated"]?.jsonPrimitive?.booleanOrNull == true
            cursor = result["next_cursor"]?.jsonPrimitive?.intOrNull ?: break
        } while (entries.size < 5000)
        return WorkspaceTreeResult(entries, truncated)
    }

    suspend fun stat(root: String, path: String): JsonObject = request(root, "stat", path).getValue("entry").jsonObject

    suspend fun cacheFile(root: String, path: String): java.io.File = withContext(Dispatchers.IO) {
        val dir = java.io.File(context.cacheDir, "termux-workspace-preview").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
        val suffix = path.substringAfterLast('.', "bin").take(12).filter { it.isLetterOrDigit() }
        val file = java.io.File.createTempFile("preview-", ".$suffix", dir)
        try {
            file.outputStream().use { export(root, path, it, 32L * 1024 * 1024) }
            file
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    suspend fun export(root: String, path: String, output: OutputStream, maxBytes: Long = MAX_FILE, expected: String? = null) {
        val info = stat(root, path)
        val size = info.getValue("sizeBytes").jsonPrimitive.long
        require(size <= maxBytes) { "File exceeds the $maxBytes byte limit" }
        val revision = info.getValue("revision").jsonPrimitive.content
        require(expected == null || expected == revision) { "File changed while opening it. Reload." }
        var offset = 0L
        do {
            val response = request(root, "read", path, buildJsonObject { put("offset", offset); put("revision", revision) })
            val bytes = Base64.getDecoder().decode(response.getValue("data").jsonPrimitive.content)
            require(offset + bytes.size <= maxBytes) { "File grew beyond the transfer limit" }
            output.write(bytes)
            offset = response.getValue("next_offset").jsonPrimitive.long
        } while (response["eof"]?.jsonPrimitive?.booleanOrNull != true)
    }

    suspend fun readText(root: String, path: String): WorkspaceTextSnapshot {
        val revision = stat(root, path).getValue("revision").jsonPrimitive.content
        val out = java.io.ByteArrayOutputStream()
        export(root, path, out, 512 * 1024, revision)
        return WorkspaceTextSnapshot(out.toString(Charsets.UTF_8.name()), revision)
    }

    suspend fun write(root: String, path: String, input: InputStream, overwrite: Boolean, expected: String? = null, parents: Boolean = false): WorkspaceFileEntry {
        val transfer = Uuid.random().toString()
        try {
            request(root, "begin", path, buildJsonObject {
                put("transfer_id", transfer); put("overwrite", overwrite); put("parents", parents)
                expected?.let { put("revision", it) }
            })
            var offset = 0L
            input.use { stream ->
                val buffer = ByteArray(24 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    require(offset + count <= MAX_FILE) { "File exceeds the 256 MiB transfer limit" }
                    request(root, "chunk", extra = buildJsonObject {
                        put("transfer_id", transfer); put("offset", offset); put("data", encode(buffer.copyOf(count)))
                    })
                    offset += count
                }
            }
            return request(root, "commit", extra = buildJsonObject {
                put("transfer_id", transfer); put("size", offset)
            }).getValue("entry").jsonObject.toEntry()
        } finally {
            withContext(NonCancellable) {
                runCatching { request(root, "abort", extra = buildJsonObject { put("transfer_id", transfer) }) }
            }
        }
    }

    suspend fun jobs(workspaceId: String, payload: JsonObject): JsonObject =
        checked(termuxJobRequest(context, "workspace:$workspaceId", payload))

    suspend fun start(workspaceId: String, root: String, command: String, cwd: String, timeoutSeconds: Long = 3600, aptWrapEnabled: Boolean? = null): JsonObject {
        val preamble = termuxCommandPreamble(aptWrapEnabled ?: preferences.snapshot().aptWrapEnabled)
        val relative = relativePath(root, cwd)
        request(root, "probe", relative)
        return jobs(workspaceId, buildJsonObject {
            put("action", "start"); put("operation_id", Uuid.random().toString()); put("command", preamble + command)
            put("working_dir", if (relative.isBlank()) root else "${root.trimEnd('/')}/$relative")
            put("execution_timeout_seconds", timeoutSeconds.coerceIn(1, 86400))
        })
    }

    suspend fun execute(workspaceId: String, root: String, command: String, cwd: String, timeoutMillis: Long?): WorkspaceCommandResult {
        val settings = preferences.snapshot()
        val timeout = timeoutMillis ?: settings.commandTimeoutMs
        var result = start(workspaceId, root, command, cwd, (timeout / 1000).coerceAtLeast(1), settings.aptWrapEnabled)
        val job = result.getValue("job_id").jsonPrimitive.content
        try {
            while (result.string("state") in ACTIVE) {
                result = jobs(workspaceId, buildJsonObject { put("action", "wait"); put("job_id", job); put("timeout_seconds", 20) })
            }
            check(result.string("state") != "unknown") { "Termux job $job has an unknown outcome. Inspect Workspace > Console > Jobs." }
            suspend fun preview(stream: String, limit: Int) = readTermuxJobPreview(limit) { cursor, bytes ->
                jobs(workspaceId, buildJsonObject {
                    put("action", "read"); put("job_id", job); put("stream", stream)
                    put("cursor", cursor); put("max_bytes", bytes)
                })
            }
            val out = preview("stdout", settings.maxStdoutBytes)
            val err = preview("stderr", settings.maxStderrBytes)
            return WorkspaceCommandResult(
                exitCode = result["exit_code"]?.jsonPrimitive?.intOrNull ?: -1,
                stdout = out.text, stderr = err.text,
                timedOut = result.string("state") == "timed_out",
                truncated = out.truncated || err.truncated,
                jobId = job,
            )
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                runCatching { jobs(workspaceId, buildJsonObject { put("action", "cancel"); put("job_id", job); put("timeout_seconds", 1) }) }
            }
            throw e
        }
    }

    suspend fun background(workspaceId: String, jobId: String): BackgroundStatus {
        val result = jobs(workspaceId, buildJsonObject { put("action", "wait"); put("job_id", jobId); put("timeout_seconds", 1) })
        return backgroundStatus(result)
    }

    suspend fun listBackground(workspaceId: String): List<BackgroundStatus> {
        val statuses = mutableListOf<BackgroundStatus>()
        var cursor = 0
        do {
            val result = jobs(workspaceId, buildJsonObject { put("action", "list"); put("cursor", cursor) })
            statuses += result.getValue("jobs").jsonArray.map { backgroundStatus(it.jsonObject) }
            cursor = result["next_cursor"]?.jsonPrimitive?.intOrNull ?: break
        } while (result["has_more"]?.jsonPrimitive?.booleanOrNull == true)
        return statuses
    }

    suspend fun cancel(workspaceId: String, jobId: String): Boolean = jobs(workspaceId, buildJsonObject {
        put("action", "cancel"); put("job_id", jobId); put("timeout_seconds", 5)
    })["cancel_confirmed"]?.jsonPrimitive?.booleanOrNull == true

    private fun backgroundStatus(value: JsonObject) = BackgroundStatus(
        id = value.getValue("job_id").jsonPrimitive.content, command = value.string("command").orEmpty(),
        cwd = value.string("working_dir").orEmpty(), running = value.string("state") in ACTIVE || value.string("state") == "unknown",
        exitCode = value["exit_code"]?.jsonPrimitive?.intOrNull,
        startedAtMillis = ((value["started_at"] ?: value["created_at"])?.jsonPrimitive?.doubleOrNull?.times(1000))?.toLong() ?: 0,
        stdout = value.string("stdout").orEmpty(), stderr = value.string("stderr").orEmpty() + value.string("reason").orEmpty(),
        droppedStdout = (value["stdout_stored_bytes"]?.jsonPrimitive?.longOrNull ?: 0) - (value["stdout_next_cursor"]?.jsonPrimitive?.longOrNull ?: 0),
        droppedStderr = (value["stderr_stored_bytes"]?.jsonPrimitive?.longOrNull ?: 0) - (value["stderr_next_cursor"]?.jsonPrimitive?.longOrNull ?: 0),
    )

    companion object {
        private const val MAX_FILE = 256L * 1024 * 1024
        private val ACTIVE = setOf("starting", "running", "cancelling")
        internal fun relativePath(root: String, path: String): String {
            val base = root.trimEnd('/')
            val relative = when {
                path == base || path == "/workspace" || path.isBlank() -> ""
                path.startsWith("$base/") -> path.removePrefix("$base/")
                path.startsWith("/workspace/") -> path.removePrefix("/workspace/")
                !path.startsWith('/') -> path
                else -> error("Path is outside the linked Termux directory: $path")
            }
            require(relative.isEmpty() || relative.split('/').none { it.isEmpty() || it == "." || it == ".." || '\u0000' in it }) { "Invalid workspace path" }
            return relative
        }
        private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
        private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
        private fun checked(result: JsonObject): JsonObject {
            check(result["success"]?.jsonPrimitive?.booleanOrNull != false && result["error"] == null) {
                listOfNotNull(result.string("error"), result.string("detail"), result.string("reason"), result.string("recovery")).joinToString("\n")
            }
            return result
        }
        internal fun JsonObject.toEntry() = WorkspaceFileEntry(
            path = getValue("path").jsonPrimitive.content, name = getValue("name").jsonPrimitive.content,
            isDirectory = getValue("isDirectory").jsonPrimitive.boolean,
            sizeBytes = getValue("sizeBytes").jsonPrimitive.long, updatedAt = getValue("updatedAt").jsonPrimitive.long,
            revision = this["revision"]?.jsonPrimitive?.contentOrNull,
        )
    }
}
