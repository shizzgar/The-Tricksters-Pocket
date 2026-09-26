package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageQueueTest {
    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    @Test fun `steering reserves a ready prefix until persistence acknowledges it`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"), steerActiveTask = true)
        queue.enqueue(text("second"), steerActiveTask = true)
        val reserved = queue.claimSteering()
        assertEquals(2, reserved.size)
        assertTrue(queue.state.value.messages.all { it.isApplying })
        assertNull(queue.takeNext())
        assertTrue(queue.claimSteering().isEmpty())
        assertNull(queue.remove(reserved.first().id))
        assertNull(queue.beginEdit(reserved.first().id))
        queue.enqueue(text("later"), steerActiveTask = true)
        queue.finishSteering(reserved.map { it.id }.toSet(), accepted = true)
        assertEquals(text("later"), queue.state.value.messages.single().parts)
        assertFalse(queue.state.value.messages.single().isApplying)
    }

    @Test fun `failed or cancelled steering retains order attachments edits and paused state`() {
        val queue = MessageQueue()
        val parts = text("original") + UIMessagePart.Image("file:///keep.png")
        queue.enqueue(parts, steerActiveTask = true)
        val id = queue.state.value.messages.single().id
        queue.beginEdit(id)
        queue.finishEdit(id, parts + UIMessagePart.Text("edited"))
        val reserved = queue.claimSteering()
        queue.pause()
        queue.enqueue(text("later"), steerActiveTask = true)
        queue.finishSteering(reserved.map { it.id }.toSet(), accepted = false)
        assertTrue(queue.state.value.paused)
        assertTrue(queue.claimSteering().isEmpty())
        queue.resume()
        assertEquals(parts + UIMessagePart.Text("edited"), queue.claimSteering().first().parts)
        assertEquals(id, queue.state.value.messages.first().id)
    }

    @Test fun `editing and ordinary turns are ordered barriers for steering`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"), steerActiveTask = true)
        queue.enqueue(text("editing"), steerActiveTask = true)
        queue.enqueue(text("last"), steerActiveTask = true)
        queue.beginEdit(queue.state.value.messages[1].id)
        val first = queue.claimSteering()
        assertEquals(listOf(text("first")), first.map { it.parts })
        queue.finishSteering(first.map { it.id }.toSet(), true)
        assertTrue(queue.claimSteering().isEmpty())
        queue.finishEdit(queue.state.value.messages.first().id)
        assertEquals(2, queue.claimSteering().size)
    }

    @Test fun `voice replies and send without answer never become steering implicitly`() {
        val queue = MessageQueue()
        val observer = CompletableDeferred<String?>()
        queue.enqueue(text("voice"), reply = observer, steerActiveTask = true)
        queue.enqueue(text("save only"), answer = false, steerActiveTask = true)
        queue.enqueue(text("update"), steerActiveTask = true)
        assertTrue(queue.claimSteering().isEmpty())
        assertTrue(queue.takeNext()!!.reply === observer)
        assertFalse(observer.isCompleted)
        assertTrue(queue.claimSteering().isEmpty())
        assertFalse(queue.takeNext()!!.answer)
        assertEquals(text("update"), queue.claimSteering().single().parts)
    }

    @Test fun `withdrawing or editing before a boundary changes what is applied`() {
        val queue = MessageQueue()
        queue.enqueue(text("withdraw"), steerActiveTask = true)
        queue.enqueue(text("old"), steerActiveTask = true)
        queue.remove(queue.state.value.messages.first().id)
        val id = queue.state.value.messages.single().id
        queue.beginEdit(id)
        assertTrue(queue.claimSteering().isEmpty())
        queue.finishEdit(id, text("new"))
        assertEquals(text("new"), queue.claimSteering().single().parts)
    }

    @Test
    fun `editing a voice message preserves its reply observer and queue position`() {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("original"), reply = reply)
        queue.enqueue(text("later"))
        val id = queue.state.value.messages.first().id
        queue.beginEdit(id)
        assertNull(queue.takeNext())
        queue.finishEdit(id, text("edited"))
        val dispatched = queue.takeNext()!!
        assertEquals(text("edited"), dispatched.parts)
        assertTrue(dispatched.reply === reply)
        assertFalse(reply.isCompleted)
        assertEquals(text("later"), queue.takeNext()!!.parts)
    }

    @Test
    fun `pausing resolves voice observers but retains queued content for manual resume`() = runBlocking {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("keep me"), reply = reply)
        queue.pause()
        assertTrue(reply.isCompleted)
        assertTrue(runCatching { reply.await() }.exceptionOrNull() is IllegalStateException)
        assertEquals(text("keep me"), queue.state.value.messages.single().parts)
        queue.resume()
        assertEquals(text("keep me"), queue.takeNext()!!.parts)
    }

    @Test
    fun `tool approval can release reply observers without removing or resuming messages`() = runBlocking {
        val queue = MessageQueue()
        val reply = CompletableDeferred<String?>()
        queue.enqueue(text("pending"), reply = reply)
        queue.failReplyWaiters("approval required")
        assertEquals("approval required", runCatching { reply.await() }.exceptionOrNull()?.message)
        assertEquals(1, queue.state.value.messages.size)
        assertFalse(queue.state.value.paused)
    }

    @Test
    fun `dispatches in submission order and preserves send without answer`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"), answer = false)

        assertEquals(text("first"), queue.takeNext()!!.parts)
        val second = queue.takeNext()!!
        assertEquals(text("second"), second.parts)
        assertFalse(second.answer)
        assertNull(queue.takeNext())
    }

    @Test
    fun `editing the head blocks later messages and keeps its position`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        val id = queue.state.value.messages.first().id

        queue.beginEdit(id)
        assertNull(queue.takeNext())
        queue.finishEdit(id, text("edited"))

        val first = queue.takeNext()!!
        assertEquals(id, first.id)
        assertEquals(text("edited"), first.parts)
        assertEquals(text("second"), queue.takeNext()!!.parts)
    }

    @Test
    fun `editing a later message does not block earlier messages`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        queue.beginEdit(queue.state.value.messages.last().id)

        assertEquals(text("first"), queue.takeNext()!!.parts)
        assertNull(queue.takeNext())
    }

    @Test
    fun `cancelling an edit restores the original input and attachments`() {
        val queue = MessageQueue()
        val parts = text("question") + UIMessagePart.Image("file:///test.png")
        queue.enqueue(parts)
        val id = queue.state.value.messages.single().id
        queue.beginEdit(id)
        queue.finishEdit(id)

        assertEquals(parts, queue.takeNext()!!.parts)
    }

    @Test
    fun `removing a message skips it without changing following input`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.enqueue(text("second"))
        queue.remove(queue.state.value.messages.first().id)

        assertEquals(text("second"), queue.takeNext()!!.parts)
        assertNull(queue.takeNext())
    }

    @Test
    fun `new input and edits cannot silently resume a paused queue`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        queue.pause()
        queue.enqueue(text("second"))
        val id = queue.state.value.messages.first().id
        queue.beginEdit(id)
        queue.finishEdit(id, text("edited"))

        assertTrue(queue.state.value.paused)
        assertNull(queue.takeNext())
        queue.resume()
        assertEquals(text("edited"), queue.takeNext()!!.parts)
        assertEquals(text("second"), queue.takeNext()!!.parts)
    }

    @Test
    fun `late edit cannot recreate a removed or dispatched message`() {
        val queue = MessageQueue()
        queue.enqueue(text("first"))
        val id = queue.takeNext()!!.id

        assertNull(queue.beginEdit(id))
        queue.finishEdit(id, text("late"))
        assertTrue(queue.state.value.messages.isEmpty())
    }

    @Test
    fun `rejects empty input but accepts attachment only input`() {
        val queue = MessageQueue()
        queue.enqueue(text("  "))
        queue.enqueue(emptyList())
        assertNull(queue.takeNext())

        val parts = listOf(UIMessagePart.Image("file:///test.png"))
        queue.enqueue(parts)
        assertEquals(parts, queue.takeNext()!!.parts)
    }

    @Test
    fun `submission snapshots caller owned list`() {
        val queue = MessageQueue()
        val parts = mutableListOf<UIMessagePart>(UIMessagePart.Text("original"))
        queue.enqueue(parts)
        parts.clear()

        assertEquals(text("original"), queue.takeNext()!!.parts)
    }

    @Test
    fun `queues are isolated per conversation`() {
        val first = MessageQueue()
        val second = MessageQueue()
        first.enqueue(text("first"))
        second.enqueue(text("second"))
        first.pause()

        assertNull(first.takeNext())
        assertEquals(text("second"), second.takeNext()!!.parts)
    }

    @Test
    fun `completion delivery cannot masquerade as explicit user review continuation`() {
        val queue = MessageQueue()
        queue.enqueue(text("child completion"))
        queue.enqueue(text("continue reviewing"), explicitUserTurn = true)
        assertFalse(queue.takeNext()!!.explicitUserTurn)
        assertTrue(queue.takeNext()!!.explicitUserTurn)
    }

    @Test
    fun `explicit user origin survives edits pause and failed steering without changing completion origin`() {
        val queue = MessageQueue()
        queue.enqueue(text("user"), steerActiveTask = true, explicitUserTurn = true)
        queue.enqueue(text("completion"), steerActiveTask = true)
        val ids = queue.state.value.messages.map { it.id }
        ids.forEach { id -> queue.beginEdit(id); queue.finishEdit(id, text("edited")) }
        val claimed = queue.claimSteering()
        assertEquals(listOf(true, false), claimed.map { it.explicitUserTurn })
        queue.pause()
        queue.finishSteering(ids.toSet(), accepted = false)
        assertNull(queue.takeNext())
        queue.resume()
        assertTrue(queue.takeNext()!!.explicitUserTurn)
        assertFalse(queue.takeNext()!!.explicitUserTurn)
    }
}
