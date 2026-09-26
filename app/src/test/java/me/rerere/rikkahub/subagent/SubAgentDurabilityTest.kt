package me.rerere.rikkahub.subagent

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SubAgentDurabilityTest {
    @get:Rule val temp = TemporaryFolder()
    private fun run(id: String, assistant: String = "assistant", status: SubAgentStatus = SubAgentStatus.PENDING) = SubAgentRun(
        id, "parent", assistant, "label", "task", null, listOf("workspace_read_file"), true,
        timeoutSeconds = 30, maxTrips = 2, status = status, startedAtMs = 1,
    )

    @Test fun `concurrent dispatches atomically reserve assistant slots`() {
        val registry = SubAgentRegistry()
        val pool = Executors.newFixedThreadPool(16)
        val gate = CountDownLatch(1)
        val accepted = AtomicInteger()
        val tasks = (1..100).map { n -> pool.submit {
            gate.await()
            if (registry.tryReserve(run("run-$n"), perAssistantCap = 3)) accepted.incrementAndGet()
        } }
        gate.countDown()
        tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdownNow()
        assertEquals(3, accepted.get())
        assertEquals(3, registry.globalActiveCount())
    }

    @Test fun `approval keeps slot and followup cannot bypass global cap`() {
        val registry = SubAgentRegistry()
        assertTrue(registry.tryReserve(run("wait", status = SubAgentStatus.WAITING_APPROVAL), 8, 1))
        assertFalse(registry.tryReserve(run("other", assistant = "another"), 8, 1))
        registry.update("wait") { it.copy(status = SubAgentStatus.SUCCEEDED) }
        assertTrue(registry.tryReserve(run("other", assistant = "another"), 8, 1))
        assertFalse(registry.tryReserve(run("wait").copy(executionEpoch = 1), 8, 1))
    }

    @Test fun `stop racing initial launch prevents attaching a late job`() {
        val registry = SubAgentRegistry()
        registry.tryReserve(run("starting"), 3)
        registry.update("starting") { it.copy(status = SubAgentStatus.CANCELLED) }
        val late = kotlinx.coroutines.Job()
        assertFalse(registry.setJob("starting", late))
        assertTrue(late.isCancelled)
        assertFalse(registry.hasJob("starting"))
    }

    @Test fun `restart retains approval and reports interrupted executions without replay`() {
        val file = File(temp.newFolder(), "runs.json")
        SubAgentRegistry(file).apply {
            addPending(run("waiting", status = SubAgentStatus.WAITING_APPROVAL))
            addPending(run("running", status = SubAgentStatus.RUNNING))
        }
        val restored = SubAgentRegistry(file)
        assertEquals(SubAgentStatus.WAITING_APPROVAL, restored.get("waiting")?.status)
        assertEquals(SubAgentStatus.PROCESS_LOST, restored.get("running")?.status)
        assertEquals(1, restored.globalActiveCount())
    }

    @Test fun `completion outbox survives followup and acknowledgement is idempotent`() {
        val file = File(temp.newFolder(), "runs.json")
        val original = run("child", status = SubAgentStatus.SUCCEEDED).copy(result = "original result")
        SubAgentRegistry(file).apply {
            addPending(original)
            queueCompletion(original)
            queueCompletion(original)
            update("child") { it.copy(status = SubAgentStatus.RUNNING, executionEpoch = 1, result = null) }
        }
        val restored = SubAgentRegistry(file)
        assertEquals("original result", restored.pendingCompletions().single().result)
        restored.acknowledgeCompletion("child", 0)
        restored.acknowledgeCompletion("child", 0)
        assertTrue(SubAgentRegistry(file).pendingCompletions().isEmpty())
        assertEquals(1, restored.get("child")?.executionEpoch)
    }
}
