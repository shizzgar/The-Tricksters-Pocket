# Verification: artifact, installed code and behavior

Read acceptance_tests from the original analysis or agreed sign-only task.
Before each experiment, check package/user/version, the complete installed set,
boot ID and runtime PID. PID is neither permanent identity nor always the main process.

## Three independent results

| Layer | Check | What it does not establish |
|---|---|---|
| Artifact | Signature/alignment/metadata/hashes | That the app installed |
| Installation | Code actually installed for the intended user | That the relevant branch works |
| Function | Reproduced action and observed result | Absence of every possible regression |

For a patch, the minimum is a baseline control, target change, an unchanged
neighboring scenario and no new evident failure/crash. Do not add a persistent
Frida hook to verify a static patch if that hook can itself cause the observed
effect: check without instrumentation first.

If runtime evidence is needed, call rebro-frida, verify pins and begin with a native
probe. Then use one relevant Java/native hook, a bounded interval/log budget, the
exact UI action and cleanup. Compare against an unhooked test. Clean stdout, an
Activity launch and an empty crash buffer do not establish acceptance.

## Evidence and outcome

Record expected/actual behavior, command/action without secrets, time, result
source (screen/log/Frida/return value) and original evidence paths. Include evidence
files in the current verify attempt's outputs so caseflow records their contents.
Do not copy all app data or unrelated-process logs into the report.

```json
{
  "schema": 2,
  "kind": "verification-report",
  "status": "pass",
  "package": "com.example.lab",
  "android_user": 0,
  "tests": [
    {"id": "target-change", "required": true, "result": "pass",
     "expected": "Lab patched", "observed": "Lab patched",
     "evidence": "evidence/target-screen.png"},
    {"id": "neighbor-flow", "required": true, "result": "pass",
     "expected": "Normal neighboring screen", "observed": "Normal neighboring screen",
     "evidence": "evidence/neighbor-screen.png"}
  ]
}
```

This is a structural example, **not a report of an actual test**. Fill it only with
observed results. Required skipped/not_tested is not pass. If the baseline control
is absent, state the limitation rather than inventing a comparison. For sign-only
without requested installation/functional testing, finish at the signed artifact
and state exactly what was verified.

## Diagnosis by boundary

Incorrect behavior after correct installation → analyze/patch with a new parent.
VerifyError/Resources.NotFound → patch/build. Certificate/install failure → sign/install.
Frida-only crash → check without instrumentation before changing the APK.
Save separate data for a new experiment; preserve old receipts as history.
Automatic rollback/deletion is not part of verify.
