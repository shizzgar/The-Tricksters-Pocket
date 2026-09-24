---
name: netbro-legba
description: "Use Legba for authentication checks and protocol enumeration on specified services: select plugins and finite credential/payload inputs, control load, manage sessions and report without leaking passwords."
---

# Legba

Check legba --version and --list-plugins; read the selected plugin's help and
[procedures](references/operations.md). Use netbro-environment for installation.

1. Select the specific service and authorized operation. A port/banner check
   does not imply a credential-testing task.
2. Verify input files exist and establish a finite attempt count. Omitted
   username/password inputs may enable generated combinations; a nonexistent
   file path may be interpreted as a literal.
3. Explicitly supply required inputs or a finite combinations file. Do not
   automatically run usernames×passwords when only specified pairs are needed.
4. Set concurrency/rate, deadline and the task's lockout constraints. Begin with
   a small diagnostic run; do not increase load after 429 responses, account
   lockout, service instability or ambiguous matcher results.
5. Use a unique session and output in a private case directory. Run long work as
   a managed Termux job; reconcile the old job before resuming its session.
6. Distinguish partial matches, full matches and verified successful logins.
   For HTTP, check the success criterion: a page/status 200 alone may represent
   a failed login.

Do not pass literal passwords in tool arguments or print them in chat.
Session/output files may contain credentials even when stdout is quiet.
For reports, load netbro-workflow and use summarize.py legba: it reports counts,
plugin, target and partial status while excluding credential data.
