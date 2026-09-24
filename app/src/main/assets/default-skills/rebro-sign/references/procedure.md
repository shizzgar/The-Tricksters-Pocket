# Signing: key identity and the complete APK set

## Inputs

Obtain `candidate-set.json`, verify hashes/identity and the build or external-APK
intake receipt. For sign-only, initialize that case mode, run environment, then
`apkset.py --kind candidate` for all supplied APKs and create an intake receipt.
Do not require analysis/patch when the user has supplied the completed artifact.
Enable `rebro-environment` and use its procedure; that package contains doctor.
An artifact-only case permits user=null; signing does not imply installation.
Intake checks the supplied composition; badging alone does not prove split
dependency closure. Record provenance/completeness separately.

Require a keystore, alias, two private password files and an **expected certificate**.
Never place passwords in prompts, JSON, argv as `pass:...` or tool traces. Files
use mode 600/400 with one line each. Keep the keystore and password files outside
the skill and published artifacts.

For a new lab project, choose one stable lab key; do not generate a new key for
every build. Establish signing identity explicitly. A certificate digest is not
a keystore hash or APK hash. Obtain it from the selected trusted certificate or
known-good APK before signing and save it in the case signing profile.
After a mismatch, do not replace the expected digest with the observed one.

Save a nonsecret profile in `case/input/signing-profile.json`:

```json
{
  "kind": "signing-profile",
  "alias": "lab",
  "expected_cert_sha256": "FULL_64_HEX_CERTIFICATE_SHA256"
}
```

Replace the placeholder with a trusted full digest. Include the profile, candidate
manifest and keystore in sign attempt inputs: the snapshot records a hash, not a
copy of the private key. Exclude password files from published outputs/case archives.

## Operation

```sh
python3 scripts/run_job.py --job-dir "$SIGN_JOB" --cwd "$SKILL_ROOT" \
  --heap-mib 512 --seconds 600 -- \
  python3 scripts/sign_set.py --set "$CANDIDATE_SET" \
    --keystore "$KEYSTORE" --alias "$KEY_ALIAS" \
    --ks-pass-file "$KS_PASS_FILE" --key-pass-file "$KEY_PASS_FILE" \
    --expected-cert-sha256 "$EXPECTED_CERT_SHA256" --out-dir "$SIGNED_DIR"
```

For **every** APK: align → sign; then for the entire set: cryptographic verify →
alignment check → metadata/split/certificate consistency. Publish signed-set only
after every member passes. A partial APK directory with failed progress is not a
signed set.

Toolkit policy: V2 and V3 enabled, V1 selected by the signer from the manifest,
V4 disabled; no incremental install. This is ordinary single-signer signing
without key rotation. Special requirements need a separate procedure.
Apply `zipalign -P 16` before signing; afterward, only `zipalign -c` verification
is allowed. ZIP alignment does not fix ELF PT_LOAD alignment.

## Gate

For every APK: actual apksigner verification, V2=true, exact expected certificate
digest and passing ZIP alignment. For the set: unchanged package/version/split IDs
and one certificate set. Preserve final APK hashes and complete verification logs.

Do not change ZIP comments, resources, manifest or alignment afterward: a new
change requires a new candidate and sign. In full mode, create a new build from
the relevant patch; in sign-only, new intake from environment, then sign from
that intake. Do not reuse a receipt after its input bytes change. A signed APK
does not guarantee an update over the official installation; install must compare
the installed signer.

## Failures

| Error | Action |
|---|---|
| Missing native zipalign / `-P` | Return to environment; do not simulate success |
| Wrong alias/password | Check the profile; do not generate a replacement key |
| Wrong certificate | Stop publishing the set and resolve signing identity |
| Only some APKs signed | Preserve the failed attempt; retry into a new output |
| Verification warning | Read the specific warning; exit 0 does not erase its meaning |
| Candidate changed during signing | full: new build → sign; sign-only: new intake → sign; reject the previous result |

Sources: [apksigner](https://developer.android.com/tools/apksigner),
[zipalign](https://developer.android.com/tools/zipalign).
