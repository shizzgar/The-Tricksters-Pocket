package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.GenerationPhase
import me.rerere.ai.provider.GenerationProgress
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ContextUsageSnapshot
import org.junit.Assert.*
import org.junit.Test

class AgentOverlayStateTest {
    private fun session(id: String, used: Int, limit: Int? = 1000) = AgentOverlaySession(
        id, "Bro $id", AgentOverlayPhase.RECEIVING, null,
        ContextUsageSnapshot(used, limit, null, 100, 800, true, false, false, true, false),
    )

    @Test fun `parallel contexts remain separate and highest occupancy is shown`() {
        val small = session("small", 850)
        val large = session("large", 2000, 10000)
        val result = selectAgentOverlayState(listOf(large, small))!!
        assertEquals("small", result.session.id)
        assertEquals(85, result.session.context.percent)
        assertEquals(2, result.activeCount)
        assertNull(selectAgentOverlayState(emptyList()))
    }

    @Test fun `unknown limits stay unknown and selection is stable`() {
        val unknown = session("unknown", 999999, null)
        assertNull(selectAgentOverlayState(listOf(unknown))!!.session.context.fraction)
        assertEquals("known", selectAgentOverlayState(listOf(unknown, session("known", 100)))!!.session.id)
        assertEquals("a", selectAgentOverlayState(listOf(session("z", 50), session("a", 50)))!!.session.id)
    }

    @Test fun `removing one job preserves others and late updates cannot reopen completed overlay`() = runBlocking {
        val first = MutableStateFlow(session("a", 900))
        val second = MutableStateFlow(session("b", 600))
        val active = MutableStateFlow<List<Flow<AgentOverlaySession>>>(listOf(first, second))
        val updates = Channel<AgentOverlayState?>(Channel.UNLIMITED)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            agentOverlayStates(active).collect { updates.send(it) }
        }
        suspend fun next() = withTimeout(5000) { updates.receive() }
        try {
            assertEquals("a", next()!!.session.id)
            active.value = listOf(second)
            val remaining = next()!!
            assertEquals("b", remaining.session.id)
            assertEquals(1, remaining.activeCount)
            // This simulates a new compacted request while the old session still emits chunks.
            first.value = session("a", 990)
            second.value = session("b", 100).let { it.copy(context = it.context.copy(compacted = true)) }
            assertEquals(10, next()!!.session.context.percent)
            active.value = emptyList()
            assertNull(next())
            first.value = session("a", 1000)
            second.value = session("b", 200)
            active.value = listOf(first)
            val resumed = next()!!
            assertEquals("a", resumed.session.id)
            assertEquals(100, resumed.session.context.percent)
        } finally {
            job.cancel()
            updates.close()
        }
    }

    @Test fun `tool execution and approval take precedence over completed model request`() {
        val progress = GenerationProgress(1, GenerationPhase.COMPLETED, 0, finishedAt = 10)
        assertEquals(AgentOverlayPhase.CONTINUING, agentOverlayPhase(progress, emptyList()))
        val running = UIMessagePart.Tool("call", "termux", "{}", executionStartedAt = 1)
        assertEquals(AgentOverlayPhase.TOOL, agentOverlayPhase(progress, listOf(running)))
        val pending = running.copy(executionStartedAt = null, approvalState = ToolApprovalState.Pending)
        assertEquals(AgentOverlayPhase.APPROVAL, agentOverlayPhase(progress, listOf(pending)))
        val completed = running.copy(output = listOf(UIMessagePart.Text("done")))
        assertEquals(AgentOverlayPhase.CONTINUING, agentOverlayPhase(progress, listOf(completed)))
    }
}
