# Tool hooks

Hooks attach user-configured instructions to the next model request when a completed tool observation matches a rule. They share the prompt-injection area, but match structured tool events rather than ordinary chat text.

## Create a rule

Open **Extensions → Prompt injections → Hooks**, or **+ → Tool hooks** in a chat. The chat entry initially scopes new rules to that chat; the library starts from the selected assistant. The JADX starter is disabled until you finish configuring and enable it.

Configure:

- A name, enabled state and priority.
- Exact tool names, optionally a recognized executable or literal command text.
- An outcome: completed, known nonzero exit, known success, tool error or execution timeout.
- Optional text in stdout and stderr. All configured conditions must match.
- An inline prompt, or an installed skill file and optional Markdown heading.
- Assistant, workspace and/or conversation scopes; these selectors are alternatives. Global scope is also available.
- A per-rule limit, from 1 to 10 matches per user turn, defaulting to 1.

A typical JADX rule selects `termux_run_command`, executable `jadx`, and a known nonzero exit. Matching a specific diagnostic in stderr narrows the rule to incorrect flags rather than every failed decompilation. The app does not assume all nonzero exits mean incorrect program usage.

The executable matcher supports direct argv and a conservative subset of shell commands. Quoted mentions such as `echo jadx` do not count as a JADX invocation. For complex shell expressions, use the explicit command-text condition; this matches text and does not prove which subcommand produced a script or pipeline exit status.

## Instructions and scopes

Skill actions read an installed text/Markdown file. A selected heading includes its subsections; missing or duplicate headings produce a visible error. The resolved instruction, including a selected section or an inline prompt, has a 16,000-character limit. Oversized instructions are rejected rather than silently cut. Selecting a skill here explicitly binds its instructions to the hook independently of the assistant’s ordinary skill toggles.

Direct assistant, workspace and chat selectors apply to matching conversations, including child conversations that directly match. The inheritance setting additionally enables matches through ancestor assistants/workspaces/chats and global rules in child sessions. Workspace matching uses the effective project/assistant workspace both in execution and preview. Delivery and repetition limits are isolated per conversation.

## Preview and inspect

**Test on a past call** reads stored tool arguments/results and shows which conditions match. It does not execute a command or enqueue a prompt. A disabled draft can be evaluated as if enabled; the preview labels this explicitly. Runtime repetition and pending-queue limits are enforced during actual execution.

A matched tool card shows a compact hook notice. Open it to inspect the rule name, match reason, source and exact instruction snapshot. Pending means waiting for a model request. Included in request means dispatched with a request; it does not prove the model followed the instruction. Skipped notices explain content or limit problems. Restored pending notices without delivery metadata show that their delivery state is unavailable.

## Runtime behavior

Tool results remain intact. Hook evaluation happens after each tool’s own capture or pagination limits, but before the generation loop shortens or spills its output; a hook does not rerun the tool. Instruction text is added to the model request without inserting a new user turn or splitting tool-call/result pairs.

Interactive launch success and missing exits do not satisfy a nonzero-exit condition. Command timeouts are distinct from a managed-job wait timeout: the latter ends observation while the job may still run. Termux and Workspace background results retain their launch identity, so polling the same completed job does not repeatedly trigger a hook. Explicitly rerunning a tool creates a new execution attempt.

The queue and consumed identities are persisted atomically. Preparing or cancelling a queued request does not consume instructions. A failure before the first model content returns the same instruction to the queue, including context-overflow recovery. After a process restart, an uncertain dispatch can resend the instruction, but never re-executes the tool. Confirmed responses keep their instructions consumed.

Up to 16 matches per conversation turn and 16 pending instructions are accepted, with a combined 32,000-character instruction budget. Higher-priority rules are evaluated first. Limits skip whole instructions with an explanation. Disabling/removing a rule or moving outside its scope prevents its pending instructions from being used at the next preparation.

The composer, message statistics and overlay count pending instructions. Prepared instructions are counted once; a finished request’s one-shot addition is removed from the next-request estimate. These remain token estimates rather than exact provider tokenization.

Rules are included in settings backup. Tool notice snapshots stay with conversation history. The local pending-delivery queue is not transferred by a portable backup, so importing a backup does not automatically replay old pending instructions. Deleting a conversation removes its runtime hook metadata.

## Validation

Pocket.6 CI and Android visual verification are in progress. Real Termux and device-specific process behavior still require a physical-device check.
