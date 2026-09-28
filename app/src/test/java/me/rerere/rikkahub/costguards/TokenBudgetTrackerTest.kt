package me.rerere.rikkahub.costguards

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.provider.GenerationRequestMetrics
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlin.uuid.Uuid

class TokenBudgetTrackerTest {

    private fun request(id: String, usage: TokenUsage?) = GenerationRequestMetrics(
        requestId = id, phase = "COMPLETED", totalMs = 10, queueMs = 0, usage = usage,
    )

    @Test fun `multiple requests merged in one assistant message all consume hard budget`() {
        val message = mkMessage(1100, 100).copy(generationMetrics = listOf(
            request("round-one", TokenUsage(promptTokens = 1000, completionTokens = 100)),
            request("round-two", TokenUsage(promptTokens = 1100, completionTokens = 100)),
        ))
        val totals = TokenBudgetTracker.aggregateTask(listOf(mkConversation(listOf(message))))
        assertEquals(2300L, totals.totalTokens)
        assertEquals(2, totals.messageCount)
        assertEquals(1200L, totals.perMessageMax)
        assertEquals(TokenBudgetTracker.BudgetStatus.OVER_HARD, TokenBudgetTracker.classify(totals, null, 1500))
    }

    @Test fun `a request without usage stays unknown instead of reusing previous message measurement`() {
        val message = mkMessage(1000, 100).copy(generationMetrics = listOf(
            request("measured", TokenUsage(promptTokens = 1000, completionTokens = 100)),
            request("unmeasured", null),
        ))
        val totals = TokenBudgetTracker.aggregateTask(listOf(mkConversation(listOf(message))))
        assertEquals(1100L, totals.totalTokens)
        assertEquals(1, totals.messageCount)
        assertEquals(1, totals.unmeasuredMessages)
    }

    @Test fun `copied request metrics are counted once across branches with distinct message ids`() {
        val first = mkMessage(10, 5).copy(generationMetrics = listOf(request("same-request", TokenUsage(10, 5))))
        val fork = first.copy(id = Uuid.random())
        val totals = TokenBudgetTracker.aggregateTask(listOf(mkConversation(listOf(first, fork))))
        assertEquals(15L, totals.totalTokens)
        assertEquals(1, totals.messageCount)
    }

    @Test fun `older fork snapshot with same message id cannot hide newer request rounds`() {
        val earlier = mkMessage(10, 5).copy(generationMetrics = listOf(request("first", TokenUsage(10, 5))))
        val later = earlier.copy(generationMetrics = earlier.generationMetrics + request("second", TokenUsage(20, 10)))
        for (copies in listOf(listOf(earlier, later), listOf(later, earlier))) {
            val totals = TokenBudgetTracker.aggregateTask(copies.map { mkConversation(listOf(it)) })
            assertEquals(45L, totals.totalTokens)
            assertEquals(2, totals.messageCount)
        }
    }

    @Test fun `legacy snapshot of measured request does not count usage twice`() {
        val legacy = mkMessage(10, 5)
        val measured = legacy.copy(generationMetrics = listOf(request("first", TokenUsage(10, 5))))
        val totals = TokenBudgetTracker.aggregateTask(listOf(mkConversation(listOf(legacy)), mkConversation(listOf(measured))))
        assertEquals(15L, totals.totalTokens)
        assertEquals(1, totals.messageCount)
    }

    private fun mkMessage(prompt: Int, completion: Int, total: Int = 0): UIMessage {
        val effectiveTotal = if (total > 0) total else prompt + completion
        return UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = emptyList(),
            usage = TokenUsage(
                promptTokens = prompt,
                completionTokens = completion,
                totalTokens = effectiveTotal,
            ),
        )
    }

    private fun mkConversation(messages: List<UIMessage>): Conversation {
        val nodes = messages.map { msg ->
            MessageNode(
                id = Uuid.random(),
                messages = listOf(msg),
                selectIndex = 0,
            )
        }
        return Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            title = "test",
            messageNodes = nodes,
        )
    }

    @Test fun `aggregate sums prompt and completion tokens across messages`() {
        val conv = mkConversation(listOf(
            mkMessage(prompt = 100, completion = 50),
            mkMessage(prompt = 200, completion = 100),
            mkMessage(prompt = 50, completion = 25),
        ))
        val totals = TokenBudgetTracker.aggregate(conv)
        assertEquals(350L, totals.inputTokens)
        assertEquals(175L, totals.outputTokens)
        assertEquals(525L, totals.totalTokens)
        assertEquals(3, totals.messageCount)
    }

    @Test fun `aggregate ignores messages without usage`() {
        val conv = mkConversation(listOf(
            mkMessage(prompt = 100, completion = 50),
            UIMessage(id = Uuid.random(), role = MessageRole.USER, parts = emptyList()),
            mkMessage(prompt = 200, completion = 100),
        ))
        val totals = TokenBudgetTracker.aggregate(conv)
        assertEquals(300L, totals.inputTokens)
        assertEquals(150L, totals.outputTokens)
        assertEquals(2, totals.messageCount)
    }

    @Test fun `perMessageMax tracks largest single message`() {
        val conv = mkConversation(listOf(
            mkMessage(prompt = 100, completion = 50),
            mkMessage(prompt = 1000, completion = 500),
            mkMessage(prompt = 200, completion = 100),
        ))
        val totals = TokenBudgetTracker.aggregate(conv)
        assertEquals(1500L, totals.perMessageMax)
    }

    @Test fun `classify NO_BUDGET when both caps null`() {
        val totals = TokenBudgetTracker.Totals(0, 0, 0, 0, 0)
        assertEquals(TokenBudgetTracker.BudgetStatus.NO_BUDGET,
            TokenBudgetTracker.classify(totals, null, null))
    }

    @Test fun `classify UNDER_SOFT when below soft cap`() {
        val totals = TokenBudgetTracker.Totals(0, 0, 5_000, 0, 1)
        assertEquals(TokenBudgetTracker.BudgetStatus.UNDER_SOFT,
            TokenBudgetTracker.classify(totals, softCap = 50_000, hardCap = 200_000))
    }

    @Test fun `classify WARN when above soft below hard`() {
        val totals = TokenBudgetTracker.Totals(0, 0, 75_000, 0, 1)
        assertEquals(TokenBudgetTracker.BudgetStatus.WARN,
            TokenBudgetTracker.classify(totals, softCap = 50_000, hardCap = 200_000))
    }

    @Test fun `classify OVER_HARD when at or above hard`() {
        val atCap = TokenBudgetTracker.Totals(0, 0, 200_000, 0, 1)
        val overCap = TokenBudgetTracker.Totals(0, 0, 250_000, 0, 1)
        assertEquals(TokenBudgetTracker.BudgetStatus.OVER_HARD,
            TokenBudgetTracker.classify(atCap, softCap = 50_000, hardCap = 200_000))
        assertEquals(TokenBudgetTracker.BudgetStatus.OVER_HARD,
            TokenBudgetTracker.classify(overCap, softCap = 50_000, hardCap = 200_000))
    }

    @Test fun `classify with only hard cap configured`() {
        val totals = TokenBudgetTracker.Totals(0, 0, 100_000, 0, 1)
        assertEquals(TokenBudgetTracker.BudgetStatus.UNDER_SOFT,
            TokenBudgetTracker.classify(totals, softCap = null, hardCap = 200_000))
    }

    @Test fun `task snapshot includes siblings descendants and live usage without duplicates`() = runBlocking {
        val root = mkConversation(listOf(mkMessage(100, 20)))
        val child = mkConversation(listOf(mkMessage(10, 5))).copy(parentConversationId = root.id)
        val sibling = mkConversation(listOf(mkMessage(30, 5))).copy(parentConversationId = root.id)
        val grandchild = mkConversation(listOf(mkMessage(7, 3))).copy(parentConversationId = child.id)
        val liveChild = child.copy(messageNodes = listOf(MessageNode(
            id = Uuid.random(), messages = listOf(mkMessage(200, 50)), selectIndex = 0,
        )))
        val saved = listOf(root, child, sibling, grandchild).associateBy { it.id }
        val snapshot = TokenBudgetTracker.taskSnapshot(liveChild, 300, 400,
            load = { saved[it] },
            children = { id -> saved.values.filter { it.parentConversationId == id }.map { it.id }.let { it + it } },
        )
        assertEquals(root.id, snapshot.rootConversationId)
        assertEquals(4, snapshot.conversationCount)
        assertEquals(415L, snapshot.totals.totalTokens)
        assertEquals(TokenBudgetTracker.BudgetStatus.OVER_HARD, snapshot.status)
    }

    @Test fun `all alternatives consume budget while copied message ids are counted once`() {
        val first = mkMessage(10, 5)
        val second = mkMessage(20, 5)
        val root = mkConversation(emptyList()).copy(messageNodes = listOf(
            MessageNode(id = Uuid.random(), messages = listOf(first, second), selectIndex = 0),
        ))
        val child = mkConversation(listOf(first))
        assertEquals(40L, TokenBudgetTracker.aggregateTask(listOf(root, child, root)).totalTokens)
        assertEquals(2, TokenBudgetTracker.aggregateTask(listOf(root, child)).messageCount)
    }

    @Test fun `missing provider usage is visible and never represented as measured usage`() {
        val unknown = UIMessage(id = Uuid.random(), role = MessageRole.ASSISTANT, parts = emptyList())
        val totals = TokenBudgetTracker.aggregateTask(listOf(mkConversation(listOf(unknown, mkMessage(5, 5)))))
        assertEquals(1, totals.unmeasuredMessages)
        assertEquals(1, totals.messageCount)
        assertEquals(10L, totals.totalTokens)
    }

    @Test fun `cycle in imported relationships terminates and keeps unique conversations`() = runBlocking {
        val first = mkConversation(listOf(mkMessage(5, 5)))
        val second = mkConversation(listOf(mkMessage(10, 10))).copy(parentConversationId = first.id)
        val cyclicFirst = first.copy(parentConversationId = second.id)
        val saved = listOf(cyclicFirst, second).associateBy { it.id }
        val snapshot = TokenBudgetTracker.taskSnapshot(cyclicFirst, null, null,
            load = { saved[it] }, children = { id -> saved.values.filter { it.parentConversationId == id }.map { it.id } })
        assertEquals(30L, snapshot.totals.totalTokens)
        assertEquals(2, snapshot.conversationCount)
    }
}
