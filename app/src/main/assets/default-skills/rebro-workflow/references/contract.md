# Shared Rebro contract 2.0

## Skill, script and authorization

A skill selects procedures, inputs, checks and recovery. A script performs the
mechanical part. Script success does not prove that the selected patch is correct.
The current user task determines tool/target authorization; a skill grants no new
permissions. An already authorized installation needs no repeat confirmation.
Plan/apply separate artifact preparation from application; they do not require
an additional conversation by themselves.

This kit is **for RikkaHub**, not installation of personal skills into ChatGPT.
Call `use_skill` with the exact enabled name. Use only its successful `skill_root`,
with `termux_skill_sync` when needed. Each package is self-contained: do not access
neighboring skill directories or guess versioned paths. Set `working_dir=skill_root`
and explicitly choose `python3`/`bash`. Store results outside skill_root.
Use `python3 -B` when calling vendor tools directly to avoid creating bytecode
inside the synchronized package; the main kit wrappers already do this.

## State and artifacts

`case.json` records case ID, package, Android user, mode and toolkit version.
In sign-only mode without installation, user may be null: do not select profile 0
for the user. Before the first installation, bind the known profile with
`caseflow bind-user`; an existing binding cannot be changed to another profile.
Do not reuse a case ID across applications/profiles. Main routes:

| Mode | Stages |
|---|---|
| full | environment → acquire → analyze → patch → build → sign → install → verify |
| sign-only | environment → intake → sign → install → verify |

Stop at the requested outcome: signing ends at sign; installation and functional
execution occur when part of the task. `intake` is handled by rebro-sign and checks
all supplied APKs. One base and consistent split IDs do not prove split dependency
closure. For installed sources, `--acquisition` links the manifest to every copy
in the original snapshot; assembly/sign preserve its topology. Establish external
set completeness from provenance and manifest dependencies, or explicitly leave it
unverified. Frida supports analyze/verify; it is not a mandatory separate stage.

Create a new attempt for each execution with `caseflow.py begin`.
Pass an **explicit parent receipt**; never select the "latest APK" by mtime.
`finish` creates a receipt with pass/failed/blocked/unknown status, input/output/
evidence hashes and the parent receipt. `check` revalidates the entire specified
chain. If an input APK, patched tree or signed file changes, checks fail; do not
"repair" old results by replacing their recorded hash with the current one.

`caseflow` checks integrity and some structure, not the truth of the agent's claims
about app behavior. The local file owner can rewrite JSON; this protects against
accidental inconsistency, not independent attestation.

| Artifact | Meaning |
|---|---|
| source-set.json | Source APKs, identity, signatures, hashes and set provenance |
| analysis.json | Patch objective, target split, observations and behavior criteria |
| patch-plan.json | Exact preimage hashes and operations |
| patch-report.json + tree/ | Verified diff and new tree |
| candidate-set.json | Set after build/intake; remaining splits may retain old signatures |
| signed-set.json | All APKs signed with one expected key and checks passed |
| install-plan.json | Verified current state and specific installation |
| install-report.json | PM session state and installed-byte reconciliation |
| verification.json | Functional checks with evidence |

Do not infer `candidate`/`signed` from filenames: use the manifest, verify hashes,
and repeat tool verification at consequential boundaries. This kit handles ordinary
APKs and split sets of one package/version. APEX, key rotation lineage, SDK libraries,
intentional package/version/split-topology changes and system apps need separately
analyzed procedures.

## Receipt commands

```sh
python3 scripts/caseflow.py init --case "$REBRO_CASE" \
  --package com.example.lab --android-user 0 --mode full
python3 scripts/caseflow.py begin --case "$REBRO_CASE" --stage patch \
  --parent "$ANALYSIS_RECEIPT" --input "$DECODED_TREE" --input "$PATCH_PLAN"
```

Keep the returned attempt.json path. If the plan references payload files, include
them/the payload directory as separate inputs. For manifest-based operations,
the manifest contains APK hashes that `check` also verifies. Every variable stands
for an already established absolute path from tool results; shell state may not
persist across tool calls.

```sh
python3 scripts/caseflow.py finish --attempt "$PATCH_ATTEMPT" --verdict pass \
  --evidence "$PATCH_OUTPUT/patch-report.json" --output "$PATCH_OUTPUT"
```

Do not declare the entire attempt/case directory as output: it contains mutable
control files. Put results in `data/` and receipts beside it. Do not append logs
after finish; use a new file/attempt for the next experiment. After a crash between
receipt writing and attempt readback, reconcile both; an existing receipt rules
out repeating finish.

## Resource limits

Run one JVM task through shared `run_job.py`, default heap 1536 MiB and 2 CPUs;
start signing with 512 MiB. JVM lock `$HOME/rebro/.locks/jvm.lock` is shared across
skills. It is advisory; manually launched JVMs do not honor it.
Do not use `resource light` for commands launching apksigner/JADX/Apktool.
Heap+reserve is preflight policy, not an RSS limit or LMKD protection.
Heavy commands require timeouts, log limits and free-space checks.

Use managed `termux_job_start`/`wait`/`cancel` for long work, retaining the job ID
and independent stdout/stderr cursors. Dispatch is not completion. A started/running
file can survive SIGKILL, reboot or force-stop: reconcile boot ID and actual state.

## Frida baseline and root

The supplied device baseline uses `127.0.0.1:27044`; preserve its existing
service/extension/patched bridge. No automatic upgrade, restart, port change or
permissive mode. Early source messages contained shortened hashes: fill nulls in
example manifests from the trusted baseline, not by declaring current bytes trusted.
Later full pins are documented in rebro-environment; verify their relevance locally.
Use Termux UID for ordinary work and scoped su only for specific Android reads/PM actions.

## Retry and rollback

- Read-only checks may be repeated. A new patch/build/sign uses a new directory.
- Do not repeat installation after a transport timeout; reconcile first.
- After failed/blocked, change the cause/plan while preserving the previous receipt.
- File rollback: return to the original input and create a new attempt.
- Rolling back an installed APK does not roll back app data. Database schemas,
  Keystore and first-launch side effects may be irreversible without a prepared backup.
- Do not automatically uninstall or run pm clear after a mismatch or failed test.
