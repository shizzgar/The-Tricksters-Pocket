package me.rerere.rikkahub.data.ai.hooks

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ToolHookMatcherTest {
    private val assistant = Uuid.random()
    private val conversation = Uuid.random()
    private val scope = ToolHookScopeContext(assistant, "workspace", conversation)
    private val rule = ToolHook(
        name = "JADX help",
        scope = ToolHookScope(assistantIds = setOf(assistant)),
        condition = ToolHookCondition(toolNames = setOf("termux_run_command"), commandExecutable = "jadx"),
        action = ToolHookAction(prompt = "Read the installed JADX help before choosing flags."),
    )
    private fun json(text: String) = Json.parseToJsonElement(text)
    private fun event(args: String = """{"command":"jadx --bad foo.apk"}""", result: String = """{"state":"completed","exit_code":2,"stderr":"unknown option"}""", name: String = "termux_run_command", id: String = "one", origin: ToolHookJobOrigin? = null) =
        ToolHookEventNormalizer.normalize(name, json(args), json(result), id, origin)
    private fun matches(event: ToolHookEvent, hook: ToolHook = rule, context: ToolHookScopeContext = scope) = ToolHookMatcher.evaluate(hook, event, context).matched

    @Test fun `jadx failure matches direct command with exact executable`() {
        assertTrue(matches(event()))
        assertFalse(matches(event(args = """{"command":"echo 'jadx --bad'"}""")))
        assertFalse(matches(event(args = """{"command":"notjadx --bad"}""")))
    }

    @Test fun `executable argv and simple shell chains identify command words`() {
        assertTrue(matches(event(args = """{"executable":"/usr/bin/jadx","arguments":["--bad","foo.apk"]}""")))
        assertTrue(matches(event(args = """{"command":"cd /tmp && LANG=C env A=B /bin/jadx --bad"}""")))
        assertTrue(matches(event(args = """{"executable":"/bin/bash","arguments":["-lc","jadx --bad"]}""")))
        assertFalse(matches(event(args = """{"command":"echo 'x; jadx --bad'"}""")))
        assertFalse(matches(event(args = """{"executable":"/bin/bash","arguments":["--rcfile","jadx"]}""")))
        assertFalse(matches(event(args = """{"executable":"/bin/bash","arguments":["script.sh","-c","jadx"]}""")))
    }

    @Test fun `complex shell programs fail closed for executable identity`() {
        assertFalse(matches(event(args = """{"command":"cat <<EOF\njadx foo.apk\nEOF"}""")))
        assertFalse(matches(event(args = """{"command":"echo `echo x; jadx foo`"}""")))
        assertFalse(matches(event(args = """{"command":"f() { jadx foo; }"}""")))
        assertFalse(matches(event(args = """{"command":"echo \"unterminated; jadx"}""")))
    }

    @Test fun `missing exit and arbitrary error prose never become nonzero exits`() {
        assertFalse(matches(event(result = """{"success":false,"stderr":"exit_code 1"}""")))
        assertFalse(matches(event(result = """"jadx failed with exit_code 2"""")))
        assertFalse(matches(event(result = """{"exit_code":"unknown"}""")))
    }

    @Test fun `stdout and stderr literals are independent conjunctive conditions`() {
        val filtered = rule.copy(condition = rule.condition.copy(stdoutContains = "input.apk", stderrContains = "UNKNOWN OPTION"))
        assertTrue(matches(event(result = """{"exit_code":2,"stdout":"input.apk","stderr":"unknown option"}"""), filtered))
        assertFalse(matches(event(result = """{"exit_code":2,"stdout":"other.apk","stderr":"unknown option"}"""), filtered))
        assertFalse(matches(event(result = """{"exit_code":2,"stdout":"input.apk","stderr":"unknown option"}"""), filtered.copy(condition = filtered.condition.copy(caseSensitive = true))))
    }

    @Test fun `success requires a recorded success fact`() {
        val success = rule.copy(condition = rule.condition.copy(outcome = ToolHookOutcome.SUCCESS))
        assertTrue(matches(event(result = """{"exit_code":0}"""), success))
        assertTrue(matches(event(result = """{"success":true}"""), success))
        assertFalse(matches(event(result = """"Done""""), success))
        assertFalse(matches(event(result = "null"), success))
    }

    @Test fun `scope is explicit and global inheritance is opt in`() {
        assertFalse(matches(event(), rule.copy(scope = ToolHookScope())))
        assertFalse(matches(event(), rule.copy(enabled = false)))
        val child = scope.copy(assistantId = Uuid.random(), conversationId = Uuid.random(), isSubagent = true,
            parentAssistantIds = setOf(assistant), ancestorConversationIds = setOf(conversation))
        assertFalse(matches(event(), context = child))
        assertTrue(matches(event(), rule.copy(scope = rule.scope.copy(inheritToSubagents = true)), child))
        assertFalse(matches(event(), rule.copy(scope = ToolHookScope(global = true)), child))
        assertTrue(matches(event(), rule.copy(scope = ToolHookScope(global = true, inheritToSubagents = true)), child))
    }

    @Test fun `workspace or conversation selectors and explicit child selection apply`() {
        assertTrue(matches(event(), rule.copy(scope = ToolHookScope(workspaceIds = setOf("workspace")))))
        assertTrue(matches(event(), rule.copy(scope = ToolHookScope(conversationIds = setOf(conversation)))))
        assertFalse(matches(event(), rule.copy(scope = ToolHookScope(workspaceIds = setOf("other")))))
        assertTrue(matches(event(), rule, scope.copy(isSubagent = true)))
    }

    @Test fun `managed job poll uses original launch and terminal deduplication identity`() {
        val launch = ToolHookJobOrigin("termux_run_command", json("""{"command":"jadx --bad","managed_job":true}"""), "launch")
        val first = event(name = "termux_job_wait", args = """{"job_id":"job-1"}""", result = """{"job_id":"job-1","state":"failed","exit_code":2}""", origin = launch)
        val later = event(name = "termux_job_read", args = """{"job_id":"job-1"}""", result = """{"job_id":"job-1","state":"failed","exit_code":2,"stream":"stderr","text":"unknown option"}""", id = "two", origin = launch)
        assertTrue(matches(first))
        assertEquals(first.deduplicationKey, later.deduplicationKey)
        assertEquals("unknown option", later.stderr)
    }

    @Test fun `running job and wait timeout do not imply command failure or timeout`() {
        val observed = event(name = "termux_job_wait", args = """{"job_id":"job-1"}""", result = """{"job_id":"job-1","state":"running","wait_timed_out":true,"exit_code":1,"command":"jadx foo"}""")
        assertFalse(observed.completed)
        assertNull(observed.exitCode)
        assertFalse(observed.timedOut)
        assertFalse(matches(observed, rule.copy(condition = ToolHookCondition(outcome = ToolHookOutcome.TIMEOUT))))
    }

    @Test fun `supervisor error exit is not the actual job exit`() {
        val observed = event(name = "termux_job_wait", result = """{"error":"invalid_supervisor_response","exit_code":1}""")
        assertTrue(observed.toolError)
        assertNull(observed.exitCode)
        assertFalse(matches(observed, rule.copy(condition = ToolHookCondition(outcome = ToolHookOutcome.NONZERO_EXIT))))
        assertTrue(matches(observed, rule.copy(condition = ToolHookCondition(outcome = ToolHookOutcome.TOOL_ERROR))))
    }

    @Test fun `job timeout is terminal and cancelled jobs do not masquerade as nonzero failures`() {
        val timeout = event(name = "termux_job_wait", result = """{"job_id":"one","state":"timed_out","exit_code":-15}""")
        assertTrue(timeout.timedOut)
        assertTrue(timeout.completed)
        assertNull(timeout.exitCode)
        val cancelled = event(name = "termux_job_cancel", result = """{"job_id":"one","state":"cancelled","exit_code":-15}""")
        assertNull(cancelled.exitCode)
    }

    @Test fun `direct timeout is an observation failure not command completion`() {
        val timeout = event(result = """{"error":"timeout","state":"unknown","process_termination_confirmed":false}""")
        assertTrue(timeout.timedOut)
        assertFalse(timeout.completed)
        assertTrue(matches(timeout, rule.copy(condition = rule.condition.copy(outcome = ToolHookOutcome.TIMEOUT))))
        assertFalse(matches(timeout))
        val runtimeDeadline = event(name = "termux_job_wait", result = """{"error":"tool_cancelled_wall_clock","timed_out":true,"execution_outcome_unknown":true}""")
        assertTrue(runtimeDeadline.timedOut)
        assertFalse(runtimeDeadline.completed)
    }

    @Test fun `workspace camel case and ssh timeout fields normalize`() {
        val workspace = event(name = "workspace_shell", result = """{"exitCode":2,"timedOut":false,"stderr":"bad option"}""")
        assertEquals(2, workspace.exitCode)
        val ssh = event(name = "ssh_exec", result = """{"error":"command_timeout","partial_stdout":"partial","partial_stderr":"warning"}""")
        assertTrue(ssh.timedOut)
        assertEquals("partial", ssh.stdout)
        assertEquals("warning", ssh.stderr)
    }

    @Test fun `workspace managed shell retains actual exit and timeout facts`() {
        val failed = event(name = "workspace_shell", result = """{"job_id":"job-ws","exitCode":2,"timedOut":false}""")
        assertTrue(failed.completed)
        assertEquals(2, failed.exitCode)
        assertEquals("job:job-ws:terminal", failed.deduplicationKey)
        val timeout = event(name = "workspace_shell", result = """{"job_id":"job-ws","exitCode":-15,"timedOut":true}""")
        assertTrue(timeout.timedOut)
        assertNull(timeout.exitCode)
        val unknown = event(name = "workspace_shell", result = """{"job_id":"job-ws","exitCode":-1,"timedOut":false}""")
        assertFalse(unknown.completed)
        assertNull(unknown.exitCode)
    }

    @Test fun `workspace background lifecycle uses process identity not polling identity`() {
        val started = event(name = "workspace_run_background", result = """{"id":"process-1","status":"running","command":"jadx foo"}""")
        assertEquals("process-1", started.jobId)
        assertFalse(started.completed)
        val done = event(name = "workspace_background_status", result = """{"id":"process-1","status":"exited","exitCode":2,"command":"jadx foo"}""")
        assertTrue(done.completed)
        assertEquals(2, done.exitCode)
        assertEquals("job:process-1:terminal", done.deduplicationKey)
    }

    @Test fun `detached and interactive dispatch are not command success`() {
        val detached = event(args = """{"command":"jadx foo","background":true}""", result = """{"exit_code":0,"success":true}""")
        assertFalse(detached.completed)
        assertNull(detached.exitCode)
        assertFalse(detached.successful)
        val interactive = event(result = """{"mode":"interactive","success":true}""")
        assertFalse(interactive.completed)
        assertFalse(interactive.successful)
    }

    @Test fun `mcp structured error remains error even with success looking output`() {
        val observed = event(result = """{"isError":true,"stdout":"success","exit_code":0}""")
        assertTrue(observed.toolError)
        assertFalse(observed.successful)
    }

    @Test fun `output and matcher work is bounded and malformed imported rules are inert`() {
        val observed = event(result = buildJsonObject { put("exit_code", 2); put("stdout", "x".repeat(100_000)) }.toString())
        assertEquals(65_536, observed.stdout.length)
        assertFalse(matches(observed, rule.copy(maxFiringsPerTurn = 0)))
        assertFalse(matches(observed, rule.copy(condition = rule.condition.copy(commandContains = "x".repeat(1_025)))))
    }
}
