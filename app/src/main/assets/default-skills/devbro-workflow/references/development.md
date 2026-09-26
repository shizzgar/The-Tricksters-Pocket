# Development procedure

## Discovery

Read repository instructions from the project and relevant parent directories. Inspect current status/diff before editing; identify user-owned changes. Locate language/runtime versions, build entry points, lockfiles, tests and CI definitions. Separate project source from generated, vendor, cache and dependency directories.

The bundled probe recognizes manifests without installing or running anything. Suggested commands must be verified against the repository scripts and current toolchain. A package.json is not permission to run install hooks; a wrapper may download binaries.

## Defect report and patch contract

Capture: expected behavior; observed behavior; input/environment; minimal reproduction; suspected component; evidence that would reject the hypothesis. Test the pre-change behavior where possible. For a data/schema migration include old-data fixtures and preserve rollback/recovery possibilities.

Keep the implementation focused. Check error/cancellation paths, async state ownership, boundary inputs, and user-edited configuration. For a UI change check an actual rendered screen or clearly state that visual verification was unavailable.

## Validation selection

Choose checks from project instructions and changed behavior: type/lint check, targeted regression, integration/build, then required CI gates. Do not broaden indefinitely after the concrete risk is resolved. Distinguish compile success, test success, emulator behavior and real-device behavior. Record command, root, revision, exit result and report/artifact path. Never paste tokens from logs.

## Handoff

- Scope and acceptance criteria.
- Exact revision/worktree state and artifact path/hash if available.
- Checks: passed, failed, not run, with reason.
- Remaining risks and reproduction steps for unresolved failures.
- Rollback or user action only when necessary.
