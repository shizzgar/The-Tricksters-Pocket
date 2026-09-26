package me.rerere.rikkahub.skills

import android.content.Context
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.tools.local.*
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.preferences.TermuxPreferences

data class TermuxSkillConfig(
    val enabled: Boolean = true,
    val syncOnUse: Boolean = true,
    val directory: String = DEFAULT_DIRECTORY,
) {
    companion object {
        const val DEFAULT_DIRECTORY = "/data/data/com.termux/files/home/.local/share/rikkahub-skills"
        fun validDirectory(value: String): Boolean = value.trimEnd('/').startsWith("$TERMUX_HOME/") &&
            value.trimEnd('/').split('/').drop(1).none { it == ".." || it == "." || it.isEmpty() } &&
            value.none { it.isISOControl() } && value.length <= 512
    }
}

/** Ships only an explicitly selected skill. Copies do not execute scripts or install dependencies. */
class TermuxSkillBridge(private val context: Context, private val preferences: TermuxPreferences) {
    private val mutex = Mutex()

    suspend fun prepare(skill: SkillMetadata, automatic: Boolean = false): JsonObject = mutex.withLock {
        withContext(Dispatchers.IO) {
            val config = preferences.skillConfigFlow().first()
            if (!config.enabled) return@withContext failure("termux_skills_disabled", "Enable Skills in Settings → Termux.")
            if (automatic && !config.syncOnUse) return@withContext buildJsonObject {
                put("status", "manual_sync"); put("hint", "Call termux_skill_sync with this skill name before using its scripts.")
            }
            if (TermuxIntegration.state(context) != TermuxIntegration.State.READY) {
                return@withContext failure("termux_not_ready", "Check Termux installation and RUN_COMMAND permission in Settings → Termux.")
            }
            val archive = File.createTempFile("termux-skill-", ".zip", context.cacheDir)
            try {
                val revision = SkillPackage.archive(skill.skillDir, archive)
                val key = SkillPackage.digest(skill.skillDir.name.toByteArray(Charsets.UTF_8))
                suspend fun rpc(action: String, extra: JsonObject = buildJsonObject {}): JsonObject = request(config, buildJsonObject {
                    put("action", action); put("key", key); put("revision", revision)
                    extra.forEach { (k, v) -> put(k, v) }
                })
                var result = rpc("probe")
                if (result["success"]?.jsonPrimitive?.booleanOrNull != true) return@withContext result
                if (result["ready"]?.jsonPrimitive?.booleanOrNull != true) {
                    result = rpc("begin", buildJsonObject { put("archive_bytes", archive.length()) })
                    if (result["success"]?.jsonPrimitive?.booleanOrNull != true) return@withContext result
                    archive.inputStream().use { stream ->
                        val buffer = ByteArray(48 * 1024)
                        var offset = 0L
                        while (true) {
                            val size = stream.read(buffer)
                            if (size < 0) break
                            result = rpc("chunk", buildJsonObject {
                                put("offset", offset)
                                put("data", Base64.getEncoder().encodeToString(buffer.copyOf(size)))
                            })
                            if (result["success"]?.jsonPrimitive?.booleanOrNull != true) return@withContext result
                            offset += size
                        }
                    }
                    result = rpc("commit")
                }
                buildJsonObject {
                    result.forEach { (key, value) -> put(key, value) }
                    put("name", skill.name)
                    put("usage", "Use skill_root as the working_dir for Termux tools. All relative references, scripts and assets are inside that directory. Run scripts with the Termux interpreter (python3, bash, node, etc.) if their shebang targets another OS. Dependencies are not installed automatically. Write generated output to a separate working directory. This is a versioned copy; edits here do not update The Trickster's Pocket.")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure("skill_sync_failed", e.message.orEmpty()) }
            finally { archive.delete() }
        }
    }

    suspend fun status(): JsonObject = mutex.withLock {
        request(preferences.skillConfigFlow().first(), buildJsonObject { put("action", "status") })
    }
    suspend fun clear(): JsonObject = mutex.withLock {
        request(preferences.skillConfigFlow().first(), buildJsonObject { put("action", "clear") })
    }

    private suspend fun request(config: TermuxSkillConfig, payload: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        require(TermuxSkillConfig.validDirectory(config.directory)) { "Skill directory must be inside the Termux home" }
        val source = context.assets.open("termux/skill_runtime.py").use { it.readBytes() }
        val runtime = "$TERMUX_HOME/.local/share/rikkahub-skill-runtime/${context.packageName}"
        val helper = "$runtime/${SkillPackage.digest(source)}.py"
        val root = config.directory.trimEnd('/') + "/" + context.packageName
        fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val script = """
            umask 077
            command -v python3 >/dev/null || { printf '%s\n' '{"success":false,"error":"python3_required","recovery":"Install Python in Termux: pkg install python"}'; exit 1; }
            mkdir -p ${quote(runtime)} || exit 1
            if [ ! -f ${quote(helper)} ]; then
              printf '%s' '${Base64.getEncoder().encodeToString(source)}' | '$TERMUX_BIN/base64' -d > ${quote(helper)}.tmp || exit 1
              mv ${quote(helper)}.tmp ${quote(helper)} || exit 1
            fi
            exec '$TERMUX_BIN/python3' ${quote(helper)} ${quote(root)} '${Base64.getEncoder().encodeToString(payload.toString().toByteArray(Charsets.UTF_8))}'
        """.trimIndent()
        when (val result = runCommandCapture(context, "$TERMUX_BIN/bash", arrayOf("-c", script), TERMUX_HOME, 60_000)) {
            is CaptureResult.Success -> runCatching { Json.parseToJsonElement(result.stdout.trim()).jsonObject }
                .getOrElse { failure("invalid_skill_bridge_response", result.stderr.take(2000)) }
            CaptureResult.Denied -> failure("termux_permission_denied", "Grant RUN_COMMAND in Settings → Termux.")
            CaptureResult.Timeout -> failure("skill_transfer_timeout", "Retry sync to inspect the installed revision. No skill script was started.")
            is CaptureResult.OtherError -> failure("termux_transport_error", result.message)
        }
    }

    private fun failure(code: String, detail: String) = buildJsonObject {
        put("success", false); put("error", code); put("detail", detail)
    }
}
