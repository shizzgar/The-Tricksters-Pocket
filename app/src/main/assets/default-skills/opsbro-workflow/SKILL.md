---
name: opsbro-workflow
version: 1
description: Diagnose and maintain Termux, Linux and explicitly selected SSH environments with scoped changes, recoverable configuration and health checks.
auto_load: true
requires-any-tools: [termux_run_command, workspace_shell, ssh_exec, ssh_exec_saved]
---

# OpsBro · environments and operations

Use for environment failures, dependencies, services, disk/process diagnosis, backup and recovery. Read `references/operations.md` before modifying a running service or setting up recovery.

1. Establish the exact target: app workspace, local Termux or saved remote SSH host. Confirm the target from observed host/OS/path evidence, not from a prior command on another machine. State the desired outcome and service scope.
2. Begin with bounded inspection: installed executable paths/versions, disk availability, relevant process/service state and redacted error excerpts. Use declared tools only. `scripts/environment_probe.py` reports local platform, disk and available executables without subprocesses, networking or changes. It reports only the machine where it runs.
3. Form a specific hypothesis from the failure. Check target-specific assumptions: Termux is Android, may have no root/systemd/glibc; Linux servers may use different init/package systems; SSH may target Windows. Do not blindly use `sudo`, `apt` or `systemctl`.
4. Before a change, identify affected configuration and users, save a scoped recoverable original if authorized, describe the effect and rollback. Use existing permission within its scope. Diagnostic requests alone do not authorize installs, restarts, deletions, firewall or public exposure changes.
5. Apply the minimum justified change through an enabled tool. Verify command result and service health using the same target and intended scenario. Check logs after the change with secrets redacted. Do not call a process merely running a complete success.
6. Deliver findings, exact target, change summary, evidence, recovery path and unverified limits. Avoid background monitors/schedules unless requested. Preserve remote credentials and never copy private keys into reports.

No declared execution/SSH tool means no live operation: analyze supplied logs/configuration and give the precise missing capability. Never route a disabled integration through shell or another assistant to evade its restriction.
