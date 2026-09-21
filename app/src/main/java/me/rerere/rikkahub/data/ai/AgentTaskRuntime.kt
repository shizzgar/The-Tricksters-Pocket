package me.rerere.rikkahub.data.ai

import kotlinx.serialization.Serializable

@Serializable
enum class GenerationStopReason {
    COMPLETED, STEP_LIMIT, CYCLE_DEADLINE, COMPACTION_LIMIT, WAITING_APPROVAL,
    LOOP_DETECTED, NO_PROGRESS, NETWORK_WAIT, FAILED, CANCELLED, TASK_DEADLINE, PROCESS_LOST,
}

class AgentTaskCycleState(var loopGuardTrips: Int = 0)

data class GenerationSliceOutcome(val reason: GenerationStopReason, val steps: Int = 0)

fun GenerationStopReason.canContinueAutomatically() = this in setOf(
    GenerationStopReason.STEP_LIMIT, GenerationStopReason.CYCLE_DEADLINE, GenerationStopReason.COMPACTION_LIMIT,
)

@Serializable
data class AgentTaskRecord(
    val conversationId: String,
    val runId: String = java.util.UUID.randomUUID().toString(),
    val status: String = "running",
    val reason: GenerationStopReason? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val cycles: Int = 0,
    val loopGuardTrips: Int = 0,
    val steps: Long = 0,
    val checkpoint: String? = null,
    val recoverAutomatically: Boolean = true,
    val detail: String? = null,
)

/** Pure policy: an exhausted cycle can continue; approvals/loops/unknown failures cannot. */
internal fun shouldContinueTask(enabled: Boolean, outcome: GenerationSliceOutcome, madeProgress: Boolean): Boolean =
    enabled && madeProgress && outcome.reason.canContinueAutomatically()

/** Content checkpoint excludes display-only telemetry and mutable titles. */
fun conversationCheckpoint(messages: List<me.rerere.ai.ui.UIMessage>): String {
    val stable = messages.map { it.copy(generationMetrics = emptyList(), usage = null, finishedAt = null) }
    val bytes = kotlinx.serialization.json.Json.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(me.rerere.ai.ui.UIMessage.serializer()), stable
    ).toByteArray(Charsets.UTF_8)
    return java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Explicit caller limits for scoped child runs; never reset by parent task continuation. */
object AgentTaskPolicy {
    private val steps = java.util.concurrent.ConcurrentHashMap<String, Int>()
    fun setStepLimit(id: String, limit: Int) { require(limit > 0); steps[id] = limit }
    fun stepLimit(id: String): Int? = steps[id]
    fun clear(id: String) { steps.remove(id) }
}
