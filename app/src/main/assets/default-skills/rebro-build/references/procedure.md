# APK rebuild: separate tooling defects from patch defects

## Before modification

For the first case with this app/version, make a **control build without a patch**
in a separate directory. Compare decode → rebuild → sign → lab install against
original behavior. This need not repeat for every attempt with identical inputs
and toolchain. If the control build fails, diagnose resources/framework, signing
or packaging first; a patched-build failure does not yet test the patch hypothesis.
If installation/execution is outside the task, limit the control to rebuild and
inspection, and mark functional compatibility untested.

Build inputs: a passed patch report, unchanged patched tree, source-set, specific
Apktool/AAPT2/JDK and vendor framework inputs. Verify the parent receipt.
Do not reuse an arbitrary `dist/*.apk` from an earlier run.

## Working copy

Apktool creates working files. Copy the patched tree into a new scratch directory
for the **current attempt**. Preserve `patch-report.output_tree` unchanged: caseflow
checks it at finish and the next skill checks it again. Keep framework cache local
to the case/attempt. Record vendor framework APK hash and device fingerprint; do
not take a framework from an unrelated ROM.

```sh
python3 scripts/run_job.py --job-dir "$BUILD_JOB" --cwd "$REBRO_CASE" \
  --heap-mib 1536 --seconds 1200 -- \
  apktool b "$BUILD_WORK_TREE" --aapt "$PREFIX/bin/aapt2" -j 2 \
  -p "$BUILD_FRAMEWORK" -o "$REBUILT_APK"
```

Use native Termux AAPT2 on the device. Check flags against `apktool b --help` for
the installed dirty build. Do not add `--copy-original` to conceal manifest problems.
An independent `.so` build must finish with its own report before APK packaging.

## Complete set

```sh
python3 scripts/assemble_set.py --source-set "$SOURCE_SET" \
  --replace "base=$REBUILT_APK" --out-dir "$CANDIDATE_DIR"
```

For a feature split, replace `base` with its actual split ID from source-set.
Every other split is copied from the verified original set. Assembly rechecks
package/version/split identity through aapt2 and requires the original topology.
APK count alone does not prove completeness: retain the original set manifest.
There is no arbitrary merging of splits into one APK.

`candidate-set.json` does not mean all files are unsigned: unchanged splits may
still have their original signatures. The next sign stage signs the whole set.
Intentional versionCode/package/split-topology changes need a separate migration
plan; the script must not automatically accept an unexpected change.

## Gate

Build exit 0 + a new existing APK + parseable metadata + a complete coherent
candidate-set + unchanged patched-tree hash. Include manifest, APK directory,
stdout/stderr, toolchain versions and changed-component checks in the receipt.
Byte-for-byte APK equality is not a universal no-op build criterion: packaging
can change. Check DEX/resources/manifest/native libraries and behavior separately.

For resource ID/framework errors, diagnose preparation; for smali parse errors,
return to patch; for OOM/LMKD, reduce threads/work rather than blindly increasing
heap. Retry with a new build attempt and the same explicit parent, or a new patch parent.

CLI source: [Apktool](https://apktool.org/docs/cli-parameters/).
