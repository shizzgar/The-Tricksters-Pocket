# Compaction and Termux reliability update

This change builds on the configurable long-context deadlines. It does not alter the inference router, SELinux, root implementation or Frida service.

## Compaction contract

The stored conversation is the source of evidence. The model's active context is a bounded projection: one coherent continuation handoff, a small selection of quoted recent user requests, a structured evidence index and the retained raw tail. Compaction never deletes original messages.

- The handoff preserves the current objective, latest narrowing/corrections, constraints and approval scope, observations, hypotheses/refutations, completed verification, live resources and next steps.
- Every multi-part map pass now goes through reduction, even if simply concatenating its outputs would fit. Later corrections must supersede interim diagnoses; unresolved contradictions remain explicitly unresolved.
- Complete JSON evidence entries carry call IDs, input excerpts, observed status/exit codes, head/tail output excerpts and available artifact/job/session references. They are marked partial, not authoritative complete execution records.
- The index favors recent calls and older errors. Routine calls can be omitted from the index without being deleted. A bounded index cannot preserve every old result in context.
- `conversation_history_read` searches and pages the stored originals of **this conversation only**, by call or message ID. Older tool output already truncated at capture time cannot be recreated; a new tool archive or managed job log avoids that loss going forward.
- Repeated compaction builds the index from original messages rather than repeatedly shortening old previews. Old index blocks are removed from summarizer input to avoid duplication.
- Prose and deterministic additions share the configured target. Oversized output is re-summarized; incomplete/empty/filtered summaries and non-shrinking replacements are not committed. The selected message boundary is revalidated before persistence.
- Existing explicit raw-tail retention/fallback remains message-based. A very large assistant message may force full compaction. `retained_raw_tool_calls=0` describes that tail, not whether evidence remains in the summary/history.
- The source estimate and new summary estimate are approximate, not tokenizer measurements.

Reference designs inspected (implementation is native to RikkaHub, not copied):

- [Codex compact.rs](https://github.com/openai/codex/blob/main/codex-rs/core/src/compact.rs): a replacement history distinct from stored history, retention of user context and bounded summaries.
- [DSH compaction](https://github.com/deepseek-ai/deepseek-harness/blob/ddefc45fbc7f8e46dd73185e68295696d1297887/docs/subsystems/compaction.md): durable evidence versus active context, coherent replacement, span revalidation and cancellation/failure without false success.

## Terminal contracts

### Existing capture calls

`success` now describes command success (`exit_code == 0`); `transport_success` describes successful delivery of its result. Timeout explicitly means the outcome is unknown, not that Termux is misconfigured or the process was killed. Coroutine cancellation propagates. Manual rerun respects terminal-specific deadlines rather than an independent 60-second cutoff.

Capture output is archived in app-private storage before the model preview is cut. `output_ref` can be read with `termux_output_read` without workspace tools. Caps are 8 MiB per stream and 128 MiB for this archive. Quota/write failures and archive truncation are explicit. RUN_COMMAND may impose its own earlier cap; use managed jobs for large output.

### Persistent terminals

New tmux sessions are owned by their conversation, with an exact target ID. Other chats cannot send/read/kill them. Existing unowned `rk_` sessions can be explicitly claimed with `termux_session_manage`; ordinary user sessions remain outside the managed namespace. Quiet terminals are no longer automatically killed at six hours. Pinning is recorded for long-lived services.

All tmux operations check exit status. Failed list/kill no longer report empty success. Input is serialized per terminal, but observation does not hold that lock and cannot prevent an interrupt. `wait_for` waits for the condition or its deadline; settling is only used without a condition. Literal matching is the default, regex is explicit and bounded. Old screen matches are excluded for send by default. Echo can still match: screen observation never proves process completion. Every read reports a stop reason and timeout separately.

### Durable jobs

Enable the existing Termux tool group. Python in Termux is required for the private supervisor; no listening service or startup job is installed.

| Tool | Purpose |
|---|---|
| `termux_job_start` | Launch once per operation ID; return durable job ID |
| `termux_job_list` | Reconcile existing jobs after lost response/app restart/reboot |
| `termux_job_read` | Read stdout or stderr by the returned UTF-8 byte cursor |
| `termux_job_wait` | Wait up to 60 seconds without changing job lifetime |
| `termux_job_cancel` | Request process-group cancellation and report whether confirmed |
| `termux_job_forget` | Remove reviewed finished logs while preserving the deduplication receipt |

The operation ID identifies **one intended launch**. Same ID + same command/cwd/deadline returns the existing job; same ID + different request is a conflict. A new intentional execution needs a new ID. This is durable deduplication, not a guarantee of exactly-once external effects under every OS/storage failure.

`termux_run_command(background=true)` routes to the managed supervisor when a conversation identity is available. The returned job can then be read/waited/cancelled using the same tools. It no longer needs invisible `/dev/null` output for ordinary managed background work.

Workers persist start identity, logs, counts and terminal status in Termux private storage. Reconciliation checks boot identity and `/proc` start ticks. Android supplies `Settings.Global.BOOT_COUNT` through its platform API, avoiding dependence on app access to `/proc/sys/kernel/random/boot_id`; the latter is a fallback when the platform marker is unavailable. A vanished worker is unknown, not successful. Lost responses never cause an automatic new launch. On the next agent turn, an interrupted `termux_job_start` is reconciled automatically through the enabled read-only job observer using its saved operation ID. Other uncertain terminal operations are marked unknown with an explicit reconciliation route. This restores observation; it does not silently rerun interrupted mutations or resume a model turn without user action.

Limits: four active jobs; default one-hour execution deadline (request override 1–86400 seconds); 8 MiB per output stream; 256 MiB job-log store. Extra bytes are drained/discarded after the cap, and loss is reported. Jobs continue after cancellation of an observation request. Explicit cancellation signals their original process group; detached/new-session or privileged descendants may escape and are outside the confirmation scope. The worker bounds pipe draining after command exit and reports lingering descendant pipes.

The helper is versioned by content hash and remains available to existing workers across app updates. Finished-log cleanup retains operation receipts, preventing an old retry from unexpectedly relaunching a finished task.

## Validation and phone acceptance

Host-side subprocess tests cover deduplication/conflicts, nonzero exit/stderr, short waits and reconnects, process-group cancellation, execution deadlines, UTF-8 pagination, log caps/cleanup, owner isolation and stale identity after reboot. Kotlin tests cover complete evidence JSON, strict index budgets under many long calls, recent/error selection, repeated compaction, scope retention, Unicode and terminal waiting.

The Android build and JVM suites run in GitHub Actions. Host tests do not establish Samsung/KernelSU behavior. On the phone check:

1. A long diagnostic dialogue compacts to a coherent handoff; retrieve an omitted old tool result by ID and verify the original command/result.
2. Start a job that prints, sleeps and prints again; a one-second wait expires while the job remains running. Reopen the app, list/read the same job, then verify completion.
3. Start a second command with the same operation ID and verify no duplicate side effect. Cancel a separate harmless long job.
4. Open a PTY in one chat and verify another chat cannot mutate it; explicitly claim only a chosen legacy terminal.
5. Print an expected marker after several seconds of quiet; `wait_for` must not return early. Test Ctrl-C during a separate long observation.

Semantic summary quality on the user's Qwen deployment still needs live evaluation. The runtime preserves evidence and rejects incomplete commits; it cannot turn an inaccurate model inference into a verified fact.
