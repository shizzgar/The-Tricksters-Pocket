package me.rerere.rikkahub.costguards

import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.core.TokenUsage

/** Meter requests which do not create chat messages (compaction, titles, suggestions). */
object AuxiliaryTokenUsageStore {
    @Serializable private data class Meter(
        val requests: Set<String> = emptySet(),
        val input: Long = 0,
        val output: Long = 0,
        val total: Long = 0,
        val maximum: Long = 0,
        val measured: Int = 0,
        val unknown: Int = 0,
    )
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var root: File? = null
    private val cache = mutableMapOf<String, Meter>()
    private val deleted = mutableSetOf<String>()
    @Synchronized fun initialize(filesDir: File) {
        val target = File(filesDir, "auxiliary-token-usage")
        if (root == target) return
        check(target.isDirectory || target.mkdirs())
        root = target
        cache.clear()
        deleted.clear()
    }
    private fun file(id: String): File? {
        require(runCatching { java.util.UUID.fromString(id) }.isSuccess)
        return root?.let { File(it, "$id.json") }
    }
    private fun read(id: String): Meter = cache.getOrPut(id) {
        file(id)?.takeIf { it.exists() }?.let { json.decodeFromString<Meter>(it.readText()) } ?: Meter()
    }
    @Synchronized fun record(conversationId: String, requestId: String, usage: TokenUsage?) {
        if (conversationId in deleted) return
        val old = read(conversationId)
        if (requestId in old.requests) return
        val input = usage?.promptTokens?.toLong()?.coerceAtLeast(0) ?: 0
        val output = usage?.completionTokens?.toLong()?.coerceAtLeast(0) ?: 0
        val total = usage?.totalTokens?.takeIf { it > 0 }?.toLong() ?: (input + output)
        val next = old.copy(requests = old.requests + requestId, input = old.input + input,
            output = old.output + output, total = old.total + total, maximum = maxOf(old.maximum, total),
            measured = old.measured + if (usage == null) 0 else 1,
            unknown = old.unknown + if (usage == null) 1 else 0)
        file(conversationId)?.let { file ->
            val temp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(temp).use { it.write(json.encodeToString(Meter.serializer(), next).toByteArray()); it.fd.sync() }
            check(temp.renameTo(file)) { "Could not persist auxiliary token usage" }
        }
        cache[conversationId] = next
    }
    @Synchronized fun totals(conversationId: String): TokenBudgetTracker.Totals = read(conversationId).let {
        TokenBudgetTracker.Totals(it.input, it.output, it.total, it.maximum, it.measured, it.unknown)
    }
    @Synchronized fun delete(conversationId: String) { deleted.add(conversationId); cache.remove(conversationId); file(conversationId)?.delete() }
}
