package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

internal fun JsonObject.jobString(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.jobLong(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
internal fun JsonObject.jobBool(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull == true
internal val JsonObject.canForgetJob: Boolean get() = jobString("state") in setOf("completed", "failed", "cancelled", "timed_out")

internal data class TermuxJobsState(
    val jobs: List<JsonObject> = emptyList(),
    val totalJobs: Long? = null,
    val activeJobs: Long? = null,
    val nextListCursor: Long? = null,
    val selected: JsonObject? = null,
    val stream: String = "stdout",
    val page: JsonObject? = null,
    val previousCursors: List<Long> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val lastCheckedAt: Long? = null,
    val pageCheckedAt: Long? = null,
    val selectedCheckedAt: Long? = null,
    val mutation: JsonObject? = null,
    val mutationAction: String? = null,
)

/** Conversation-scoped UI gateway. No launch action, automatic retry, or command evaluation.
 * One RPC at a time prevents stale log pages and double mutations; closing the view never kills a job. */
internal class TermuxJobsController(
    private val scope: CoroutineScope,
    private val request: suspend (JsonObject) -> JsonObject,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutable = MutableStateFlow(TermuxJobsState())
    val state = mutable.asStateFlow()

    private fun run(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = mutable.value.copy(error = e.message ?: e.javaClass.simpleName) }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    private suspend fun rpc(payload: JsonObject): JsonObject {
        val result = request(payload)
        if ((result["success"] as? JsonPrimitive)?.booleanOrNull == false || result["error"] != null) {
            error(listOfNotNull(result.jobString("error"), result.jobString("reason"), result.jobString("recovery")).joinToString("\n").ifBlank { "supervisor_response_unavailable" })
        }
        return result
    }

    fun refresh(more: Boolean = false) = run {
        val cursor = if (more) mutable.value.nextListCursor ?: return@run else 0L
        val result = rpc(buildJsonObject { put("action", "list"); put("cursor", cursor) })
        val jobs = (result["jobs"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: error("invalid_job_list")
        val previous = mutable.value
        mutable.value = previous.copy(
            jobs = ((if (more) previous.jobs else emptyList()) + jobs).distinctBy { it.jobString("job_id") },
            nextListCursor = result.jobLong("next_cursor")?.takeIf { result.jobBool("has_more") && it > cursor },
            lastCheckedAt = clock(),
            totalJobs = result.jobLong("total_jobs"), activeJobs = result.jobLong("active_jobs"),
        )
    }

    fun select(job: JsonObject) {
        if (mutable.value.busy || job.jobString("job_id") == null) return
        mutable.value = mutable.value.copy(selected = job, page = null, previousCursors = emptyList(),
            stream = "stdout", pageCheckedAt = null, selectedCheckedAt = mutable.value.lastCheckedAt, mutation = null, mutationAction = null)
        read()
    }

    fun back() {
        if (!mutable.value.busy) mutable.value = mutable.value.copy(selected = null, page = null, error = null)
    }

    fun changeStream(stream: String) {
        if (mutable.value.busy || stream !in setOf("stdout", "stderr")) return
        mutable.value = mutable.value.copy(stream = stream, page = null, previousCursors = emptyList(), pageCheckedAt = null)
        read()
    }

    /** Cursors are byte offsets supplied by the supervisor, never Kotlin character counts. */
    fun read(direction: Int = 0) = run {
        val s = mutable.value
        val id = s.selected?.jobString("job_id") ?: return@run
        val current = s.page?.jobLong("cursor") ?: 0L
        val cursor = when {
            direction > 0 -> s.page?.jobLong("next_cursor")?.takeIf { s.page?.jobBool("has_more") == true && it > current } ?: return@run
            direction < 0 -> s.previousCursors.lastOrNull() ?: return@run
            else -> current
        }
        val page = rpc(buildJsonObject {
            put("action", "read"); put("job_id", id); put("stream", s.stream); put("cursor", cursor); put("max_bytes", 12000)
        })
        require(page.jobString("job_id") == id && page.jobString("stream") == s.stream) { "mismatched_log_response" }
        val history = when { direction > 0 -> s.previousCursors + current; direction < 0 -> s.previousCursors.dropLast(1); else -> s.previousCursors }
        mutable.value = mutable.value.copy(page = page, selected = page, mutation = null, mutationAction = null, previousCursors = history, pageCheckedAt = clock(), selectedCheckedAt = clock(),
            jobs = mutable.value.jobs.map { if (it.jobString("job_id") == id) page else it })
    }

    fun mutate(action: String) = run {
        require(action in setOf("cancel", "forget"))
        val job = mutable.value.selected ?: return@run
        val id = job.jobString("job_id") ?: return@run
        if (action == "forget") require(job.canForgetJob) { "job_not_confirmed_finished" }
        // Never retry a mutation on an ambiguous transport outcome. The user can refresh it.
        val result = rpc(buildJsonObject { put("action", action); put("job_id", id); put("timeout_seconds", 3) })
        require(result.jobString("job_id") == id) { "mismatched_job_response" }
        mutable.value = mutable.value.copy(selected = result, mutation = result, mutationAction = action,
            jobs = mutable.value.jobs.map { if (it.jobString("job_id") == id) result else it },
            selectedCheckedAt = clock(), page = if (action == "forget") null else mutable.value.page)
    }
}
