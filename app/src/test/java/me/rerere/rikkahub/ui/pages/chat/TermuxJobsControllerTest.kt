package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TermuxJobsControllerTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private val running = obj("""{"job_id":"a","state":"running","command":"make"}""")

    @Test fun `log pagination uses byte cursor and retains only one page`() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val controller = TermuxJobsController(this, request = { request ->
            requests += request
            val cursor = request.jobLong("cursor") ?: 0L
            obj("""{"job_id":"a","state":"running","stream":"stdout","cursor":$cursor,"next_cursor":${cursor + 4},"has_more":true,"text":"🙂"}""")
        })
        controller.select(running); yield()
        controller.read(1); yield()
        assertEquals(4L, requests.last().jobLong("cursor"))
        assertEquals("🙂", controller.state.value.page!!.jobString("text"))
        controller.read(-1); yield()
        assertEquals(0L, requests.last().jobLong("cursor"))
        assertTrue(controller.state.value.previousCursors.isEmpty())
    }

    @Test fun `mutations cannot overlap or retry after an ambiguous response`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val actions = mutableListOf<String?>()
        val controller = TermuxJobsController(this, request = { request ->
            actions += request.jobString("action")
            if (request.jobString("action") == "cancel") {
                gate.await()
                obj("""{"success":false,"state":"unknown","error":"supervisor_response_unavailable"}""")
            } else obj("""{"job_id":"a","state":"running","stream":"stdout","cursor":0,"next_cursor":0,"text":""}""")
        })
        controller.select(running); yield()
        controller.mutate("cancel"); yield()
        controller.mutate("cancel")
        controller.select(obj("""{"job_id":"b"}"""))
        gate.complete(Unit); yield()
        assertEquals(listOf("read", "cancel"), actions)
        assertEquals("a", controller.state.value.selected!!.jobString("job_id"))
        assertEquals("supervisor_response_unavailable", controller.state.value.error)
        assertNull(controller.state.value.mutation)
        assertFalse(controller.state.value.busy)
    }

    @Test fun `unknown cancellation does not authorize deletion and no start action exists`() = runBlocking {
        val actions = mutableListOf<String?>()
        val controller = TermuxJobsController(this, request = { request ->
            actions += request.jobString("action")
            if (request.jobString("action") == "cancel") obj("""{"job_id":"a","state":"unknown","cancel_confirmed":false}""")
            else obj("""{"job_id":"a","state":"running","stream":"stdout","cursor":0,"next_cursor":0,"text":""}""")
        })
        controller.select(running); yield()
        controller.mutate("cancel"); yield()
        assertFalse(controller.state.value.mutation!!.jobBool("cancel_confirmed"))
        controller.mutate("forget"); yield()
        assertEquals("job_not_confirmed_finished", controller.state.value.error)
        controller.mutate("start"); yield()
        assertEquals(listOf("read", "cancel"), actions)
    }

    @Test fun `list refresh recovers durable jobs without relaunch and deduplicates pages`() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val controller = TermuxJobsController(this, request = { request ->
            requests += request
            obj("""{"success":true,"jobs":[{"job_id":"a","state":"unknown"}],"next_cursor":20,"has_more":true}""")
        })
        controller.refresh(); yield()
        controller.refresh(true); yield()
        assertEquals(1, controller.state.value.jobs.size)
        assertEquals(20L, requests.last().jobLong("cursor"))
        assertTrue(requests.all { it.jobString("action") == "list" })
        assertNull(controller.state.value.nextListCursor)
    }

    @Test fun `log deletion clears visible content but preserves job receipt`() = runBlocking {
        val controller = TermuxJobsController(this, request = { request ->
            if (request.jobString("action") == "forget") obj("""{"job_id":"a","state":"completed","logs_removed":true}""")
            else obj("""{"job_id":"a","state":"completed","stream":"stdout","cursor":0,"next_cursor":3,"text":"log"}""")
        })
        controller.select(running); yield()
        controller.mutate("forget"); yield()
        assertNull(controller.state.value.page)
        assertEquals("a", controller.state.value.selected!!.jobString("job_id"))
        assertTrue(controller.state.value.selected!!.jobBool("logs_removed"))
    }
}
