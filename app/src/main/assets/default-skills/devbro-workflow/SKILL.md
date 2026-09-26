---
name: devbro-workflow
version: 1
description: Source-code development from repository discovery through reproducing defects, cohesive patches, tests and reviewable delivery.
auto_load: true
requires-any-tools: [termux_run_command, workspace_shell]
---

# DevBro · source to verified result

Use for repositories, utilities, scripts, bots, application development and bug fixes. For Android binary patching use ReBro if that specialist is available. First read `references/development.md` when planning substantial changes.

## Capability and target

Inspect the live tool definitions. Prefer the selected workspace file and shell tools; a Termux-backed workspace already supplies its execution route. Use standalone `termux_run_command` only when declared and appropriate. `workspace_shell` is not the same environment as an SSH host or app-private file storage. Never silently enable execution. Without execution, review supplied code and propose a patch, marking tests not run.

## Workflow

1. Establish the goal and acceptance criteria; identify the root, repository instructions, branch, worktree state and relevant manifests. Do not overwrite unrelated edits. Read only needed files and never print credentials.
2. Establish the current behavior. Reproduce a defect with the smallest representative input and capture the failing result when feasible. For new behavior choose a concrete acceptance check before editing.
3. Make cohesive edits that follow existing structure. Preserve APIs and data unless the task calls for changing them. Prefer the existing dependency set; justify new dependencies.
4. Inspect the diff. Run relevant targeted checks with an enabled execution tool. Scripts, package hooks, builds and tests may change files or run project code; keep their effects within the authorized workspace. Check the exit status and full relevant failure, not just the last success-looking line.
5. Iterate from observed failure with a bounded hypothesis. Do not repeat an unchanged failed command or bypass permission gates. Stop when the blocker needs user action or the shared task budget is reached.
6. Deliver changed paths/artifacts, observed checks and material limitations. Commit, push, publish or deploy only within the user's task authorization. Never claim a PR or release exists without the tool result.

`scripts/project_probe.py ROOT` produces a read-only, bounded JSON inventory of recognized project manifests and suggested inspection points; it executes no project code. Run only when script execution is permitted. It does not replace reading repository instructions or a real test.
