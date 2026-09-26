---
name: rebro-install
description: "Plan, execute and reconcile installation of a verified APK/full split set for a specified Android user. Use for authorized installation/update; resolve an unknown PM commit outcome without blind retries."
---

# Installation

The Trickster's Pocket skill, kit 2.3 with English instructions. Enable this skill separately.
Obtain its own skill_root from a successful use_skill/termux_skill_sync response
and use it as working_dir. Read the [contract](references/contract.md),
[agent rules](references/agent-contract.md), then the [procedure](references/procedure.md).
For tools and synchronization, read the [harness contract](references/harness.md).
Do not reread shared references of the same version without a reason. Never infer
another skill's path. Write outputs into the case; keep package sources unchanged.

Use the procedure as the starting point, local checks as evidence of the current
environment, and focused Local search for missing or changed APIs/practices.
Follow the primary-source links in references/sources.md and match documentation
to the installed version. Historical pins and reports are not fresh observations;
a newer webpage alone is not a reason to replace a working baseline.

## Inputs and result

- Input: Signed receipt, Android user and authorized installation scope.
- Output: Install plan, single-use journal and post-install hashes.
- Stage and predecessor are defined in the shared contract; sign also handles sign-only intake.
- Use a caseflow receipt with an explicit parent between stages.
- Check actual tool availability and the meaning of results, not just exit 0.
- Honor established user authorization. Do not add repeat confirmation for an
  already authorized action or extend its scope to deletion/other profiles.

## Execution

1. Read the needed procedure fully; verify target and input identities.
2. Verify the preceding receipt and hashes. For a narrow request, select the
   corresponding route without requiring unnecessary stages.
3. Start a new attempt for a stage; helpers use the current attempt. Create a
   separate data/output directory. Supply shell variables explicitly in every
   tool call; the shell environment may not persist between calls.
4. Execute scripts with resource limits. Use managed Termux jobs for long commands.
5. Check the procedure's gates, preserve evidence and finish the receipt.
6. Report the evidenced result and remaining verification boundary.

## Package scripts

- `scripts/apkset.py`
- `scripts/caseflow.py`
- `scripts/install_set.py`
- `scripts/run_job.py`

`python3 scripts/<name>.py --help` describes the actual CLI.
caseflow records integrity and structure; it cannot validate manually asserted
application behavior. Follow the procedure for specialist operations.

## Failure and recovery

Record failed/blocked/unknown with a specific reason. A missing response does not
establish success or absence of side effects. Do not repeat PM commit after an
interruption; reconcile first. A new file change requires a new attempt.
Do not replace pinned Frida, a signing key or expected hashes merely to pass a check.

## Further reading

- [Sources](references/sources.md)
- [Release checks and limitations](references/validation.md)
