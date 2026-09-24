# Orchestration by stage results

## Select the objective and route

Use rebro-workflow for complex tasks; a focused signing/diagnostic task can enter
the specialist skill directly. RikkaHub provides the enabled-skill list. The
orchestrator does not execute another skill magically: call use_skill, obtain
skill_root, run the script and inspect the result. Treat a disabled skill as
unavailable; do not guess its private path.
Every new APK case requires enabled `rebro-environment`; neither the orchestrator
nor sign includes its doctor. Even a short sign-only route calls that skill.

| Request | Minimal route |
|---|---|
| Understand an app feature | environment → acquire → analyze; Frida if needed |
| Patch and deliver a signed APK | environment → acquire → analyze → patch → build → sign |
| Sign this completed APK | environment → intake(candidate) → sign |
| Install and verify the resulting patch | Continue from a verified signed receipt → install → verify |
| Frida stopped attaching | environment + rebro-frida diagnosis; no APK rebuild |

Work within existing authorization. Clarify only genuinely unresolved package/user,
patch objective or signing-identity choices that change the action. Do not repeat
questions already resolved in the current task.

## State transitions

Each stage: check parent → begin → act → inspect outputs → finish.
Started/running is not pass. Failed preserves the error; blocked identifies a
specific missing input/condition; unknown means the operation may have happened
and needs reconciliation. None of these statuses is a valid next-stage parent.

For a new patch variant, branch attempts from the appropriate parent receipt.
An older successfully signed set retains its provenance; do not label it as the
result of a new build. Do not select "current" from the newest directory name.
`caseflow check` detects changed bytes in already recorded artifacts.

Example start of a full case:

```sh
python3 scripts/caseflow.py init --case "$REBRO_CASE" \
  --package com.example.lab --android-user 0 --mode full
python3 scripts/caseflow.py begin --case "$REBRO_CASE" --stage environment
```

Then call rebro-environment, run doctor into a separate file, and finish environment
with evidence=doctor.json and output=that file. Keep the **returned** attempt/receipt
paths; do not construct UUIDs. Recheck current RAM/disk before heavy tasks even if
an older environment receipt remains intact.

Signing completed files does not require an Android user:

```sh
python3 scripts/caseflow.py init --case "$REBRO_CASE" \
  --package "$PACKAGE" --mode sign-only
```

If installation is later requested, bind the actually selected profile before install:
`python3 scripts/caseflow.py bind-user --case "$REBRO_CASE" --android-user "$ANDROID_USER"`.
Completed artifact-only receipts with user=null remain valid.

## One evidence-based status for the user

Report the last established stage, usable artifact path, checks performed and the
specific unresolved boundary. Examples:

- The full set is signed and its certificate matches the profile; installation was outside the task.
- The journal reached commit_requested and the response was lost; the installed set has not matched yet, so the outcome remains unknown.
- Installation and the target test passed; the neighboring scenario is untested, so the functional gate remains open.

Successful apksigner alone does not establish that a patch works. Lost transport
does not establish installation failure. Concrete user facts and current API
schemas take precedence over a template workflow. If a parser version is
incompatible, stop that stage with evidence and adapt it in a separate controlled change.
