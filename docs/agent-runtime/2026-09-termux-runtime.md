# Termux presentation and long-context inference

The Termux detail sheet handles command capture and all five managed-session operations.
It uses selectable, soft-wrapped monospace text, copies the original text, and exposes raw
arguments/results separately. Large text has an explicit display limit and a show-more
control; existing tool-output truncation markers are preserved. JSON is never rendered as
HTML or executed. `success: true` does not override a nonzero exit code. Detached launch,
visible-terminal dispatch and session-screen reads are not presented as command completion.

Settings → Models now exposes text-generation connection, first model response, read/idle and total request
limits plus an application-wide generation concurrency limit. Defaults are 20 seconds,
30 minutes to the first model event, 30 minutes read/idle, 60 minutes total and 2 requests.
The streaming first-response timer starts after the local queue. SSE comments do not reset it. The total coroutine budget
includes waiting in the application queue and remains effective after SSE headers.
Compaction defaults are 30 minutes/request, 90 minutes/operation, parallelism 2. The default
agent turn budget is 60 minutes, with compaction's separate cumulative allowance.
Existing explicitly saved timeout values are preserved. Tool timeouts are unchanged.

One non-preemptive priority queue in ProviderManager covers text calls across providers:
continuation → required automatic compaction → interactive chat → background/manual
compaction → title. Cancellation while queued removes the waiter, and cancellation after
dispatch propagates to the provider. Running requests are not forcibly preempted. The app
cannot prioritize requests originating from other clients or repair the router's own queue.

Session namespaces preserve the main chat's routing key:
- chat: conversation UUID
- compaction: conversation UUID + `:compaction:` + operation UUID + part number
- title/suggestions: conversation UUID + task name + message UUID

Only the `primary`/`secondary` values of X-Triage-Backend are recorded in transport logs,
along with header latency, status and completion/failure. Header latency is not TTFT.
Existing retry policy still forbids replay after meaningful streamed output.
SSE parser tests exercise `: keepalive` comments through an actual HTTP socket.
The Qwen request-contract test checks low reasoning effort and absence of
thinking_token_budget for background generation with the model's REASONING capability.
For compaction Chat Completions calls, the final body removes thinking_token_budget and
normalizes an existing reasoning_effort to low, including custom-body overrides. Ordinary
chat overrides are preserved. The isCompaction marker is local metadata, never a JSON field.

Selected upstream commits ported from rikkahub/rikkahub:
- 6e98691cd578580a446ad48efdee69f161cbac60: omit unsupported tool-result name
- a7850967f455389da386ac4c0d8086ff2ceb4394: disable inline code ligatures
- d47d13a6d4a9d4827e455253e9f2f79e8c55b54a: destructive regeneration warning
- 445341e91a63fd7731ab61d7d715d3a12e155267: favorite deletion undo ordering
- 458c16df6575e233d035eb422e7629297639a751: preserve and disambiguate fork titles

The background session-ID idea from bd936caa is adapted with separate namespaces rather
than reusing the foreground ID. The larger upstream QuickJS migration, session-manager
replacement, theme/dependency changes and bulk file export are not included in this patch.
They require separate integration work to preserve fork-specific cancellation, persistence
and output limits.

Validation is performed by .github/workflows/compaction-debug.yml. Device checks remaining:
320/360 dp widths, increased font scale, long paths and single-line commands, multiline
output, failed exit, timeout, detached launch, session screens, and a full cold-context
compaction against the user's router. No device or aitower validation is implied by CI.
