package me.rerere.rikkahub.costguards

import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlinx.coroutines.flow.first
import kotlin.uuid.Uuid

/**
 * Phase 15 — pure-function token aggregator over a [Conversation]. Walks the currently-
 * selected branch (one message per node, picked via `selectIndex`), sums every
 * non-null [TokenUsage], and reports the running totals.
 *
 * The runtime and UI share taskSnapshot: it includes the complete descendant task tree,
 * counts each stored message only once, and overlays the caller's live conversation.
 * Missing provider usage remains visible; it must never be presented as a measured zero.
 */
object TokenBudgetTracker {

    data class Totals(
        val inputTokens: Long,
        val outputTokens: Long,
        val totalTokens: Long,
        val perMessageMax: Long,
        val messageCount: Int,
        val unmeasuredMessages: Int = 0,
    )

    enum class BudgetStatus {
        UNDER_SOFT,
        WARN,            // crossed soft, below hard
        OVER_HARD,       // crossed hard
        NO_BUDGET,       // no caps configured
    }

    data class Snapshot(
        val totals: Totals,
        val softCap: Int?,
        val hardCap: Int?,
        val status: BudgetStatus,
        val rootConversationId: Uuid? = null,
        val conversationCount: Int = 1,
    )

    fun aggregate(conversation: Conversation): Totals {
        var input = 0L
        var output = 0L
        var total = 0L
        var perMax = 0L
        var count = 0
        for (node in conversation.messageNodes) {
            val msg = node.messages.getOrNull(node.selectIndex) ?: continue
            val usage = msg.usage ?: continue
            input += usage.promptTokens.toLong()
            output += usage.completionTokens.toLong()
            val totalThis = (usage.totalTokens.takeIf { it > 0 }
                ?: (usage.promptTokens + usage.completionTokens)).toLong()
            total += totalThis
            if (totalThis > perMax) perMax = totalThis
            count++
        }
        return Totals(
            inputTokens = input,
            outputTokens = output,
            totalTokens = total,
            perMessageMax = perMax,
            messageCount = count,
        )
    }

    fun classify(totals: Totals, softCap: Int?, hardCap: Int?): BudgetStatus {
        // No budget configured → no-budget. Spec calls this "off"; tool surface shows
        // numbers but doesn't recommend action.
        if (softCap == null && hardCap == null) return BudgetStatus.NO_BUDGET
        if (hardCap != null && totals.totalTokens >= hardCap) return BudgetStatus.OVER_HARD
        if (softCap != null && totals.totalTokens >= softCap) return BudgetStatus.WARN
        return BudgetStatus.UNDER_SOFT
    }

    fun snapshot(conversation: Conversation, softCap: Int?, hardCap: Int?): Snapshot {
        val totals = aggregate(conversation)
        return Snapshot(
            totals = totals,
            softCap = softCap,
            hardCap = hardCap,
            status = classify(totals, softCap, hardCap),
        )
    }

    suspend fun taskRoot(conversation: Conversation, conversationRepo: ConversationRepository): Conversation =
        taskRoot(conversation) { conversationRepo.getConversationById(it) }

    internal suspend fun taskRoot(conversation: Conversation, load: suspend (Uuid) -> Conversation?): Conversation {
        val seen = linkedMapOf(conversation.id to conversation)
        var current = conversation
        while (true) {
            val parentId = current.parentConversationId ?: return current
            if (parentId in seen) return seen.values.minBy { it.id.toString() }
            val parent = load(parentId) ?: return current
            seen[parent.id] = parent
            current = parent
        }
    }

    suspend fun taskSnapshot(
        conversation: Conversation,
        conversationRepo: ConversationRepository,
        softCap: Int?,
        hardCap: Int?,
    ): Snapshot = taskSnapshot(
        conversation, softCap, hardCap,
        load = { conversationRepo.getConversationById(it) },
        children = { conversationRepo.observeChildConversations(it).first().map { child -> child.id } },
    )

    internal suspend fun taskSnapshot(
        conversation: Conversation,
        softCap: Int?,
        hardCap: Int?,
        load: suspend (Uuid) -> Conversation?,
        children: suspend (Uuid) -> List<Uuid>,
    ): Snapshot {
        val root = taskRoot(conversation, load)
        val pending = ArrayDeque<Uuid>().apply { add(root.id) }
        val seen = mutableSetOf<Uuid>()
        val conversations = mutableListOf<Conversation>()
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (!seen.add(id)) continue
            val current = if (id == conversation.id) conversation else load(id) ?: continue
            conversations.add(current)
            children(id).forEach { if (it !in seen) pending.add(it) }
        }
        // A newly-created/live child may not be present in the DAO list yet.
        if (conversations.none { it.id == conversation.id }) conversations.add(conversation)
        val history = aggregateTask(conversations)
        val auxiliary = conversations.distinctBy { it.id }.map { AuxiliaryTokenUsageStore.totals(it.id.toString()) }
        val totals = history.copy(
            inputTokens = history.inputTokens + auxiliary.sumOf { it.inputTokens },
            outputTokens = history.outputTokens + auxiliary.sumOf { it.outputTokens },
            totalTokens = history.totalTokens + auxiliary.sumOf { it.totalTokens },
            perMessageMax = maxOf(history.perMessageMax, auxiliary.maxOfOrNull { it.perMessageMax } ?: 0L),
            messageCount = history.messageCount + auxiliary.sumOf { it.messageCount },
            unmeasuredMessages = history.unmeasuredMessages + auxiliary.sumOf { it.unmeasuredMessages },
        )
        return Snapshot(totals, softCap, hardCap, classify(totals, softCap, hardCap), root.id, conversations.size)
    }

    /** Count all stored alternatives: changing the selected answer must not reset spent usage. */
    fun aggregateTask(conversations: List<Conversation>): Totals {
        var input = 0L
        var output = 0L
        var total = 0L
        var maximum = 0L
        var count = 0
        var unknown = 0
        val seen = mutableSetOf<Uuid>()
        for (conversation in conversations.distinctBy { it.id }) {
            for (message in conversation.messageNodes.flatMap { it.messages }) {
                if (!seen.add(message.id)) continue
                val usage = message.usage
                if (usage == null) {
                    if (message.role == me.rerere.ai.core.MessageRole.ASSISTANT) unknown++
                    continue
                }
                val measuredInput = usage.promptTokens.coerceAtLeast(0).toLong()
                val measuredOutput = usage.completionTokens.coerceAtLeast(0).toLong()
                val measuredTotal = usage.totalTokens.takeIf { it > 0 }?.toLong() ?: (measuredInput + measuredOutput)
                input += measuredInput
                output += measuredOutput
                total += measuredTotal
                maximum = maxOf(maximum, measuredTotal)
                count++
            }
        }
        return Totals(input, output, total, maximum, count, unknown)
    }
}
