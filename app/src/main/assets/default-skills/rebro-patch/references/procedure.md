# Patching: from an explained change to a reproducible tree

## Inputs

Read analysis.json: objective, package/user, exact split, observation source,
intended change and neighboring scenarios that must remain intact.
Require the unchanged decoded tree, parent analyze receipt, full APK set and
text/binary payload for the planned change. Start an attempt with the decoded tree,
plan and every payload as inputs. Change the smallest useful number of files.

Do not edit JADX-recovered Java expecting Apktool to compile it: this pipeline
works on smali/resources/manifest/native assets. Editing original Java sources
and using Gradle is a different build route.

## Exact replacement plan

Get the tree hash: `python3 scripts/tree_hash.py "$DECODED_TREE"`.
Obtain the full SHA-256 of each changed file. Prepare patch-plan.json:

```json
{
  "schema": 2,
  "purpose": "Change the diagnostic screen label in the lab APK",
  "expected_behavior": "The diagnostic screen shows the new label; other screens are unchanged",
  "input_tree_sha256": "FULL_TREE_SHA256",
  "operations": [{
    "action": "replace_text",
    "path": "res/values/strings.xml",
    "before_sha256": "FULL_FILE_SHA256",
    "old": "<string name=\"diagnostic_label\">Lab</string>",
    "new": "<string name=\"diagnostic_label\">Lab patched</string>",
    "count": 1
  }]
}
```

Supported operations are literal replace_text, replace_file, add_file and delete_file.
For file payloads, supply `payload` and `payload_sha256`; add_file requires the
preimage to be absent and before_sha256=null. Deletion requires an exact original hash.
No regex/global "replace everywhere" or shell commands inside the plan. This is a
bounded mechanism for an already selected change, not autonomous patch discovery.

```sh
python3 scripts/patch_tree.py --tree "$DECODED_TREE" \
  --plan "$PATCH_PLAN" --out-dir "$PATCH_OUTPUT"
```

The script validates the plan, copies sources into a new `tree/`, applies changes,
rechecks the input and writes patch-report. The plan applies to the original bytes
and stops on different bytes. Reusing an existing output directory is forbidden.

## Review by change type

| Type | Check before build |
|---|---|
| Smali | Correct class/method descriptor and DEX/split; registers/locals; wide-register pairs; type flow; try/catch boundaries; monitor balance; valid invoke/move-result/return |
| Manifest | Package/version and split identity preserved; exported/permission/SDK changes match the task; valid namespaced XML |
| Resources | Names/IDs retained; qualifiers and translations; cross-resource references; case-sensitive paths; no duplicate resources |
| Native `.so` | Correct ABI/build ID; file offset mapped to PT_LOAD; calling convention, PAC/BTI, ELF alignment; changed content independently checked |
| Assets | Encoding, sizes, checksums and format; unknown binary fields preserved |

Rebuild checks smali syntax but does not rule out runtime VerifyError or a wrong
branch. Before an expensive hook/patch, state the observable change, check a
negative control and record baseline behavior.

## Output and rollback

Pass means the exact preimage matched, only declared files changed, the result
hash is saved and a testable hypothesis exists. Semantic success belongs to verify.
For failure, identify stale input, ambiguous match, syntax/format issue or incomplete
hypothesis; do not increase count to force an unexpected match through.
Rollback preserves the original tree unchanged; use a new attempt for a new variant.
