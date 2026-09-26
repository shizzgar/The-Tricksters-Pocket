# The Trickster's Pocket: execution and skill access

This contract was checked against the PR #1 guide at head
`d7210a5dd2782f7da9b8c4eefb1d16c02921261d`. The installed APK may be another build;
current tool schemas, not this document, determine field names and availability.

## Connecting packages

1. Import the required ZIPs from imports/ separately and connect them to the selected assistant; built-in presets already include their default kit.
2. Enable Termux tools, full-package transfer and the required skills.
3. Call use_skill / termux_skill_sync. Take working_dir from the returned skill_root.
4. Write config/output to the case or another external directory; keep skill_root
   unchanged. For Python, use `python3 -B scripts/...`.

In the reviewed revision, **all skill tools are hidden when no existing skill is
connected**. Disabling use_skill also hides the other skill tools. The agent cannot
create the first skill through an invisible skill_create: connect one in the UI first.
A system prompt does not enable tools or change exclusions.

Three compatible Android package IDs were observed on the original phone. Their Termux copies
are separate; do not use skill_root from another installation. Imported skill version
and app APK version are distinct. A new sync does not replace files used by a running job.

## Short command, job and PTY

Use termux_run_command for bounded reads when available. command and
executable+arguments are alternative forms; consult the current schema.
Captured completion differs from dispatch to a visible terminal. Inspect exit_code,
stdout/stderr, timeout, truncation and returned output references.

For long noninteractive work, use managed termux_job_* with a stable operation_id.
After a lost response, reconcile the previous job. Preserve byte cursors unchanged.
A wait timeout does not cancel execution. A background process alone does not
schedule the model's next autonomous turn.

Use termux_session_* for interactive programs, with the exact returned ID.
Screen contents, echo and wait_for text are not exit status; save a fresh marker/result
to a file. send/read can fail after input delivery: inspect before resending.
A service and its client have separate lifecycles.

## Agent edits to skills

The skill creation/editing group is disabled by default. Even with a connected
skill, use only tools exposed to the current assistant:
skill_create/list_files/read_file/write_file/edit_file/manage_files/delete.

Before writing, obtain the tree revision and then the file's sha256. A stale pair
means a conflict, not permission to remove the check. An exact edit requires a
unique match. The reviewed limits were 16 KiB read pages, 256 KiB text writes,
64 KiB binary writes, and 200 files/20 MiB per package; consult current tool schemas.

Edits to a shared package affect all assistants connected to it. skill_delete removes
the package, drafts/recovery and all connections; it is not a way to disable one skill
for the current task. Debugging case scripts does not require modifying the package.

Tool availability may change between requests and is checked again on invocation.
Do not execute a disabled tool through old history or another private path.
Writing a skill does not execute its scripts: sync after an intentional package change.

Source: [guide at the reviewed head](https://github.com/shizzgar/The-Tricksters-Pocket/blob/d7210a5dd2782f7da9b8c4eefb1d16c02921261d/docs/agent-runtime/trajectory-and-termux-skills.ru.md).
