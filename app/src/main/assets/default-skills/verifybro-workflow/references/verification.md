# Verification contract

## Acceptance matrix

| Criterion | Exact object/revision | Evidence and origin | Result | Remaining gap |
| --- | --- | --- | --- | --- |
| User-visible behavior | commit/artifact/path | observation or report actually inspected | CONFIRMED / ERRORS FOUND / NOT VERIFIED | next bounded check |

No evidence is NOT VERIFIED, not an error or a pass. Separate producer-supplied evidence from independently observed evidence. A supplied hash identifies bytes but does not establish authenticity; compare with a trusted expected source. File timestamps do not prove a build came from a revision.

## Software and Android artifacts

Inspect requested behavior, changed areas, failure/cancellation paths, persistence compatibility and permissions. Match CI commit with the artifact's source revision. For an APK report package, version name/code, certificate digest, debuggable flag and relevant ABI when corresponding reliable output is available. State whether installation and real-device behavior were checked. Do not infer certificate identity from a file name or successful ZIP listing.

## Research and documents

Compare conclusions with cited source text actually available; check dates and whether a citation supports the nearby claim. Inspect requirements, structure, missing content, arithmetic and links. Layout requires rendered evidence; a source document parsed successfully is not a visual review. Live freshness requires current source access; mark it unverified when unavailable.

## Reproducibility and findings

Keep each finding small enough to act on: location, conditions, expected/actual result, impact and evidence. Avoid presenting speculative issues as observed defects. A passing targeted test proves its case, not complete correctness. Record tests not run and why. Reject claims whose evidence belongs to another version or environment.

## Manifest helper input

JSON: `{"files":[{"path":"dist/result.apk","sha256":"64 lowercase hex digits","size":123}]}`. Paths must be relative to the explicitly selected root; directories, symlinks and traversal outside the root are rejected. `size` is optional. Empty manifests and duplicate entries are errors. The helper prints JSON and exits 0 only if every entry matches. It does not verify signatures, provenance, safety or functionality; those remain separate criteria. The manifest itself must come from a trusted expected source to establish integrity.
