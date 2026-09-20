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
}
