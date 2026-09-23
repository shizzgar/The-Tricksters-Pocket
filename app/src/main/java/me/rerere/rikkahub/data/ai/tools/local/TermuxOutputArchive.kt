package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.util.UUID

/** Capture output is archived before the LLM preview cap, independently of workspace tools. */
internal object TermuxOutputArchive {
    private const val STREAM_LIMIT = 8 * 1024 * 1024
    private const val STORE_LIMIT = 128L * 1024 * 1024
    private fun root(context: Context) = File(context.filesDir, "termux-output")
    private fun ownerRoot(context: Context, owner: String) = File(root(context), sessionOwner(owner))

    @Synchronized
    fun save(context: Context, owner: String, stdout: String, stderr: String): JsonObject = try {
        val used = root(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val out = takeFirstUtf8Bytes(stdout, STREAM_LIMIT)
        val err = takeFirstUtf8Bytes(stderr, STREAM_LIMIT)
        if (used + out.toByteArray().size + err.toByteArray().size > STORE_LIMIT) {
            buildJsonObject { put("archive_error", "private_output_quota_exhausted"); put("archive_limit_bytes", STORE_LIMIT) }
        } else {
            val id = UUID.randomUUID().toString()
            val dir = File(ownerRoot(context, owner), id).apply { mkdirs() }
            File(dir, "stdout.txt").writeText(out)
            File(dir, "stderr.txt").writeText(err)
            val result = buildJsonObject {
                put("output_ref", id)
                put("archive_truncated", out.length < stdout.length || err.length < stderr.length)
                put("stdout_total_bytes", stdout.toByteArray().size)
                put("stderr_total_bytes", stderr.toByteArray().size)
                put("output_read_tool", "termux_output_read")
            }
            File(dir, "metadata.json").writeText(result.toString())
            result
        }
    } catch (e: java.io.IOException) {
        buildJsonObject { put("archive_error", "write_failed"); put("archive_reason", e.message.orEmpty()) }
    }

    fun read(context: Context, owner: String, input: JsonObject): JsonObject {
        val ref = input["output_ref"]?.jsonPrimitive?.content.orEmpty()
        require(ref.matches(Regex("[a-f0-9-]{36}"))) { "Invalid output_ref" }
        val stream = input["stream"]?.jsonPrimitive?.content ?: "stdout"
        require(stream in listOf("stdout", "stderr")) { "Invalid stream" }
        val dir = File(ownerRoot(context, owner), ref)
        if (!File(dir, "metadata.json").isFile) return buildJsonObject { put("error", "output_not_found") }
        val cursor = (input["cursor"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
        val limit = (input["max_chars"]?.jsonPrimitive?.intOrNull ?: 8000).coerceIn(256, 16000)
        // Files are bounded at write time. Decode before paging so byte boundaries cannot
        // corrupt Unicode. Cursors are UTF-16 character offsets returned by this tool.
        val text = File(dir, "$stream.txt").readText()
        var start = cursor.coerceAtMost(text.length)
        if (start > 0 && start < text.length && text[start].isLowSurrogate()) start--
        var end = (start + limit).coerceAtMost(text.length)
        if (end > start && end < text.length && text[end - 1].isHighSurrogate()) end--
        return buildJsonObject {
            Json.parseToJsonElement(File(dir, "metadata.json").readText()).jsonObject.forEach { (k,v) -> put(k,v) }
            put("stream", stream); put("text", text.substring(start, end)); put("cursor", start)
            put("next_cursor", end); put("has_more", end < text.length); put("stored_chars", text.length)
        }
    }
}

fun termuxOutputReadTool(context: Context, owner: String?): Tool = Tool(
    name = "termux_output_read",
    description = "Read archived Termux capture output beyond the chat preview, scoped to this conversation. Pass output_ref from termux_run_command and returned next_cursor unchanged. Archive caps/loss are explicit. This does not execute commands.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("output_ref", buildJsonObject { put("type", "string") })
        put("stream", buildJsonObject { put("type", "string"); put("description", "stdout or stderr") })
        put("cursor", buildJsonObject { put("type", "integer"); put("description", "Returned character cursor; default 0") })
        put("max_chars", buildJsonObject { put("type", "integer"); put("description", "256–16000, default 8000") })
    }, required = listOf("output_ref")) },
    execute = { input -> withContext(Dispatchers.IO) {
        val result = if (owner == null) buildJsonObject { put("error", "conversation_identity_required") }
        else try { TermuxOutputArchive.read(context, owner, input.jsonObject) }
        catch (e: IllegalArgumentException) { buildJsonObject { put("error", "invalid_argument"); put("reason", e.message.orEmpty()) } }
        catch (e: java.io.IOException) { buildJsonObject { put("error", "archive_read_failed"); put("reason", e.message.orEmpty()) } }
        listOf(UIMessagePart.Text(result.toString()))
    } },
)
