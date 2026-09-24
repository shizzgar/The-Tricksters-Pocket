# Acquisition: the source set anchors the whole pipeline

Inputs: package, Android user, case ID and a fresh environment receipt. Determine
whether the source is an installed package, a local monolithic APK or a complete
split set. AAB/APKS requires a separate deliberate extraction for the device
configuration; an arbitrary ZIP collection is not a complete installed set.

```sh
python3 scripts/acquire_apks.py com.example.lab --android-user 0 --out-dir "$ACQUIRE_DIR"
```

The script performs scoped root reads, creates copies owned by Termux, verifies
source size/hash and checks the path list again. `acquisition.json` must be complete.
Do not choose base by ordinal position; preserve the entire list. Reads are not an
atomic Package Manager transaction: exclude external app updates during acquisition
and repeat in a new directory if drift is detected.

Pass all acquired APKs to inspection under the JVM lock:

```sh
python3 scripts/run_job.py --job-dir "$INSPECT_JOB" --cwd "$SKILL_ROOT" \
  --heap-mib 512 --seconds 600 -- \
  python3 scripts/apkset.py --kind source --out "$SOURCE_SET" \
    --acquisition "$ACQUIRE_DIR/acquisition.json" \
    --logs "$INSPECT_LOGS" "$BASE_APK" "$SPLIT_APK"
```

The final arguments are the exact acquisition list; this example does not require
two APKs. Inspection reads actual package/version/split metadata and signatures.
It requires exactly one base, unique split IDs and one signing identity. Preserve
sources separately from work/output; do not re-sign them in place.
Finish evidence=source-set, outputs=copies, manifest, acquisition and inspection logs.
`--acquisition` also requires every recorded copy and hash to match; an omitted split
blocks the source manifest. This is a snapshot of the installed composition, not
all on-demand features that may exist in the original AAB.

A local external unsigned APK supplied only for signing belongs to sign-only intake,
not source: missing signatures are not successful original-signature verification.
Acquisition does not copy app data; database/Keystore backup is a separate specific task.
