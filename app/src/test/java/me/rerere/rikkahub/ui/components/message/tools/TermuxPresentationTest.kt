package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TermuxPresentationTest {
    private fun present(args: String = "{}", output: String? = null, name: String = "termux_run_command", loading: Boolean = false, started: Boolean = false) =
        presentTermux(name, Json.parseToJsonElement(args), output?.let { Json.parseToJsonElement(it) }, loading, started)

    @Test fun `capture success flag never hides failing exit code`() {
        assertEquals(TermuxStatus.FAILED, present(output = """{"success":true,"exit_code":7,"stderr":"failed"}""").status)
        assertEquals(TermuxStatus.COMPLETED, present(output = """{"success":true,"exit_code":0}""").status)
        assertEquals(TermuxStatus.UNKNOWN, present(output = """{"success":true}""").status)
    }
    @Test fun `detached launch is not completion and raw executable ignores background flag`() {
        val output = """{"success":true,"exit_code":0,"stdout":"rikkahub_bg_pid=123\n"}"""
        val view = present("""{"command":"server","background":true}""", output)
        assertEquals(TermuxStatus.DISPATCHED, view.status)
        assertEquals("123", view.pid)
        assertEquals(TermuxStatus.COMPLETED, present("""{"executable":"/bin/tool","background":true}""", output).status)
        assertEquals(TermuxStatus.UNKNOWN, present("""{"command":"server","background":true}""", """{"exit_code":0}""").status)
    }
    @Test fun `session screen and timeout are not command completion`() {
        assertEquals(TermuxStatus.SESSION_UPDATED, present(output = """{"success":true,"screen":"done"}""", name = "termux_session_read").status)
        assertEquals(TermuxStatus.TIMEOUT, present(output = """{"success":true,"timed_out":true}""", name = "termux_session_send").status)
        assertEquals(TermuxStatus.TIMEOUT, present(output = """{"error":"timeout"}""").status)
    }
    @Test fun `partial and malformed results remain unknown`() {
        assertEquals(TermuxStatus.RUNNING, present(loading = true, started = true).status)
        assertEquals(TermuxStatus.UNKNOWN, present(started = true).status)
        assertEquals(TermuxStatus.PENDING, present().status)
        assertEquals(TermuxStatus.UNKNOWN, present(output = "\"not JSON\"").status)
        assertEquals(TermuxStatus.UNKNOWN, present(output = """{"exit_code":{},"success":[]}""").status)
    }
    @Test fun `argv display preserves shell metacharacters and empty arguments`() {
        val view = present("""{"executable":"/bin/tool","arguments":["","a b","it's","$(touch file)"]}""")
        assertEquals("/bin/tool '' 'a b' 'it'\"'\"'s' '$(touch file)'", view.command)
        val command = "printf '%s\\n' a\n# actual newline"
        assertEquals(command, present(args = """{"command":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(command))}}""").command)
    }

    @Test fun `job snapshots never imply a live status connection`() {
        val view = present(name = "termux_job_wait", output = """{"job_id":"job","state":"running","wait_timed_out":true,"command":"make all"}""")
        assertEquals(TermuxStatus.OBSERVED_RUNNING, view.status)
        assertTrue(view.isJobSnapshot)
        assertTrue(view.waitExpired)
        assertEquals("make all", view.command)
        assertEquals(TermuxStatus.STARTING, present(name = "termux_job_start", output = """{"state":"starting"}""").status)
        assertEquals(TermuxStatus.UNKNOWN, present(name = "termux_job_wait", output = """{"state":"unknown","error":"supervisor_response_unavailable"}""").status)
    }

    @Test fun `job cancellation request is distinct from confirmed cancellation and failure`() {
        assertEquals(TermuxStatus.CANCELLING, present(name = "termux_job_cancel", output = """{"state":"running","cancel_confirmed":false,"wait_timed_out":true}""").status)
        assertEquals(TermuxStatus.CANCELLED, present(name = "termux_job_cancel", output = """{"state":"cancelled","cancel_confirmed":true,"exit_code":-15}""").status)
        assertEquals(TermuxStatus.CANCELLED, present(name = "termux_job_read", output = """{"state":"cancelled","exit_code":-15}""").status)
        assertEquals(TermuxStatus.UNKNOWN, present(name = "termux_job_cancel", output = """{"state":"cancelled","cancel_confirmed":false}""").status)
        assertEquals(TermuxStatus.FAILED, present(name = "termux_job_cancel", output = """{"success":false,"error":"job_not_found"}""").status)
    }

    @Test fun `execution timeout and observation timeout are not conflated`() {
        assertEquals(TermuxStatus.JOB_TIMEOUT, present(name = "termux_job_wait", output = """{"state":"timed_out","exit_code":-9}""").status)
        assertEquals(TermuxStatus.OBSERVED_RUNNING, present(name = "termux_job_wait", output = """{"state":"running","wait_timed_out":true}""").status)
        assertEquals(TermuxStatus.TIMEOUT, present(name = "termux_job_wait", output = """{"error":"timeout"}""").status)
        assertEquals(TermuxStatus.FAILED, present(name = "termux_job_read", output = """{"state":"failed"}""").status)
    }

    @Test fun `reading saved output is successful without claiming command completion`() {
        val view = present(name = "termux_output_read", output = """{"output_ref":"ref","text":"","next_cursor":0,"has_more":false}""")
        assertEquals(TermuxStatus.RESPONSE_RECEIVED, view.status)
        assertEquals(TermuxOutputState.ARCHIVED, view.outputState)
        assertEquals(TermuxStatus.RESPONSE_RECEIVED, present(name = "termux_job_list", output = """{"success":true,"jobs":[]}""").status)
        assertEquals(TermuxStatus.UNKNOWN, present(name = "termux_job_list", output = """{"success":true}""").status)
        assertEquals(TermuxStatus.UNKNOWN, present(name = "termux_output_read", output = """{"text":true,"output_ref":"ref"}""").status)
    }

    @Test fun `removing logs of a failed job does not rerun or fail that job again`() {
        val view = present(name = "termux_job_forget", output = """{"success":true,"state":"failed","logs_removed":true,"exit_code":7,"error":"launch_failed"}""")
        assertEquals(TermuxStatus.LOGS_REMOVED, view.status)
        assertEquals(TermuxOutputState.REMOVED, view.outputState)
    }

    @Test fun `preview shortening is different from permanent archive loss`() {
        assertEquals(TermuxOutputState.ARCHIVED, present(output = """{"output_ref":"ref","archive_truncated":false,"stdout":"some text\n…[truncated; 400 bytes more]"}""").outputState)
        assertEquals(TermuxOutputState.PREVIEW_SHORTENED, present(output = """{"stdout":"some text\n…[truncated; 400 bytes more]"}""").outputState)
        assertEquals(TermuxOutputState.TRUNCATED, present(output = """{"output_ref":"ref","archive_truncated":true,"text":"page","has_more":true}""").outputState)
        assertEquals(TermuxOutputState.TRUNCATED, present(output = """{"logs_truncated":{"stdout":false,"stderr":true}}""").outputState)
        assertNull(present(output = """{"logs_truncated":{"stdout":false,"stderr":false}}""").outputState)
        assertEquals(TermuxOutputState.UNAVAILABLE, present(output = """{"archive_error":"private_output_quota_exhausted"}""").outputState)
        assertEquals(TermuxOutputState.MORE_AVAILABLE, present(output = """{"text":"page","next_cursor":123,"has_more":true}""").outputState)
        // Pagination of job metadata is not pagination of terminal output.
        assertNull(present(name = "termux_job_list", output = """{"jobs":[],"has_more":true}""").outputState)
        assertNull(present(output = """{"archive_truncated":{},"logs_truncated":[],"has_more":[]}""").outputState)
    }
}
