# Primary-source map

Originally checked on 22 September 2026. This is a selected reading route and
original adaptation, not a mirror of third-party manuals. External scripts were
not included; four templates were authored for the kit. Upstream pages/branches
can change: pin an actual commit and license before borrowing code. Use Local
search to fill a concrete gap or check changed guidance, matching the installed
version; keep the skill procedure and local evidence in the decision.

| Source | Read for | Application |
|---|---|---|
| [Frida Android](https://frida.re/docs/android/) | Android instrumentation model | Adapt USB examples to the existing -H 127.0.0.1:27044 endpoint |
| [Bridges](https://frida.re/docs/bridges/) | CLI versus create_script and Compiler | Read before diagnosing a missing Java global |
| [Frida 17 migration](https://frida.re/news/2025/05/17/frida-17-0-0-released/) | Breaking API changes | Use when adapting older community snippets |
| [JavaScript API](https://frida.re/docs/javascript-api/) | Exact API names/signatures | Read the relevant section and compare with a capability probe |
| [Best practices](https://frida.re/docs/best-practices/) | Native buffer lifetime and callback cost | Read before native replacement or hot hooks |
| [Messages](https://frida.re/docs/messages/) | send/recv and error handling | Design structured JSONL events |
| [Stalker](https://frida.re/docs/stalker/) | Tracing and its cost | Select a thread/window first |
| [Troubleshooting](https://frida.re/docs/troubleshooting/) | Basic error classification | Transfer the method, not commands for another platform |
| [frida-python](https://github.com/frida/frida-python) | Bindings and client examples | Verify attach/load/detach/Compiler in the actual client |
| [frida-java-bridge](https://github.com/frida/frida-java-bridge) | Android Java interop implementation | Compare with the patched bridge without replacing it |
| [frida-snippets](https://github.com/iddoeldor/frida-snippets) | Community patterns | Select one example, review, migrate its API, test within bounds |
| [Frida CodeShare](https://codeshare.frida.re/) | Focused examples | Preserve source/fingerprint; do not execute remote code blindly |
| [OWASP MASTG](https://mas.owasp.org/MASTG/) | Mobile analysis methodology | Turn a technique into a hypothesis and verification criterion |
| [MASTG Deep Link runtime](https://mas.owasp.org/MASTG/techniques/android/MASTG-TECH-0173/) | Focused observation example | Use as a task-card structure, not a universal hook |
| [JADX](https://github.com/skylot/jadx) | CLI, decompilation errors and modes | Check flags against installed --help |
| [Apktool CLI](https://apktool.org/docs/cli-parameters/) | Decode/build/framework/aapt | Use external native AAPT2 and case-local framework cache |
| [apksigner](https://developer.android.com/tools/apksigner) | Signing schemes, certificate verification, password files | Read before signing or diagnosing update mismatch |
| [zipalign](https://developer.android.com/tools/zipalign) | Operation order and -P 16 | Align before signing; check after signing |
| [Android page sizes](https://developer.android.com/guide/practices/page-sizes) | ZIP versus ELF alignment | Check actual pages and PT_LOAD |
| [Termux apksigner recipe](https://github.com/termux/termux-packages/blob/master/packages/apksigner/build.sh) | Signer provenance and JDK dependency | First source before using an external JAR |
| [Termux aapt recipe](https://github.com/termux/termux-packages/blob/master/packages/aapt/build.sh) | Native build tools | Compare with the local package manifest |
| [Termux android-build-tools](https://github.com/termux/android-build-tools) | aapt/aapt2/aidl/zipalign | Native tooling source, not desktop SDK binaries |
| [Termux Package Management](https://github.com/termux/termux-packages/wiki/Package-Management) | Updates and dependencies | Plan maintenance of a pinned environment |
| [LLVM readelf](https://llvm.org/docs/CommandGuide/llvm-readelf.html) | ELF metadata | Inspect specific headers/sections/symbols |
| [Official r2 Book](https://book.rada.re/) | Native analysis workflow | Use focused commands, not blind full analysis |
| [SQLite backup](https://www.sqlite.org/backup.html) | Consistent live backups | Do not copy only an open main database file |

## Reviewed fork material

PR: [shizzgar/The-Tricksters-Pocket#1](https://github.com/shizzgar/The-Tricksters-Pocket/pull/1).
Reviewed head: `964714533885709526fd8072f44ccb1a496a0baa`; it was open at that review.

- [Skill packages and Trajectory](https://github.com/shizzgar/The-Tricksters-Pocket/blob/964714533885709526fd8072f44ccb1a496a0baa/docs/agent-runtime/trajectory-and-termux-skills.ru.md)
- [Autonomous tasks](https://github.com/shizzgar/The-Tricksters-Pocket/blob/964714533885709526fd8072f44ccb1a496a0baa/docs/agent-runtime/autonomous-tasks-and-trajectory.ru.md)
- [SkillsTools](https://github.com/shizzgar/The-Tricksters-Pocket/blob/964714533885709526fd8072f44ccb1a496a0baa/app/src/main/java/me/rerere/rikkahub/data/ai/tools/SkillsTools.kt)
- [ZIP importer](https://github.com/shizzgar/The-Tricksters-Pocket/blob/964714533885709526fd8072f44ccb1a496a0baa/app/src/main/java/me/rerere/rikkahub/skills/SkillZipImporter.kt)

The PR description reported its CI results; that historical source review did not
rerun CI. Reading source does not establish physical-phone integration.

## Minimal provenance card for a borrowed snippet

```json
{
  "name": "observe-selected-method",
  "source_url": "https://...",
  "upstream_commit": null,
  "license": null,
  "source_sha256": null,
  "adapted_sha256": null,
  "bridge": "existing-patched",
  "requirements": ["exact class", "exact overload", "selected process"],
  "evidence_kind": "java-call",
  "duration_seconds": 15,
  "tested_on_device": false
}
```

Fill nulls with actual reviewed values. A hash establishes file identity, not the
safety or compatibility of its behavior.
