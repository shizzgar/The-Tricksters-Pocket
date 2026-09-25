package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
import me.rerere.rikkahub.data.preferences.TermuxRuntime
import java.util.UUID

internal const val TERMUX_BIN = "/data/data/com.termux/files/usr/bin"
internal const val TERMUX_HOME = "/data/data/com.termux/files/home"

private const val DEFAULT_COLS = 200
private const val DEFAULT_ROWS = 50
private const val DEFAULT_READ_LINES = 200
private const val DEFAULT_TIMEOUT_S = 20
private const val MAX_TIMEOUT_S = 600
private const val SETTLE_MS = 600L
private const val POLL_INTERVAL_MS = 200L
private const val MAX_SESSIONS = 8
private const val TMUX_OP_TIMEOUT_MS = 8_000L
private const val INSTALL_TIMEOUT_MS = 180_000L
// Idle sessions are never explicitly killed by the model, so they would otherwise pin the
// MAX_SESSIONS budget forever. Reap any rk_ session whose tmux session_activity is older than
// this before enforcing the slot cap. 6h is long enough to leave a genuinely in-use shell
// (ssh, a REPL, a watched build) alone while clearing forgotten ones.
private const val SESSION_TTL_MS = 6L * 60 * 60 * 1000

/** Builds the argv passed to the tmux executable for each session operation. Pure. */
internal object TmuxOps {
    fun sessionName(userName: String?): String {
        val suffix = userName?.takeIf { it.isNotBlank() }
            ?.replace(Regex("[^A-Za-z0-9_]"), "_")
            ?.take(24)
        val id = UUID.randomUUID().toString().take(8)
        return if (suffix.isNullOrBlank()) "rk_$id" else "rk_${suffix}_$id"
    }

    fun startArgv(session: String, cols: Int, rows: Int, workingDir: String? = null): Array<String> =
        arrayOf("new-session", "-d", "-s", session, "-x", cols.toString(), "-y", rows.toString()) +
            (workingDir?.let { arrayOf("-c", it) } ?: emptyArray())

    // -l sends the text literally (no tmux key-name interpretation); -- ends option parsing.
    fun sendTextArgv(session: String, text: String): Array<String> =
        arrayOf("send-keys", "-t", "=$session", "-l", "--", text)

    // Each element is a tmux key name (e.g. "C-c", "Enter", "Up", "Tab").
    fun sendKeysArgv(session: String, keys: List<String>): Array<String> =
        (listOf("send-keys", "-t", "=$session") + keys).toTypedArray()

    fun enterArgv(session: String): Array<String> =
        arrayOf("send-keys", "-t", "=$session", "Enter")

    fun capturePaneArgv(session: String, lines: Int): Array<String> =
        arrayOf("capture-pane", "-t", "=$session", "-p", "-S", "-${lines.coerceAtLeast(0)}")

    fun killArgv(session: String): Array<String> =
        arrayOf("kill-session", "-t", "=$session")

    fun listArgv(): Array<String> =
        arrayOf("list-sessions", "-F", "#{session_name}\t#{session_created}\t#{session_activity}")
}

internal data class PaneSample(val elapsedMs: Long, val content: String)

internal sealed interface PollResult {
    data object Continue : PollResult
    data class Done(val reason: Reason, val content: String) : PollResult
    enum class Reason { SETTLED, MATCHED, TIMEOUT }
}

/** Bounded matching: literal by default; explicit regex with an interruptible input. */
internal fun waitForMatches(pane: String, pattern: String, regex: Boolean = false): Boolean {
    if (pattern.isEmpty() || pattern.length > 512) return false
    val text = pane.takeLast(64_000)
    if (!regex) return text.contains(pattern)
    val deadline = System.nanoTime() + 10_000_000L
    class BoundedText(private val value: String) : CharSequence {
        override val length: Int get() = value.length
        override fun get(index: Int): Char {
            check(System.nanoTime() < deadline) { "regex matching budget exceeded" }
            return value[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = BoundedText(value.substring(startIndex, endIndex))
        override fun toString() = value
    }
    return try { java.util.regex.Pattern.compile(pattern).matcher(BoundedText(text)).find() }
    catch (_: IllegalArgumentException) { false }
    catch (_: IllegalStateException) { false }
    catch (_: StackOverflowError) { false }
}

/**
 * Decide whether the polling loop should stop, given every pane snapshot taken so far
 * (chronological, each with its elapsed time since the send). Order of precedence:
 * wait_for match, then settle (screen unchanged for >= settleMs), then timeout.
 */
internal fun evaluatePoll(
    samples: List<PaneSample>,
    settleMs: Long,
    timeoutMs: Long,
    waitFor: String?,
): PollResult {
    val cur = samples.lastOrNull() ?: return PollResult.Continue
    if (!waitFor.isNullOrEmpty() && waitForMatches(cur.content, waitFor)) {
        return PollResult.Done(PollResult.Reason.MATCHED, cur.content)
    }
    var stableSince = cur.elapsedMs
    for (i in samples.indices.reversed()) {
        if (samples[i].content == cur.content) stableSince = samples[i].elapsedMs else break
    }
    if (waitFor.isNullOrEmpty() && samples.size >= 2 && cur.elapsedMs - stableSince >= settleMs) {
        return PollResult.Done(PollResult.Reason.SETTLED, cur.content)
    }
    if (cur.elapsedMs >= timeoutMs) {
        return PollResult.Done(PollResult.Reason.TIMEOUT, cur.content)
    }
    return PollResult.Continue
}

internal data class TmuxSessionInfo(val name: String, val created: Long, val lastActivity: Long)

internal fun parseSessions(stdout: String, prefix: String = "rk_"): List<TmuxSessionInfo> =
    stdout.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size < 3 || !parts[0].startsWith(prefix)) return@mapNotNull null
            TmuxSessionInfo(
                name = parts[0],
                created = parts[1].toLongOrNull() ?: 0L,
                lastActivity = parts[2].toLongOrNull() ?: 0L,
            )
        }.toList()

/** Sessions whose last tmux activity is older than [ttlMs] relative to [nowEpochSecs]. Pure. */
internal fun staleSessionsToReap(
    sessions: List<TmuxSessionInfo>,
    nowEpochSecs: Long,
    ttlMs: Long,
): List<TmuxSessionInfo> {
    val cutoffSecs = nowEpochSecs - ttlMs / 1000
    // session_activity is epoch seconds; treat a 0/unparsed activity as not-stale so a session
    // with a malformed timestamp is never reaped out from under an active user.
    return sessions.filter { it.lastActivity in 1 until cutoffSecs }
}

internal fun isSessionNotFound(stderr: String): Boolean {
    val s = stderr.lowercase()
    return s.contains("can't find session") ||
        s.contains("no server running") ||
        s.contains("session not found") ||
        s.contains("no current session")
}

internal suspend fun tmux(context: Context, argv: Array<String>, timeoutMs: Long = TMUX_OP_TIMEOUT_MS): CaptureResult {
    val result = runCommandCapture(context, "$TERMUX_BIN/tmux", argv, TERMUX_HOME, timeoutMs)
    return if (result is CaptureResult.Success && result.exitCode != 0)
        CaptureResult.OtherError("tmux exit=${result.exitCode}: ${result.stderr.ifBlank { result.stdout }}") else result
}

/** Ensure tmux is installed; auto-install on first use. Returns null on success, an error string otherwise. */
private suspend fun ensureTmux(context: Context): String? {
    val check = runCommandCapture(context, "$TERMUX_BIN/sh", arrayOf("-c", "command -v tmux"), TERMUX_HOME, TMUX_OP_TIMEOUT_MS)
    if (check is CaptureResult.Success && check.exitCode == 0 && check.stdout.isNotBlank()) return null
    // The install can run for the full INSTALL_TIMEOUT_MS (~180s). Keep the bound but surface
    // each outcome distinctly instead of silently blocking ~3 min and then reporting a generic
    // failure: a Denied means the permission path, a Timeout means the install is still going
    // (network / large download) so the caller can tell the user to retry shortly.
    val install = runCommandCapture(context, "$TERMUX_BIN/bash", arrayOf("-c", "pkg install -y tmux"), TERMUX_HOME, INSTALL_TIMEOUT_MS)
    if (install is CaptureResult.Denied) return "termux_permission_denied"
    if (install is CaptureResult.Timeout) return "tmux_installing"
    val recheck = runCommandCapture(context, "$TERMUX_BIN/sh", arrayOf("-c", "command -v tmux"), TERMUX_HOME, TMUX_OP_TIMEOUT_MS)
    return if (recheck is CaptureResult.Success && recheck.exitCode == 0 && recheck.stdout.isNotBlank()) null else "tmux_install_failed"
}

private fun resolveTimeoutMs(input: JsonElement): Long {
    val raw = input.jsonObject["timeout_seconds"]?.jsonPrimitive?.intOrNull
    val secs = when {
        raw == null || raw == 0 -> DEFAULT_TIMEOUT_S
        else -> raw.coerceIn(1, MAX_TIMEOUT_S)
    }
    return secs.toLong() * 1000
}

/**
 * UTF-8 byte width of the Unicode code point [cp]. Used to budget truncation by bytes while
 * iterating code points (NOT chars): an astral char (emoji, some CJK) is a surrogate PAIR of
 * two Java chars but a single 4-byte UTF-8 sequence. Measuring per-char would count each
 * surrogate half separately — overshooting the budget ~2x and letting a cut fall between the
 * two halves, corrupting the very emoji this boundary-snapping is meant to protect.
 */
private fun utf8Width(cp: Int): Int = when {
    cp < 0x80 -> 1
    cp < 0x800 -> 2
    cp < 0x10000 -> 3
    else -> 4
}

/**
 * Keep at most [maxBytes] of UTF-8 from the end of [s], snapping the cut to a code-point
 * boundary so a multi-byte sequence (including a surrogate-pair emoji) is never split, which
 * would otherwise corrupt CJK / emoji output. Returns the whole string when it already fits.
 * Pure.
 */
internal fun takeLastUtf8Bytes(s: String, maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
    // Walk back from the end one code point at a time, counting its true UTF-8 width, until
    // adding one more would exceed the budget; the surviving slice is byte-bounded and aligned.
    var bytes = 0
    var i = s.length
    while (i > 0) {
        val cp = s.codePointBefore(i)
        val w = utf8Width(cp)
        if (bytes + w > maxBytes) break
        bytes += w
        i -= Character.charCount(cp)
    }
    return s.substring(i)
}

/**
 * Keep at most [maxBytes] of UTF-8 from the start of [s], snapping the cut to a code-point
 * boundary so a multi-byte sequence (including a surrogate-pair emoji) is never split. Returns
 * the whole string when it already fits. Pure. Counterpart to [takeLastUtf8Bytes] for
 * head-keeping truncation.
 */
internal fun takeFirstUtf8Bytes(s: String, maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
    var bytes = 0
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        val w = utf8Width(cp)
        if (bytes + w > maxBytes) break
        bytes += w
        i += Character.charCount(cp)
    }
    return s.substring(0, i)
}

private fun truncateOut(s: String): String {
    // capture-pane emits the full terminal height, so the screen arrives padded with a wall
    // of blank lines below the cursor. Drop trailing blank lines so each read does not burn
    // tokens on empty padding.
    val trimmed = s.trimEnd('\n', ' ', '\t')
    val max = TermuxRuntime.maxStdoutBytes
    // Bound on UTF-8 bytes, not chars: maxStdoutBytes is a byte budget, and a char-count cut
    // would over- or under-shoot for multibyte text and could split a code point.
    return if (trimmed.toByteArray(Charsets.UTF_8).size > max) {
        takeLastUtf8Bytes(trimmed, max) + "\n…[older scrollback truncated]"
    } else {
        trimmed
    }
}

private val sessionLocks = ConcurrentHashMap<String, Mutex>()
private val creationLock = Mutex()
internal fun sessionOwner(conversationId: String): String = MessageDigest.getInstance("SHA-256")
    .digest(conversationId.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

private fun sessionErrorEnvelope(error: String, recovery: String, session: String? = null) = listOf(
    UIMessagePart.Text(buildJsonObject {
        put("success", false); put("error", error); put("recovery", recovery)
        session?.let { put("session_id", it) }
    }.toString())
)

private fun captureError(result: CaptureResult): String = when (result) {
    is CaptureResult.OtherError -> result.message
    CaptureResult.Timeout -> "Result wait timed out; external execution outcome is unknown. Inspect before retrying."
    CaptureResult.Denied -> "Termux RUN_COMMAND permission denied. Check integration permissions."
    is CaptureResult.Success -> "exit=${result.exitCode}"
}

private fun preflight(context: Context): List<UIMessagePart>? = when (TermuxIntegration.state(context)) {
    TermuxIntegration.State.NOT_INSTALLED -> sessionErrorEnvelope("termux_not_installed", "Install Termux first.")
    TermuxIntegration.State.NO_PERMISSION -> sessionErrorEnvelope("termux_permission_not_granted", "Enable Termux in Assistant → Local tools and grant RUN_COMMAND permission.")
    TermuxIntegration.State.READY -> null
}

private suspend fun ownedSession(context: Context, session: String, owner: String?): String? {
    if (owner == null) return "Conversation identity is required for managed terminals."
    if (!session.matches(Regex("rk_[A-Za-z0-9_]{1,80}"))) return "Invalid managed session id."
    val result = tmux(context, arrayOf("show-options", "-t", "=$session", "-v", "@rk_owner"))
    if (result !is CaptureResult.Success) return captureError(result)
    return if (result.stdout.trim() == sessionOwner(owner)) null
        else "Session is unclaimed or belongs to another conversation. Claim an unowned legacy session explicitly with termux_session_manage."
}

private fun literalOccurrences(text: String, pattern: String): Int {
    if (pattern.isEmpty()) return 0
    val bounded = text.takeLast(64_000)
    var offset = 0
    var count = 0
    while (offset < bounded.length) {
        val found = bounded.indexOf(pattern, offset)
        if (found < 0) break
        count++
        offset = found + pattern.length
    }
    return count
}

private data class ScreenRead(val screen: String, val reason: String)
private suspend fun readScreen(context: Context, session: String, lines: Int, waitFor: String?, timeoutMs: Long,
    regex: Boolean = false, baseline: String? = null): ScreenRead {
    val start = android.os.SystemClock.elapsedRealtime()
    var last = ""
    var stableSince = start
    var changedSinceBaseline = baseline == null || !waitForMatches(baseline, waitFor.orEmpty(), regex)
    while (true) {
        val elapsed = android.os.SystemClock.elapsedRealtime() - start
        val cap = tmux(context, TmuxOps.capturePaneArgv(session, lines.coerceIn(0, 2000)),
            (timeoutMs - elapsed).coerceIn(1, TMUX_OP_TIMEOUT_MS))
        if (cap !is CaptureResult.Success) throw IllegalStateException(captureError(cap))
        val screen = takeLastUtf8Bytes(cap.stdout, 128_000)
        val now = android.os.SystemClock.elapsedRealtime()
        if (screen != last) stableSince = now
        if (!changedSinceBaseline && !waitForMatches(screen, waitFor.orEmpty(), regex)) changedSinceBaseline = true
        val additionalLiteralMatch = !regex && !waitFor.isNullOrEmpty() && baseline != null &&
            literalOccurrences(screen, waitFor) > literalOccurrences(baseline, waitFor)
        val matched = !waitFor.isNullOrEmpty() && (changedSinceBaseline || additionalLiteralMatch) && waitForMatches(screen, waitFor, regex)
        val reason = when {
            matched -> "matched"
            waitFor.isNullOrEmpty() && now - stableSince >= SETTLE_MS -> "settled"
            now - start >= timeoutMs -> "timeout"
            else -> null
        }
        if (reason != null) return ScreenRead(screen, reason)
        last = screen
        delay(POLL_INTERVAL_MS)
    }
}

private fun screenEnvelope(session: String, read: ScreenRead, inputSent: Boolean = false) = listOf(
    UIMessagePart.Text(buildJsonObject {
        put("success", true); put("session_id", session); put("screen", truncateOut(read.screen))
        put("stop_reason", read.reason); put("matched_wait_for", read.reason == "matched")
        put("timed_out", read.reason == "timeout"); put("input_sent", inputSent)
        put("command_completed", false)
        put("note", "Screen observation only; a match may be terminal echo. Use managed jobs for verified command completion.")
    }.toString())
)

private fun JsonElement.string(key: String) = jsonObject[key]?.jsonPrimitive?.contentOrNull
private fun JsonElement.flag(key: String, default: Boolean = false) = jsonObject[key]?.jsonPrimitive?.booleanOrNull ?: default
private fun JsonElement.number(key: String, default: Int) = jsonObject[key]?.jsonPrimitive?.intOrNull ?: default
private fun field(type: String, description: String) = buildJsonObject { put("type", type); put("description", description) }
private fun waitFields() = mapOf(
    "wait_for" to field("string", "Expected literal text; wait until matched or timed out, not until screen is quiet."),
    "wait_for_regex" to field("boolean", "Explicit bounded regular expression mode. Default false."),
    "match_existing" to field("boolean", "Allow existing screen text to satisfy wait_for; send defaults false, read true."),
    "timeout_seconds" to field("integer", "Observation timeout, default 20, maximum 600 seconds. Does not stop the process."),
)

fun termuxSessionStartTool(context: Context, owner: String? = null, defaultWorkingDir: String? = null): Tool = Tool(
    name = "termux_session_start",
    description = "Create a persistent PTY owned by this conversation. Returns session_id even if reading fails. Sessions are never killed for being quiet. Use jobs for batch work; terminals for interactive programs.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("working_dir", field("string", "Start directory; defaults to the bound workspace or Termux settings. Relative paths resolve from there."))
        put("name", field("string", "Friendly purpose/label")); put("command", field("string", "Optional initial command"))
        put("cols", field("integer", "Width, 40–400; default 120")); put("rows", field("integer", "Height, 10–200; default 50"))
        put("pinned", field("boolean", "Mark a long-lived service terminal; default true"))
    }) },
    execute = execute@{ input ->
        preflight(context)?.let { return@execute it }
        if (owner == null) return@execute sessionErrorEnvelope("missing_context", "Conversation identity is required.")
        val initial = input.string("command")
        initial?.let { HardlineCommandGuard.checkCommand(it) }?.let { return@execute sessionErrorEnvelope("blocked_by_safety_floor", it) }
        ensureTmux(context)?.let { return@execute sessionErrorEnvelope(it, "Check tmux installation in Termux; a timeout does not prove installation failed.") }
        creationLock.withLock {
            val listed = tmux(context, TmuxOps.listArgv())
            if (listed !is CaptureResult.Success && !(listed is CaptureResult.OtherError && listed.message.contains("no server running")))
                return@withLock sessionErrorEnvelope("list_failed", captureError(listed))
            val live = (listed as? CaptureResult.Success)?.let { parseSessions(it.stdout) }.orEmpty()
            if (live.size >= MAX_SESSIONS) return@withLock sessionErrorEnvelope("too_many_sessions", "Limit $MAX_SESSIONS; explicitly close an owned session. No idle sessions were killed.")
            val name = TmuxOps.sessionName(input.string("name"))
            val started = tmux(context, TmuxOps.startArgv(name, input.number("cols", 120).coerceIn(40, 400), input.number("rows", 50).coerceIn(10, 200),
                me.rerere.rikkahub.data.ai.tools.resolveTermuxWorkingDirectory(input.string("working_dir"), defaultWorkingDir)))
            if (started !is CaptureResult.Success) return@withLock sessionErrorEnvelope("session_start_unknown", captureError(started), name)
            val claimed = tmux(context, arrayOf("set-option", "-t", "=$name", "@rk_owner", sessionOwner(owner)))
            if (claimed !is CaptureResult.Success) return@withLock sessionErrorEnvelope("claim_failed", captureError(claimed), name)
            tmux(context, arrayOf("set-option", "-t", "=$name", "@rk_pinned", input.flag("pinned", true).toString()))
            if (!initial.isNullOrBlank()) {
                // One tmux command chain: failed text delivery must never be followed by Enter.
                val sent = tmux(context, TmuxOps.sendTextArgv(name, initial) + arrayOf(";") + TmuxOps.enterArgv(name))
                if (sent !is CaptureResult.Success) return@withLock sessionErrorEnvelope("initial_input_unknown", captureError(sent) + " Session exists; inspect it before retrying input.", name)
            }
            try { screenEnvelope(name, readScreen(context, name, DEFAULT_READ_LINES, null, 3000), !initial.isNullOrBlank()) }
            catch (c: kotlinx.coroutines.CancellationException) { throw c }
            catch (e: IllegalStateException) { sessionErrorEnvelope("initial_read_failed", "Session was created. Read it instead of starting another: ${e.message}", name) }
        }
    },
)

fun termuxSessionSendTool(context: Context, owner: String? = null): Tool = Tool(
    name = "termux_session_send",
    description = "Send literal input/control keys to this conversation's PTY, serialized per session. Read failure does not mean input failed. wait_for waits until a match or deadline. A screen match is not process completion.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("session_id", field("string", "Exact managed session ID")); put("input", field("string", "Literal text"))
        put("enter", field("boolean", "Send Enter; default true (set false for control keys only)"))
        put("keys", buildJsonObject { put("type", "array"); put("items", field("string", "tmux key name")) })
        waitFields().forEach { (k,v) -> put(k,v) }
    }, required = listOf("session_id")) },
    execute = execute@{ input ->
        preflight(context)?.let { return@execute it }
        val session = input.string("session_id").orEmpty()
        val text = input.string("input").orEmpty()
        HardlineCommandGuard.checkCommand(text)?.let { return@execute sessionErrorEnvelope("blocked_by_safety_floor", it) }
        val wait = input.string("wait_for")
        var baseline: String? = null
        val sendError = sessionLocks.getOrPut(session) { Mutex() }.withLock {
            ownedSession(context, session, owner)?.let { return@withLock sessionErrorEnvelope("session_access_denied", it, session) }
            baseline = if (!wait.isNullOrEmpty() && !input.flag("match_existing")) {
                val cap = tmux(context, TmuxOps.capturePaneArgv(session, DEFAULT_READ_LINES))
                if (cap !is CaptureResult.Success) return@withLock sessionErrorEnvelope("read_failed_before_send", captureError(cap), session)
                cap.stdout
            } else null
            val operations = mutableListOf<Array<String>>()
            if (text.isNotEmpty()) operations += TmuxOps.sendTextArgv(session, text)
            val keys = input.jsonObject["keys"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            if (keys.any { !it.matches(Regex("[A-Za-z0-9+_-]{1,32}")) }) return@withLock sessionErrorEnvelope("invalid_key", "Use tmux key names such as C-c or Enter.", session)
            if (keys.isNotEmpty()) operations += TmuxOps.sendKeysArgv(session, keys)
            if (input.flag("enter", true)) operations += TmuxOps.enterArgv(session)
            for (op in operations) {
                val sent = tmux(context, op)
                if (sent !is CaptureResult.Success) return@withLock sessionErrorEnvelope("input_outcome_unknown", captureError(sent) + " Earlier input may already have been delivered; read before resending.", session)
            }
            null
        }
        if (sendError != null) return@execute sendError
        // Only input is serialized. A long wait must not prevent C-c/kill from another call.
        try { screenEnvelope(session, readScreen(context, session, DEFAULT_READ_LINES, wait, resolveTimeoutMs(input), input.flag("wait_for_regex"), baseline), true) }
        catch (c: kotlinx.coroutines.CancellationException) { throw c }
        catch (e: IllegalStateException) { sessionErrorEnvelope("read_failed_after_send", "Input was sent; do not resend. ${e.message}", session) }
    },
)

fun termuxSessionReadTool(context: Context, owner: String? = null): Tool = Tool(
    name = "termux_session_read", description = "Observe this conversation's terminal without input. Wait for a literal pattern or an explicit bounded regex. Returns stop_reason and timed_out; does not claim command completion.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("session_id", field("string", "Exact managed session ID")); put("lines", field("integer", "0–2000 scrollback lines, default 200"))
        waitFields().forEach { (k,v) -> put(k,v) }
    }, required = listOf("session_id")) },
    execute = execute@{ input ->
        preflight(context)?.let { return@execute it }
        val session = input.string("session_id").orEmpty()
        ownedSession(context, session, owner)?.let { return@execute sessionErrorEnvelope("session_access_denied", it, session) }
        try {
            val wait = input.string("wait_for")
            val lines = input.number("lines", 200).coerceIn(0, 2000)
            if (wait.isNullOrEmpty()) {
                val cap = tmux(context, TmuxOps.capturePaneArgv(session, lines))
                if (cap !is CaptureResult.Success) return@execute sessionErrorEnvelope("read_failed", captureError(cap), session)
                screenEnvelope(session, ScreenRead(cap.stdout, "snapshot"))
            } else {
                val baseline = if (!input.flag("match_existing", true)) {
                    val cap = tmux(context, TmuxOps.capturePaneArgv(session, lines))
                    if (cap !is CaptureResult.Success) return@execute sessionErrorEnvelope("read_failed", captureError(cap), session)
                    cap.stdout
                } else null
                screenEnvelope(session, readScreen(context, session, lines, wait, resolveTimeoutMs(input), input.flag("wait_for_regex"), baseline))
            }
        } catch (c: kotlinx.coroutines.CancellationException) { throw c }
            catch (e: IllegalStateException) { sessionErrorEnvelope("read_failed", e.message.orEmpty(), session) }
    },
)

fun termuxSessionKillTool(context: Context, owner: String? = null): Tool = Tool(
    name = "termux_session_kill", description = "Close only a terminal owned by this conversation. Verifies tmux exit status; no idle cleanup.",
    parameters = { InputSchema.Obj(properties = buildJsonObject { put("session_id", field("string", "Exact managed session ID")) }, required = listOf("session_id")) },
    execute = execute@{ input ->
        val session = input.string("session_id").orEmpty()
        sessionLocks.getOrPut(session) { Mutex() }.withLock {
            ownedSession(context, session, owner)?.let { return@withLock sessionErrorEnvelope("session_access_denied", it, session) }
            val result = tmux(context, TmuxOps.killArgv(session))
            if (result !is CaptureResult.Success) sessionErrorEnvelope("kill_failed", captureError(result), session)
            else listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("killed", session) }.toString()))
        }
    },
)

fun termuxSessionListTool(context: Context, owner: String? = null): Tool = Tool(
    name = "termux_session_list", description = "List this conversation's terminals plus unclaimed legacy terminals. Other conversations' terminals are not exposed. Listing failure is distinct from an empty list.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
    execute = execute@{ _ ->
        preflight(context)?.let { return@execute it }
        if (owner == null) return@execute sessionErrorEnvelope("missing_context", "Conversation identity is required.")
        val result = tmux(context, TmuxOps.listArgv())
        val emptyServer = result is CaptureResult.OtherError && result.message.contains("no server running")
        if (result !is CaptureResult.Success && !emptyServer) return@execute sessionErrorEnvelope("list_failed", captureError(result))
        val sessions = (result as? CaptureResult.Success)?.let { parseSessions(it.stdout) }.orEmpty()
        val items = mutableListOf<JsonElement>()
        for (s in sessions) {
            val owned = tmux(context, arrayOf("show-options", "-t", "=${s.name}", "-qv", "@rk_owner"))
            if (owned !is CaptureResult.Success) return@execute sessionErrorEnvelope("list_failed", captureError(owned))
            val key = owned.stdout.trim()
            if (key.isEmpty() || key == sessionOwner(owner)) items += buildJsonObject {
                put("session_id", s.name); put("created", s.created); put("last_activity", s.lastActivity)
                put("ownership", if (key.isEmpty()) "unclaimed" else "this_conversation")
            }
        }
        listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("sessions", kotlinx.serialization.json.JsonArray(items)) }.toString()))
    },
)

fun termuxSessionManageTool(context: Context, owner: String? = null): Tool = Tool(
    name = "termux_session_manage", description = "Explicitly claim an unowned legacy rk_ terminal or change its pinned flag. Cannot take a terminal owned by another conversation. Does not send input.",
    parameters = { InputSchema.Obj(properties = buildJsonObject {
        put("session_id", field("string", "Managed rk_ terminal ID")); put("claim", field("boolean", "Explicitly claim an unowned terminal")); put("pinned", field("boolean", "Mark as long-lived"))
    }, required = listOf("session_id")) },
    execute = execute@{ input ->
        if (owner == null) return@execute sessionErrorEnvelope("missing_context", "Conversation identity is required.")
        val session = input.string("session_id").orEmpty()
        if (!session.matches(Regex("rk_[A-Za-z0-9_]{1,80}"))) return@execute sessionErrorEnvelope("invalid_session", "Only rk_ managed terminals can be claimed.")
        creationLock.withLock {
            val current = tmux(context, arrayOf("show-options", "-t", "=$session", "-qv", "@rk_owner"))
            if (current !is CaptureResult.Success) return@withLock sessionErrorEnvelope("session_not_found", captureError(current), session)
            val key = current.stdout.trim()
            if (key != sessionOwner(owner) && (key.isNotEmpty() || !input.flag("claim"))) return@withLock sessionErrorEnvelope("session_access_denied", "Only explicitly claimed unowned sessions can change ownership.", session)
            val claimed = tmux(context, arrayOf("set-option", "-t", "=$session", "@rk_owner", sessionOwner(owner)))
            if (claimed !is CaptureResult.Success) return@withLock sessionErrorEnvelope("claim_failed", captureError(claimed), session)
            val pinned = tmux(context, arrayOf("set-option", "-t", "=$session", "@rk_pinned", input.flag("pinned", true).toString()))
            if (pinned !is CaptureResult.Success) return@withLock sessionErrorEnvelope("pin_failed", captureError(pinned), session)
            listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("session_id", session); put("ownership", "this_conversation"); put("pinned", input.flag("pinned", true)) }.toString()))
        }
    },
)
