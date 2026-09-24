# Installation: prepare, apply once, reconcile actual state

## Scope

This procedure handles an ordinary lab APK/full split set and an explicit Android
user. Package Manager shares app code paths across profiles: `--user 0` does not
mean a code update cannot affect another profile. System packages, APEX, downgrade,
key rotation and split-topology changes are outside the automatic installer; these
are separate tasks, not errors to bypass with flags.

Installation/update authorization comes from the current request. If only a patch
or signature is requested, stop at that artifact. An already authorized installation
can apply a ready plan without another question. Expanding to other profiles,
uninstalling an app or deleting its data requires separate authorization. The
script does not uninstall, pm clear, reboot or downgrade.

## Read-only plan

```sh
python3 scripts/run_job.py --job-dir "$INSTALL_PLAN_JOB" --cwd "$SKILL_ROOT" \
  --heap-mib 512 --seconds 600 -- \
  python3 scripts/install_set.py plan --set "$SIGNED_SET" \
    --android-user 0 --out-dir "$INSTALL_PLAN_DIR"
```

Plan uses root reads and writes evidence into a new directory:

1. Reverify actual signatures/alignment of every input APK.
2. Enumerate Android users and presence of **only the target package**.
3. Read actual installed-set paths/hashes in every affected profile.
4. Copy original installed APKs into Termux and inspect identity/certificates.
5. Compare signer, version, split topology, system-app status and user scope.
6. Repeat the snapshot to detect changes during preparation.

Output: `install-plan.json`, its full SHA-256 and ready/blocked status.
Certificate mismatch blocks updating the installed app; root cannot replace the
author's signing key. Do not uninstall automatically. Use `--allow-other-users`
only when impact on the listed profiles is already authorized. A plan is valid
for 10 minutes; prepare another after expiration.

## Apply

```sh
python3 scripts/install_set.py apply --plan "$INSTALL_PLAN" \
  --expected-plan-sha256 "$REVIEWED_PLAN_SHA256" --out-dir "$INSTALL_RESULT_DIR"
```

Run as a managed job with an outer deadline, not as daemon/root Python: the script
stays under Termux UID and individual `su -c pm ...` calls perform PM operations.
Apply has no JVM commands; use resource light if wrapping it with run_job.

Before the first mutation, compare plan hash, APK hashes, boot ID and current
snapshot. Write a single-use execution journal for the plan. Create the full set
in one PM session; pass APKs to `install-write` **through stdin**, without chmod on
Termux home or mandatory root staging. Persist `commit_requested` before commit.
After `Success`, read actual installed paths/hashes. `commit_requested` is a
checkpoint before calling PM, not proof that PM received the command; reconcile
its outcome carefully.

Only an exact full installed-set match with the intended set gives install pass.
If the intended bytes are already installed for the intended profile at plan/apply,
record already_present without reinstalling. The script does not launch an Activity
or establish functional behavior.

## Failure and unknown outcome

| State | Action |
|---|---|
| Error before create | Fix preflight; create a new plan |
| Create response lost | unknown; inspect logs/session state, do not blindly create again |
| Write error with known session ID | Abandon only the owned session; preserve cleanup result |
| Commit sent, no response | unknown; read-only reconcile first |
| Success received, hashes differ | unknown: possible race/external update; not pass |
| SIGKILL/force-stop | Read execution journal; started/commit_requested does not mean failed |

Reconcile the previous managed job first: losing the client does not mean the
process stopped. Use saved job ID, status, boot ID and stdout/stderr cursors.
Do not reconcile while the old executor is still changing evidence. Do not remove its lock.

```sh
python3 scripts/install_set.py reconcile --report "$INSTALL_REPORT" \
  --out "$RECONCILIATION_JSON"
```

`--report` also accepts complete `execution.json`, which contains a report copy.
Compare it with report_path and logs: the two files are written sequentially and
may reflect different checkpoints after interruption. Three phase/session/status
fields without plan, its hash and target identity do not replace the full journal.

Reconcile installs nothing: it compares current and intended state and writes a
new record. A full match can close the install receipt with that record; otherwise
status remains unknown. A mismatch does not prove the old session can never finish.
Inspect its actual state first; there is no automatic retry. The script does not
query PM session state. After mismatch, investigate its ID/ownership/terminal state
for the actual device, starting with that build's `pm help`. The toolkit does not
promise a universal `install-status` command.

CLI exits: ready/pass=0, blocked plan=3, unknown reconcile=4; exceptions return
nonzero with diagnostics. Always read JSON status and concrete evidence.
If the install attempt is still open, close it with reconciliation evidence.
If an unknown receipt already exists, do not rewrite it: a new install attempt
from the same passing sign parent may reconcile the old operation without reinstalling.

The old plan is single-use; repeat apply is forbidden. Resolve previous uncertainty
before preparing a new plan for a new operation. The global install lock coordinates
only this toolkit; other PM callers remain independent.

## Recovery and boundaries

Saved APKs back up code, not user data. An update may migrate databases; first launch
may change remote state. Before a risky test, prepare a suitable data backup and
restoration criterion or use a separate lab target. Successful APK rollback does
not guarantee that the old app can read the new database.

The PM parser is deliberately strict: unknown output, root errors or unavailable
users stop execution. Check Android/Samsung `pm help` and acceptance on an owned lab
APK first. The kit's original validation did not perform a real device installation.

Sources: [ADB/PM](https://developer.android.com/tools/adb#pm),
[AOSP PM implementation](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/pm/PackageManagerShellCommand.java).
