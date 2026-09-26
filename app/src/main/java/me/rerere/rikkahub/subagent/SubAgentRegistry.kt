package me.rerere.rikkahub.subagent

import kotlinx.coroutines.Job
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/** Durable run state and process-local supervision jobs. Reservation and persistence are
 * serialized together, so concurrent dispatches and follow-ups cannot oversubscribe slots. */
class SubAgentRegistry(private val storageFile: java.io.File? = null) {

    private val _runs = MutableStateFlow<Map<String, SubAgentRun>>(load())
    val runs: StateFlow<Map<String, SubAgentRun>> = _runs

    /**
     * Side-table of cancellable Jobs for currently RUNNING runs. Removed once the run
     * reaches a terminal status. Kept separate from the StateFlow because [Job] is not
     * serialisable and we don't want UI consumers re-collecting on Job-pointer churn.
     */
    private val activeJobs: ConcurrentHashMap<String, Job> = ConcurrentHashMap()

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun load(): Map<String, SubAgentRun> {
        val file = storageFile?.takeIf { it.exists() } ?: return emptyMap()
        return readRuns(file).mapValues { (_, run) ->
                if (run.status.isActive() && run.status != SubAgentStatus.WAITING_APPROVAL)
                    run.copy(status = SubAgentStatus.PROCESS_LOST, error = "Process interrupted; review before resuming") else run
            }
    }
    private fun readRuns(file: java.io.File): Map<String, SubAgentRun> = runCatching {
        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<Map<String, SubAgentRun>>(file.readText())
    }.getOrElse {
        // Preserve evidence of corruption. Missing registry entries cannot execute because
        // ensureChildTurn requires both a durable run and a valid independent policy.
        file.copyTo(java.io.File(file.parentFile, file.name + ".corrupt-${System.currentTimeMillis()}"))
        emptyMap()
    }
    private val outboxFile get() = storageFile?.let { java.io.File(it.parentFile, it.name + ".outbox") }
    private var outbox: Map<String, SubAgentRun> = outboxFile?.takeIf { it.exists() }?.let {
        readRuns(it)
    }.orEmpty()
    private fun saveOutbox(next: Map<String, SubAgentRun>) {
        outboxFile?.let { file ->
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            val temp = java.io.File(file.parentFile, file.name + ".tmp")
            java.io.FileOutputStream(temp).use {
                it.write(json.encodeToString(kotlinx.serialization.builtins.MapSerializer(String.serializer(), SubAgentRun.serializer()), next).toByteArray()); it.fd.sync()
            }
            check(temp.renameTo(file))
        }
        outbox = next
    }
    @Synchronized fun forgetConversation(conversationId: String) {
        val removed = _runs.value.values.filter { it.id == conversationId || it.conversationId == conversationId || it.parentChatId == conversationId }
        removed.forEach { activeJobs.remove(it.id)?.cancel() }
        saveOutbox(outbox.filterValues { it.id != conversationId && it.conversationId != conversationId && it.parentChatId != conversationId })
        publish(_runs.value - removed.map { it.id }.toSet())
    }
    @Synchronized fun queueCompletion(run: SubAgentRun) {
        if (!run.runInBackground || run.status.isActive() || run.status == SubAgentStatus.PROCESS_LOST || run.resultDeliveredEpoch >= run.executionEpoch) return
        val key = "${run.id}:${run.executionEpoch}"
        if (key !in outbox) saveOutbox(outbox + (key to run))
    }
    @Synchronized fun pendingCompletions(): List<SubAgentRun> = outbox.values.toList()
    @Synchronized fun acknowledgeCompletion(runId: String, epoch: Int) {
        saveOutbox(outbox - "$runId:$epoch")
        update(runId) { it.copy(resultDeliveredEpoch = maxOf(it.resultDeliveredEpoch, epoch)) }
    }

    private fun publish(next: Map<String, SubAgentRun>) {
        storageFile?.let { file ->
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            val temp = java.io.File(file.parentFile, file.name + ".tmp")
            java.io.FileOutputStream(temp).use {
                it.write(json.encodeToString(kotlinx.serialization.builtins.MapSerializer(String.serializer(), SubAgentRun.serializer()), next).toByteArray())
                it.fd.sync()
            }
            check(temp.renameTo(file)) { "Cannot persist subagent state" }
        }
        _runs.value = next
    }
    @Synchronized fun tryReserve(run: SubAgentRun, perAssistantCap: Int, globalCap: Int = SubAgentDefaults.GLOBAL_CONCURRENCY_CAP): Boolean {
        if (_runs.value[run.id]?.status?.isActive() == true) return false
        if (globalActiveCount() >= globalCap || activeCountForAssistant(run.parentAssistantId) >= perAssistantCap) return false
        publish(pruneIfNeeded(_runs.value) + (run.id to run))
        return true
    }
    @Synchronized fun addPending(run: SubAgentRun, job: Job? = null) {
        publish(pruneIfNeeded(_runs.value) + (run.id to run))
        if (job != null) activeJobs[run.id] = job
    }
    @Synchronized fun update(id: String, transform: (SubAgentRun) -> SubAgentRun) {
        val existing = _runs.value[id] ?: return
        publish(_runs.value + (id to transform(existing)))
    }
    fun hasJob(id: String): Boolean = activeJobs[id]?.isCompleted == false
    fun job(id: String): Job? = activeJobs[id]

    @Synchronized fun setJob(id: String, job: Job, replace: Boolean = false): Boolean {
        if (_runs.value[id]?.status?.isActive() != true) { job.cancel(); return false }
        if (!replace && activeJobs[id]?.isCompleted == false) { job.cancel(); return false }
        activeJobs[id] = job
        return true
    }

    fun get(id: String): SubAgentRun? = _runs.value[id]

    fun list(activeOnly: Boolean): List<SubAgentRun> {
        val all = _runs.value.values
        return if (activeOnly) all.filter { it.status.isActive() }
        else all.toList()
    }

    fun activeCountForAssistant(parentAssistantId: String): Int =
        _runs.value.values.count {
            it.parentAssistantId == parentAssistantId &&
                (it.status.isActive())
        }

    fun globalActiveCount(): Int =
        _runs.value.values.count {
            it.status.isActive()
        }

    /**
     * Cancel a single run by id. Returns true if a cancellable job existed; false if the
     * run was already in a terminal state or if the id is unknown. Marking the status to
     * CANCELLED is the caller's job (typically the engine after the Job's onCompletion
     * fires) so we don't double-write.
     */
    fun requestCancel(id: String): Boolean {
        val job = activeJobs.remove(id) ?: return false
        job.cancel()
        return true
    }

    /**
     * Cancel every currently-active run dispatched from [parentChatId]. Hooked into the
     * Telegram /stop handler and the in-app stop button so a single tick takes down the
     * parent generation AND all of its sub-agents. Returns the count cancelled.
     */
    fun cancelAllForParent(parentChatId: String): Int {
        var count = 0
        val toCancel = _runs.value.values
            .filter { it.parentChatId == parentChatId && (it.status.isActive()) }
            .map { it.id }
        for (runId in toCancel) {
            if (requestCancel(runId)) count++
        }
        return count
    }

    fun clearJob(id: String, expected: Job? = null) {
        if (expected == null) activeJobs.remove(id) else activeJobs.remove(id, expected)
    }

    private fun pruneIfNeeded(current: Map<String, SubAgentRun>): Map<String, SubAgentRun> {
        if (current.size < SubAgentDefaults.REGISTRY_LRU_CAP) return current
        // Evict the oldest TERMINAL run; never evict a running one. If every run is
        // running, the cap would be exceeded — we accept this since it should be rare
        // (50 concurrent sub-agents would already have been blocked by the global cap of 30).
        val terminalSorted = current.values
            .filter { !it.status.isActive() && it.resultDeliveredEpoch >= it.executionEpoch }
            .sortedBy { it.finishedAtMs ?: it.startedAtMs }
        val toEvictId = terminalSorted.firstOrNull()?.id
        return if (toEvictId != null) current - toEvictId else current
    }
}
