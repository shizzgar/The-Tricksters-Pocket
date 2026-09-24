# Frida toolkit research for Samsung S24 Ultra / ReBro

Research date: **22 September 2026**. This English adaptation preserves the supplied
research, repository inventory and revisions. It is historical reference material,
not a claim that these versions/statuses were checked again today. The original
research used the user's live report without direct phone access. Read the current
skill procedure first and use focused Local search to verify changing claims before
acting on them. Later kit 2.3 evidence resolved bridge paths/pins and confirmed that
apksigner/zipalign were already installed; do not repeat the earlier setup suggestions.

Build around the working ReBro service, Python binding, Compiler and patched Java
bridge. The useful outputs of this research are small agents, libraries and methods
that can use the existing controller. The original review checked metadata for
**59 public repositories**, README files for 22, key source/dependencies, releases
and related issues. It was a broad thematic survey, not an exhaustive GitHub audit.
Nothing was executed on the phone. Stars were not evidence of quality; candidates,
code donors, experiments, archives and workstation tools have distinct roles.

## 1. Baseline

| Component | Supplied baseline |
|---|---|
| Device | SM-S928B / e3q, Android 16 / API 36 |
| ABI | arm64-v8a only |
| Root | Working KernelSU, SELinux Enforcing |
| Service | rebro-frida --serve, core 17.18.0, build.17180-sepol3-20260922-004847 |
| Endpoint | **127.0.0.1:27044** |
| Python binding | 17.2.14-4+rebro.compiler1; public API reports 17.2.14 |
| Java bridge | Verified bridge-final.js with pinned hash |
| Compiler | Local frida.Compiler(); build/watch reported working |
| Resources | About 3.1 GB MemAvailable and 27 GB free at the original observation |
| Control | Termux + scoped su; no external ADB transport |

Different core/binding version numbers are a known property of the working custom
build. General upstream version-alignment advice does not justify replacing it.
Handshake/process listing do not establish every newer client API; test each extension.
SM-S928B/e3q is S24 Ultra; the earlier Exynos 2400 description needed correction.
Use actual arm64-v8a evidence for binary selection. Samsung lists Snapdragon 8 Gen 3
for Galaxy for S24 Ultra. Eight observed cores were consistent with that correction.
[Samsung source](https://news.samsung.com/global/enter-the-new-era-of-mobile-ai-with-samsung-galaxy-s24-series).

AC power does not eliminate heat/background-process constraints. Load average is
not CPU utilization percentage. MemAvailable is a snapshot, not guaranteed JVM budget.
Start one JADX process around 1–1.5 GB heap with few threads, then tune from measured
RSS/memory pressure; use the current kit's resource policy for actual execution.

## 2. Candidate components

| Priority | Component | Benefit | Integration route |
|---|---|---|---|
| 1 | apksigner and native zipalign | rebuild → align → sign → verify | Termux packages/sources; inspect install plan; later inventory already resolved availability |
| 1 | Owned agent registry | Reproducibility, versions, limits, uniform output | Existing Python/Compiler and endpoint 27044 |
| 1 | 0xdea Android trace/enum | Focused Java discovery/tracing | Selected JS after review |
| 1 | Medusa modules | HTTP, intents, WebView, storage, JNI, crypto | Small modules with adapted prolog/bridge |
| 1 | HTTP Toolkit scripts | Separate proxy, CA trust and pinning controls | Selected files and explicit config |
| 2 | friTap | TLS key logs and/or plaintext PCAP | Remote backend; isolated dependency evaluation |
| 2 | r2frida | Runtime access from existing radare2 | Android arm64 build and its own Frida-core checks |
| 2 | Objection | Interactive Java/runtime exploration | TCP with independent bundled-agent validation |
| 2 | JNI/native mini-agents | RegisterNatives, dlopen, exports, backtraces | Small agents using supported APIs |
| 3 | IL2CPP/Flutter/Gadget | App-specific support | Only for a relevant case |

### Frida CLI and scaffolding

[frida-tools](https://github.com/frida/frida-tools) supplies ps, trace, compile,
create, apk, itrace and other commands; reviewed main also contained frida-strace.
Main-branch availability does not establish availability/compatibility in the
installed patched package. The reviewed [setup.py](https://github.com/frida/frida-tools/blob/main/setup.py)
required `frida >= 17.10.0, < 18.0.0`, while the local binding reported 17.2.14.
An ordinary pip resolver could replace the binding or fail. Select a compatible
pinned release or deliberately backport a needed feature after checking its API.

The reviewed [Termux recipe](https://github.com/termux/termux-packages/blob/master/root-packages/frida/build.sh)
used 17.2.14 revision 4 with automatic updating disabled. This explained the version
gap with [upstream 17.18.0](https://github.com/frida/frida/releases/tag/17.18.0),
not the private ReBro patches. For new projects, reuse
[frida-agent-example](https://github.com/oleavr/frida-agent-example) or
`frida-create -t agent`. Replace macOS libSystem.B.dylib examples with Android logic.

### Script libraries

[0xdea/frida-scripts](https://github.com/0xdea/frida-scripts) offers focused starting
points: [Android enum](https://github.com/0xdea/frida-scripts/blob/master/raptor_frida_android_enum.js)
and [Android trace](https://github.com/0xdea/frida-scripts/blob/master/raptor_frida_android_trace.js).
Review android-snippets separately. Its README reported core-script testing with
17.3.2 and snippets with versions through 17.0.0; this is not certification for
Android 16 or the patched bridge.

[iddoeldor/frida-snippets](https://github.com/iddoeldor/frida-snippets) contains
Hook overloads, Hook reflection, Reveal native methods, Binder transactions,
Log SQLite query, Print shared preferences updates, Webview URLS, Socket activity,
Stalker and Load C module examples. Last recorded push: 2024-11-29. Adapt obsolete
APIs where needed; never load its whole README as one agent.

[Medusa](https://github.com/Ch0pin/medusa) reported over 90 modules. Select from
[http_communications](https://github.com/Ch0pin/medusa/tree/master/modules/http_communications),
[webviews](https://github.com/Ch0pin/medusa/tree/master/modules/webviews),
[sockets](https://github.com/Ch0pin/medusa/tree/master/modules/sockets),
[intents](https://github.com/Ch0pin/medusa/tree/master/modules/intents),
[content_providers](https://github.com/Ch0pin/medusa/tree/master/modules/content_providers),
[db_queries](https://github.com/Ch0pin/medusa/tree/master/modules/db_queries),
[file_system](https://github.com/Ch0pin/medusa/tree/master/modules/file_system),
[JNICalls](https://github.com/Ch0pin/medusa/tree/master/modules/JNICalls),
[code_loading](https://github.com/Ch0pin/medusa/tree/master/modules/code_loading), and
[encryption](https://github.com/Ch0pin/medusa/tree/master/modules/encryption).
The shell calls adb; [libraries/natives.py](https://github.com/Ch0pin/medusa/blob/master/libraries/natives.py)
extracts a bridge from frida-tools and exposes global Java. That is not automatically
the existing bridge-final.js. In a phone-only setup, adapt selected modules for the
current loader first; a full shell needs ADB-route and bridge integration work.

### Network and TLS

[HTTP Toolkit scripts](https://github.com/httptoolkit/frida-interception-and-unpinning)
separate proxy, CA trust and pinning. Relevant files: config.js,
android/android-proxy-override.js, android/android-system-certificate-injection.js,
android/android-certificate-unpinning.js and its -fallback variant. Add
native-connect-hook.js/native-tls-hook.js only when native coverage is required.
config.js must be first. Select only the missing layer; BLOCK_HTTP3 changes UDP/443
behavior by default. Java trust-manager success does not establish Cronet/Flutter/
custom TLS coverage. Use the [demo APK](https://github.com/httptoolkit/android-ssl-pinning-demo)
for a control run.

[friTap](https://github.com/fkie-cad/friTap) adds key logs, plaintext capture and
TLS-library workflows. Its library matrix distinguishes key extraction from plaintext
read/write. The reviewed source supports `-H/--host`. Command shape after version
and installation verification, **not an executed command**:

~~~bash
fritap -H 127.0.0.1:27044 -k keys.log com.example.lab
~~~

Patterns/offsets support some symbol-less libraries.
[BoringSecretHunter](https://github.com/monkeywave/BoringSecretHunter) derives certain
patterns through Ghidra, often best offloaded. friTap dependencies include frida-tools,
AndroidFridaManager, psutil and native Python packages. Python 3.14 + Android/Bionic
needs build availability checks. Do not let automatic server management replace
ReBro. Key-log success is not packet-capture success; auxiliary routes may expect ADB/root.

[PCAPdroid](https://github.com/emanuele-f/PCAPdroid) provides Android network visibility/
PCAP; packet capture does not decrypt TLS. [mitmproxy](https://github.com/mitmproxy/mitmproxy)
provides proxy/API automation; check native dependencies for phone-only use.

### Java, JNI and native analysis

[Objection](https://github.com/sensepost/objection) supports network host/port in the
reviewed CLI. Verify installed help before using this command shape:

~~~bash
objection -N -h 127.0.0.1 -P 27044 -n com.example.lab start
~~~

Older CLI used explore; the reviewed source retained it as a compatibility entry
and preferred start. Objection loads objection/agent.js with its own Java-bridge
dependency. [Issue #800](https://github.com/sensepost/objection/issues/800) described
an ART-update failure not repaired by server update alone; its author reported
resolution in [1.12.5](https://github.com/sensepost/objection/releases/tag/1.12.5).
That is an example of bundled-bridge coupling, not a claim that current Objection
is broken. Validate it independently with this baseline.

[jnitrace](https://github.com/chame1eon/jnitrace) and
[jnitrace-engine](https://github.com/chame1eon/jnitrace-engine) illustrate JNI tracing
and support remote connections. Recorded pushes were in 2023; README used old
Memory.readCString. Treat them as adaptation/rebuild candidates, not universal
first installations. [frida_hook_libart](https://github.com/lasting-yang/frida_hook_libart)
provides RegisterNatives/ART examples; check Android 16 symbols/layout rather than
transferring fixed offsets.

[r2frida](https://github.com/nowsecure/r2frida) offers maps, exports, memory search
and agents within installed radare2. It has its own Frida core, not the Python
binding automatically. Verify Android arm64 and radare2 6.2.0 build compatibility
before connecting to 27044. Desktop r2pm instructions do not establish phone readiness.

Use Stalker/frida-itrace on one thread/function in a short window: counters/call
summaries before detailed trace. PAC/BTI does not by itself imply incompatibility.
[17.18.0 notes](https://frida.re/news/2026/09/09/frida-17-18-0-released/) describe ARM64/BTI
fixes. Frida-strace uses a distinct eBPF path; ordinary attach does not prove Samsung
kernel support. An [S23 Ultra/Android 16 report](https://github.com/frida/frida/issues/3723)
cannot automatically be applied to this S24 Ultra.

### Specialized cases and interfaces

| Task | Project | Selection condition |
|---|---|---|
| Unity IL2CPP | [frida-il2cpp-bridge](https://github.com/vfsfitvnm/frida-il2cpp-bridge) | Pin bridge and Unity versions for an IL2CPP app |
| Flutter AOT | [blutter](https://github.com/worawit/blutter) | Android arm64 libapp.so and generated blutter_frida.js; Dart build can be heavy |
| Flutter engine patching | [reFlutter](https://github.com/Impact-I/reFlutter) | Separate APK/engine workflow; reviewed example pinned Frida 16.7.19 |
| Selected DEX/SO dump | [frida_dump](https://github.com/lasting-yang/frida_dump) | Check API 17, size budgets and Android 16 |
| Gadget in APK | [frida-gadget](https://github.com/ksg97031/frida-gadget) | Explicit --arch and --custom-gadget-path; re-signing changes app identity |
| Gadget through Zygisk | [ZygiskFrida](https://github.com/lico-n/ZygiskFrida) | Actual Zygisk required; KernelSU alone is insufficient |

Gadget is an option for a separate lab APK/loading phase, not a required replacement
for the working root server. Concurrent use needs a separate port; 27044 is occupied.
The app UID must be able to read its configuration/JS.

[RMS](https://github.com/m0bilesecurity/RMS-Runtime-Mobile-Security) has a web UI,
its own Node binding/bridge and a README route using SystemUI; it is a secondary
integration for this baseline. [Brida](https://github.com/federicodotta/Brida) links
Frida to Burp and has low priority without a workstation/Burp.
[CENSUS hook generator](https://github.com/CENSUS/ghidra-frida-hook-gen) can generate
native hooks from Ghidra; verify loading time, addresses and signatures.
[NSA ghidra-frida](https://github.com/NationalSecurityAgency/ghidra-frida) provides
TraceRMI integration, but the minimal README did not establish turnkey maturity.
[ghidra2frida](https://github.com/federicodotta/ghidra2frida) is another older candidate.

## 3. Organizing reusable agents

Use small agents with explicit inputs/output. The following layout is a proposal,
not a claim these paths already exist on the phone:

| Path | Purpose |
|---|---|
| baseline/manifest.json | Full service/binding/bridge hashes, firmware, endpoint |
| vendor/OWNER/REPO/ | Only needed source files with commit and license |
| agents/java/ | Java discovery/hooks |
| agents/jni/ | Registration and bounded JNI events |
| agents/native/ | Modules, exports, backtraces, selected tracing |
| agents/network/ | Request metadata and selected TLS hooks |
| agents/storage/ | SQLite/SharedPreferences/file events |
| profiles/ | Hook selection, class/package filters and limits |
| cases/CASE/session.json | APK/.so versions, PID, timestamps, agent hashes and config |
| cases/CASE/events.jsonl | Structured events |
| cases/CASE/artifacts/ | Bounded dumps/captures |

Initial agent candidates: env-probe (arch/pointers/pages/modules), java-enum,
java-overload-trace, classloader-watch, native-module-watch, jni-register-map,
native-call-trace, intents-observe, webview-observe, storage-observe,
network-observe and a separate tls-profile. These are proposed names, not upstream files.
Use Frida API, 0xdea, Medusa, iddoeldor, JNI examples, HTTP Toolkit and friTap as
reviewed sources of methods.

Shared agent contract:

- Explicit package/PID and class/module filters; max events/bytes/duration/backtrace depth.
- JSONL timestamp, pid, tid, agent, kind, payload; send large data as binary payloads rather than giant hex strings.
- Necessary metadata by default; argument/content capture explicitly selected.
- Attach-only first; spawn/child gating as a separate step.
- Owned listeners/timers/RPC handlers unload without restarting the service.
- Module name plus ELF build ID/hash; offsets tied to the specific build.

Frida 17 [bridges](https://frida.re/docs/bridges/) are external to GumJS; REPL/trace
supply them but arbitrary create_script does not. Bridge and its consumer need
the same Script context. The early report did not establish bridge-final.js format/
export/loader; kit 2.3 later resolved the flat export. Never import a new bridge over
it automatically. Check this contract before Medusa/Objection/RMS integration.

A venv isolates ordinary system-package writes but can still install another frida
that shadows the patched binding. Check interpreter, frida.__file__, reported version,
actual _frida.abi3.so hash, included bridge hash, resolver plan and APIs newer than
17.2.14. `--no-deps` requires deliberate dependency reconciliation; it does not make
an incompatible package compatible. Do not default to `pip install -U frida frida-tools`.

## 4. APK build/sign tools

The reviewed [apksigner recipe](https://github.com/termux/termux-packages/blob/master/packages/apksigner/build.sh)
used Build Tools 37.0.0, openjdk-21 and apksigner.jar. The user already had JDK 21.
[android-build-tools](https://github.com/termux/android-build-tools) supports zipalign;
[aapt](https://github.com/termux/termux-packages/blob/master/packages/aapt/build.sh)
used tag 16.0.0.4, whose [CMake](https://github.com/termux/android-build-tools/blob/16.0.0.4/vendor/CMakeLists.txt)
installs zipalign. AAPT2's separate subpackage does not prove zipalign availability.
Check local mirror/package state; later inventory already found both tools installed.

~~~bash
apt-cache policy apksigner aapt
apt-get -s install apksigner aapt ripgrep jq
dpkg -L aapt
command -v zipalign
~~~

Missing aapt makes dpkg -L fail as expected. Inspect the simulated dependency plan
before installing; do not unexpectedly replace pinned Frida/Python.
Android recommends -P 16 for ZIP alignment with uncompressed .so; it does not repair
ELF LOAD-segment alignment. [zipalign](https://developer.android.com/tools/zipalign),
[apksigner](https://developer.android.com/tools/apksigner).

Example for a lab build and existing keystore:

~~~bash
zipalign -P 16 -v 4 rebuilt.apk aligned.apk
apksigner sign --ks lab.jks --v2-signing-enabled true --out signed.apk aligned.apk
apksigner verify --verbose --print-certs signed.apk
zipalign -c -P 16 -v 4 signed.apk
~~~

Align before sign; zipalign -c does not mutate. Use separate working copies.
An owned key does not preserve the developer's identity; splits need coherent set
handling. jarsigner provides JAR/v1 signing, not apksigner v2.
[uber-apk-signer](https://github.com/patrickfav/uber-apk-signer) is a fallback whose
native executables require Android/arm64 checks. Desktop Linux x86_64 zipalign is
not an Android arm64 binary.

## 5. API migration and hook practice

| Old pattern | Current API to verify against the installed version |
|---|---|
| Module.findBaseAddress(name) | Process.findModuleByName(name), then .base after a null check |
| Module.getExportByName(name, symbol) | Process.getModuleByName(name).getExportByName(symbol) |
| Module.findExportByName(null, symbol) | Module.findGlobalExportByName(symbol) |
| Memory.readUtf8String(pointer) | pointer.readUtf8String() |
| Process.enumerateModulesSync() | Process.enumerateModules() |

Sources: [Frida 17 migration](https://frida.re/news/2025/05/17/frida-17-0-0-released/),
[JavaScript API](https://frida.re/docs/javascript-api/).
Use Java.perform with exact overloads/ClassLoader. Prefer module observers to a fixed
setTimeout for late .so loading. Check pointer type/readability and bound reads.
Filter dumps; backtracing every hot call is expensive. Consider CModule only after
measuring JS callback cost. Scope Stalker to a thread/window and necessary event kinds.
Separate observation from behavior changes for comparable controls. Start with a
lab app, not system processes. Preserve Enforcing/scoped root; no general setenforce 0.
Keep full hashes, not chat abbreviations. See
[best practices](https://frida.re/docs/best-practices/) and
[Stalker internals](https://frida.re/docs/stalker/).

## 6. Validating a new component

Proposed integration gate; not executed by the original research:

1. Inspect proposed files and dependencies.
2. Connect to **127.0.0.1:27044** and list processes with the current binding.
3. Attach to a separate lab app, load a minimal native agent and cleanly unload.
4. Repeat with the baseline bridge and simple Java.perform/Java.use.
5. Enable one new module; measure errors, latency, RSS and log size.
6. Check detach/re-attach and reproducibility.
7. Record commit/hash and limitations in the profile.

Set explicit host/port. -R ordinarily selects 27042; -U requires the corresponding
USB/ADB device. Standard client command shape:

~~~bash
frida-ps -H 127.0.0.1:27044
~~~

This does not promise compatibility of an unknown CLI; use the working Python/ReBro
controller first. Bound/rotate storage: 100 MB or 60 seconds per run was a proposed
user budget, not a tool limit. Full memory dumps should not be default with the
historically observed 27 GB free.

## 7. Avoid automatic baseline replacements

Archived hluwa/frida-dexdump (recorded push 2023-03-04) and google/ssl_logger
(2020-10-20) are references, not default new tooling; evaluate friTap for new TLS work.
Random Termux wheels require matching Python ABI, Bionic, build options and patches.
Older universal SSL/root scripts do not cover every native TLS stack, loader or ART.
strongR/server forks do not establish a need to replace working ReBro or guarantee
undetectability. Global npm/pip upgrades can shadow the baseline. Running MobSF,
Ghidra and Burp together on this phone has low priority under its resource budget.
MagiskFrida/ZygiskFrida use different launch/injection models, not automatic replacements.
Root detection, anti-Frida, pinning and server integrity are distinct mechanisms;
one successful intervention does not establish all of them. Use separate lab apps
and compare against original behavior.

## 8. Documentation and labs

- [Frida API](https://frida.re/docs/javascript-api/), [bridges](https://frida.re/docs/bridges/), [best practices](https://frida.re/docs/best-practices/), [Stalker](https://frida.re/docs/stalker/), [Gadget](https://frida.re/docs/gadget/).
- [OWASP MASTG](https://mas.owasp.org/MASTG/) and [repository](https://github.com/OWASP/mastg): methods, techniques and apps.
- [Frida-Labs](https://github.com/DERE-ad2001/Frida-Labs): Android exercises.
- [MASTG Hacking Playground](https://github.com/OWASP/MASTG-Hacking-Playground): educational APKs; account for age.
- [HTTP Toolkit demo](https://github.com/httptoolkit/android-ssl-pinning-demo): reproducible TLS control.
- [DetectFrida](https://github.com/darvincisec/DetectFrida): older instrumentation-detection example, not a map of all modern defenses.
- [awesome-frida](https://github.com/dweinstein/awesome-frida): navigation with mixed-age links.
- [CodeShare](https://codeshare.frida.re/): additional examples; published scripts were not audited by this survey.

## 9. Integration order

Record the existing full baseline, verify signing/alignment and small search/JSON
utilities, establish an agent registry with JSONL output, adapt 0xdea enum/trace
and 5–10 relevant Medusa/iddoeldor snippets, then validate a separate HTTP Toolkit
TLS profile on its demo. Add friTap only after dependency/remote-backend validation.
Evaluate r2frida and Objection independently; add Flutter, IL2CPP, Gadget and detailed
Stalker only for a case that needs them. The intended next outcome was a baseline-aware
loader, manifests, 10–15 verified agents and working APK signing, not installation
of all 59 projects. The bundled Frida Pack subsequently implemented its own modules;
external candidates below remain separate projects.

## 10. Repository inventory at the research date

Push dates reflect the historical GitHub API response, not correctness, active
maintenance or a fresh release. README/code/docs means those materials were read;
metadata means existence, description and repository state were checked. License
IDs below are API metadata, not a legal determination: read LICENSE before redistributing
borrowed code, especially for NOASSERTION/unknown results.

| # | Repository | Role / decision | Last push | Archived | Review |
|---|---|---|---|---|---|
| 1 | [frida/frida](https://github.com/frida/frida) | **Foundation.** Upstream releases/source for comparison; preserve the working service. | 2026-09-22 | No | Metadata |
| 2 | [frida/frida-tools](https://github.com/frida/frida-tools) | **Foundation.** ps/trace/create/compile/apk/itrace; use compatible CLI. Reviewed main requires frida>=17.10.0. | 2026-09-22 | No | README/code/docs |
| 3 | [frida/frida-python](https://github.com/frida/frida-python) | **Foundation.** Local-controller API; preserve the installed patched binding. | 2026-09-22 | No | README/code/docs |
| 4 | [frida/frida-java-bridge](https://github.com/frida/frida-java-bridge) | **Foundation.** API/ART fixes; baseline bridge-final.js takes precedence over automatic updates. | 2026-06-22 | No | README/code/docs |
| 5 | [frida/frida-compile](https://github.com/frida/frida-compile) | **Foundation.** Modular-agent builds; account for the working frida.Compiler version. | 2026-03-27 | No | Metadata |
| 6 | [frida/frida-gum](https://github.com/frida/frida-gum) | **Advanced reference.** Interceptor, Stalker, architecture and CModule for native instrumentation. | 2026-09-22 | No | Metadata |
| 7 | [frida/frida-itrace](https://github.com/frida/frida-itrace) | **Task-specific.** Instruction tracing after compatible client-API checks; potentially expensive. | 2026-04-29 | No | Metadata |
| 8 | [oleavr/frida-agent-example](https://github.com/oleavr/frida-agent-example) | **Reuse.** TypeScript/watch project structure; adapt demo code to Android. | 2026-02-28 | No | README/code/docs |
| 9 | [termux/termux-app](https://github.com/termux/termux-app) | **Installed.** Terminal; account for package-source signatures and background-process constraints. | 2026-09-16 | No | README/code/docs |
| 10 | [termux/termux-packages](https://github.com/termux/termux-packages) | **Foundation.** Official frida/apksigner/aapt recipes and Android ARM64 packages. | 2026-09-22 | No | README/code/docs |
| 11 | [termux/android-build-tools](https://github.com/termux/android-build-tools) | **Priority.** aapt/aapt2/zipalign sources; preferred native zipalign route. | 2026-07-16 | No | README/code/docs |
| 12 | [termux/proot-distro](https://github.com/termux/proot-distro) | **Fallback.** glibc userland for selected utilities; unnecessary for the working Frida. | 2026-09-22 | No | README/code/docs |
| 13 | [topjohnwu/Magisk](https://github.com/topjohnwu/Magisk) | **Reference.** Samsung/root documentation; existing KernelSU needs no migration. | 2026-09-22 | No | Metadata |
| 14 | [ViRb3/magisk-frida](https://github.com/ViRb3/magisk-frida) | **Fallback.** Upstream-server autostart; README supports KernelSU. Do not replace ReBro. | 2026-09-09 | No | README/code/docs |
| 15 | [Ch0pin/medusa](https://github.com/Ch0pin/medusa) | **Priority: modules.** 90+ modules; select by task. Shell requires ADB and a frida-tools bridge. | 2026-09-06 | No | README/code/docs |
| 16 | [Ch0pin/stheno](https://github.com/Ch0pin/stheno) | **Task-specific.** Android intent-monitoring UI with Medusa; separate integration. | 2024-07-29 | No | Metadata |
| 17 | [sensepost/objection](https://github.com/sensepost/objection) | **Candidate.** Runtime exploration with TCP; independently verify its bundled bridge/agent. | 2026-09-17 | No | README/code/docs |
| 18 | [0xdea/frida-scripts](https://github.com/0xdea/frida-scripts) | **Priority.** Android trace/enum; core scripts report 17.3.2 tests, older snippets need review. | 2026-08-02 | No | README/code/docs |
| 19 | [iddoeldor/frida-snippets](https://github.com/iddoeldor/frida-snippets) | **Reuse.** Java/JNI/native/Binder/SQLite recipes; selectively migrate to API 17. | 2024-11-29 | No | README/code/docs |
| 20 | [httptoolkit/frida-interception-and-unpinning](https://github.com/httptoolkit/frida-interception-and-unpinning) | **Priority.** Modular Android/TLS/proxy scripts; config.js first, minimal selection. | 2026-09-18 | No | README/code/docs |
| 21 | [fkie-cad/friTap](https://github.com/fkie-cad/friTap) | **Candidate.** TLS keys/plaintext PCAP and remote endpoint; isolate dependency/backend checks. | 2026-09-19 | No | README/code/docs |
| 22 | [federicodotta/Brida](https://github.com/federicodotta/Brida) | **Workstation.** Burp integration; useful with Burp, secondary for phone-only work. | 2025-10-30 | No | Metadata |
| 23 | [emanuele-f/PCAPdroid](https://github.com/emanuele-f/PCAPdroid) | **Task-specific.** Android network visibility/PCAP; TLS decryption is separate and bounded. | 2026-09-20 | No | Metadata |
| 24 | [mitmproxy/mitmproxy](https://github.com/mitmproxy/mitmproxy) | **Task-specific.** HTTP(S) proxy/automation; verify phone dependencies and memory. | 2026-09-10 | No | Metadata |
| 25 | [chame1eon/jnitrace](https://github.com/chame1eon/jnitrace) | **Adaptation needed.** JNI tracer with remote support; older code, no established Frida 17 compatibility. | 2023-07-18 | No | README/code/docs |
| 26 | [chame1eon/jnitrace-engine](https://github.com/chame1eon/jnitrace-engine) | **Reuse.** JNI interception engine as source/reference after API migration and build. | 2023-07-18 | No | Metadata |
| 27 | [nowsecure/r2frida](https://github.com/nowsecure/r2frida) | **Candidate.** radare2+Frida; complements installed r2 but has its own core/build requirements. | 2026-09-21 | No | README/code/docs |
| 28 | [lasting-yang/frida_hook_libart](https://github.com/lasting-yang/frida_hook_libart) | **Reuse.** JNI/RegisterNatives/ART hooks; verify Android 16 symbols/layout. | 2025-10-22 | No | Metadata |
| 29 | [lasting-yang/frida_dump](https://github.com/lasting-yang/frida_dump) | **Task-specific.** Selected DEX/SO dumps; verify API 17, ART and size bounds. | 2025-08-20 | No | Metadata |
| 30 | [hluwa/frida-dexdump](https://github.com/hluwa/frida-dexdump) | **Archived.** Popular DEX dumper; not a ready default Android 16 tool. | 2023-03-04 | Yes | Metadata |
| 31 | [vfsfitvnm/frida-il2cpp-bridge](https://github.com/vfsfitvnm/frida-il2cpp-bridge) | **Task-specific.** Unity IL2CPP classes, methods and tracing for relevant apps. | 2026-09-06 | No | README/code/docs |
| 32 | [worawit/blutter](https://github.com/worawit/blutter) | **Task-specific.** Flutter Android arm64 libapp.so and generated Frida templates; heavy Dart builds. | 2026-08-18 | No | README/code/docs |
| 33 | [Impact-I/reFlutter](https://github.com/Impact-I/reFlutter) | **Task-specific.** Flutter engine patching; reviewed Frida example pins 16.7.19, use a separate profile. | 2026-08-11 | No | README/code/docs |
| 34 | [monkeywave/BoringSecretHunter](https://github.com/monkeywave/BoringSecretHunter) | **Workstation.** Ghidra analysis of stripped BoringSSL for friTap patterns; specialized. | 2026-06-11 | No | Metadata |
| 35 | [m0bilesecurity/RMS-Runtime-Mobile-Security](https://github.com/m0bilesecurity/RMS-Runtime-Mobile-Security) | **Secondary.** Web UI with its own Node binding/bridge; README uses SystemUI. Adaptation needed. | 2026-09-03 | No | README/code/docs |
| 36 | [NationalSecurityAgency/ghidra](https://github.com/NationalSecurityAgency/ghidra) | **Workstation.** Native static analysis; heavy JVM load for the supplied phone. | 2026-09-21 | No | Metadata |
| 37 | [CENSUS/ghidra-frida-hook-gen](https://github.com/CENSUS/ghidra-frida-hook-gen) | **Workstation.** Generate hooks from Ghidra; verify module loading and signatures. | 2026-09-03 | No | README/code/docs |
| 38 | [federicodotta/ghidra2frida](https://github.com/federicodotta/ghidra2frida) | **Secondary.** Older Ghidra/Frida bridge; needs a separate compatibility experiment. | 2024-01-04 | No | Metadata |
| 39 | [NationalSecurityAgency/ghidra-frida](https://github.com/NationalSecurityAgency/ghidra-frida) | **Watch.** Ghidra TraceRMI support; minimal README did not establish readiness for this stack. | 2026-01-15 | No | README/code/docs |
| 40 | [skylot/jadx](https://github.com/skylot/jadx) | **Installed.** Primary Java/DEX decompiler; reuse installed 1.5.5. | 2026-09-12 | No | Metadata |
| 41 | [iBotPeaches/Apktool](https://github.com/iBotPeaches/Apktool) | **Installed.** Resources/smali/rebuild; installed 3.0.3 was working. | 2026-09-21 | No | Metadata |
| 42 | [patrickfav/uber-apk-signer](https://github.com/patrickfav/uber-apk-signer) | **Fallback.** Signing wrapper; bundled native zipalign must match Android/arm64. | 2023-10-30 | No | Metadata |
| 43 | [MobSF/Mobile-Security-Framework-MobSF](https://github.com/MobSF/Mobile-Security-Framework-MobSF) | **Workstation.** Large static/dynamic framework; outside the initial phone-only kit. | 2026-09-22 | No | Metadata |
| 44 | [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) | **Workstation.** Screen control when a PC/ADB transport becomes available. | 2026-09-19 | No | Metadata |
| 45 | [ksg97031/frida-gadget](https://github.com/ksg97031/frida-gadget) | **Task-specific.** APK patcher; --arch arm64 and --custom-gadget-path reduce ADB/autodownload coupling. | 2026-08-16 | No | Metadata |
| 46 | [lico-n/ZygiskFrida](https://github.com/lico-n/ZygiskFrida) | **Experimental.** Gadget injection through Zygisk; KernelSU alone does not establish Zygisk. | 2025-10-18 | No | README/code/docs |
| 47 | [OWASP/mastg](https://github.com/OWASP/mastg) | **Documentation.** Mobile testing/RE methodology; canonical repository at review. | 2026-09-20 | No | Metadata |
| 48 | [OWASP/mastg-hacking-playground](https://github.com/OWASP/MASTG-Hacking-Playground) | **Lab.** Educational apps; account for Android project age. | 2022-10-31 | No | Metadata |
| 49 | [DERE-ad2001/Frida-Labs](https://github.com/DERE-ad2001/Frida-Labs) | **Lab.** Android exercises for validating owned tooling. | 2026-02-22 | No | Metadata |
| 50 | [httptoolkit/android-ssl-pinning-demo](https://github.com/httptoolkit/android-ssl-pinning-demo) | **Lab.** Control Android app for reproducible TLS-hook validation. | 2026-07-31 | No | Metadata |
| 51 | [dweinstein/awesome-frida](https://github.com/dweinstein/awesome-frida) | **Navigation.** Broad index including old and iOS-only projects. | 2026-04-10 | No | Metadata |
| 52 | [r0ysue/AndroidFridaBeginnersBook](https://github.com/r0ysue/AndroidFridaBeginnersBook) | **Educational archive.** Book/examples; manually adapt to Frida 17. | 2022-08-06 | No | Metadata |
| 53 | [darvincisec/DetectFrida](https://github.com/darvincisec/DetectFrida) | **Lab.** Instrumentation-detection example, not comprehensive modern defense coverage. | 2021-06-12 | No | Metadata |
| 54 | [google/ssl_logger](https://github.com/google/ssl_logger) | **Archived.** Evaluate friTap first for a new TLS workflow. | 2020-10-20 | Yes | Metadata |
| 55 | [Nightbringer21/fridump](https://github.com/Nightbringer21/fridump) | **Older tool.** Generic memory dumper; check API 17 and output volume. | 2024-08-07 | No | Metadata |
| 56 | [CrackerCat/strongR-frida-android](https://github.com/CrackerCat/strongR-frida-android) | **Not a baseline replacement.** Patched detection-evasion server; no reason to replace ReBro or promise invisibility. | 2025-04-14 | No | Metadata |
| 57 | [hzzheyang/strongR-frida-android](https://github.com/hzzheyang/strongR-frida-android) | **Not a baseline replacement.** Newer patched-server fork; changes/builds need independent review. | 2026-09-09 | No | Metadata |
| 58 | [rendiix/termux-zipalign](https://github.com/rendiix/termux-zipalign) | **Not a baseline replacement.** Old prebuilts/third-party repository; prefer official Termux sources. | 2021-09-06 | No | README/code/docs |
| 59 | [b-erdem/rekit](https://github.com/b-erdem/rekit) | **Watch.** Small new API/traffic/HAR toolkit; maturity/coverage need a separate experiment. | 2026-05-01 | No | Metadata |

## 11. Revisions for reproducible comparison

These are default-branch HEADs captured by the original research, not automatically
recommended installation releases. README/source was read in that research session;
repositories can update independently.

| Repository | Recorded commit |
|---|---|
| frida/frida-tools | [99eaf0f0d38124ec4848a737ce2f0bfbcf5c0a31](https://github.com/frida/frida-tools/commit/99eaf0f0d38124ec4848a737ce2f0bfbcf5c0a31) |
| Ch0pin/medusa | [f5835f590fd9cc6ca792bc9cd757a6825e4cde54](https://github.com/Ch0pin/medusa/commit/f5835f590fd9cc6ca792bc9cd757a6825e4cde54) |
| sensepost/objection | [35c4e2c9e68a0354b21db4833252e0c4acd5bd28](https://github.com/sensepost/objection/commit/35c4e2c9e68a0354b21db4833252e0c4acd5bd28) |
| fkie-cad/friTap | [31a7e07eb673914264ee274d826e8b4f7f40db2c](https://github.com/fkie-cad/friTap/commit/31a7e07eb673914264ee274d826e8b4f7f40db2c) |
| termux/termux-packages | [3454667d1a1cd005c59d2abbf2ab3e8002b8a568](https://github.com/termux/termux-packages/commit/3454667d1a1cd005c59d2abbf2ab3e8002b8a568) |
| 0xdea/frida-scripts | [8b8058a118f655cfd2f539ae9e8f8b05b0e72a2f](https://github.com/0xdea/frida-scripts/commit/8b8058a118f655cfd2f539ae9e8f8b05b0e72a2f) |
| httptoolkit/frida-interception-and-unpinning | [b3ea8f63a14b9a6f1b60d9d1535dea7e6b23f028](https://github.com/httptoolkit/frida-interception-and-unpinning/commit/b3ea8f63a14b9a6f1b60d9d1535dea7e6b23f028) |

## 12. License navigation index

Historical GitHub API identifiers; inspect the actual license for any reuse.

- [frida/frida](https://github.com/frida/frida): NOASSERTION.
- [frida/frida-tools](https://github.com/frida/frida-tools): NOASSERTION.
- [frida/frida-python](https://github.com/frida/frida-python): NOASSERTION.
- [frida/frida-java-bridge](https://github.com/frida/frida-java-bridge): Not identified by the API.
- [frida/frida-compile](https://github.com/frida/frida-compile): NOASSERTION.
- [frida/frida-gum](https://github.com/frida/frida-gum): NOASSERTION.
- [frida/frida-itrace](https://github.com/frida/frida-itrace): MIT.
- [oleavr/frida-agent-example](https://github.com/oleavr/frida-agent-example): Not identified by the API.
- [termux/termux-app](https://github.com/termux/termux-app): NOASSERTION.
- [termux/termux-packages](https://github.com/termux/termux-packages): NOASSERTION.
- [termux/android-build-tools](https://github.com/termux/android-build-tools): Apache-2.0.
- [termux/proot-distro](https://github.com/termux/proot-distro): GPL-3.0.
- [topjohnwu/Magisk](https://github.com/topjohnwu/Magisk): GPL-3.0.
- [ViRb3/magisk-frida](https://github.com/ViRb3/magisk-frida): Not identified by the API.
- [Ch0pin/medusa](https://github.com/Ch0pin/medusa): GPL-3.0.
- [Ch0pin/stheno](https://github.com/Ch0pin/stheno): GPL-3.0.
- [sensepost/objection](https://github.com/sensepost/objection): GPL-3.0.
- [0xdea/frida-scripts](https://github.com/0xdea/frida-scripts): MIT.
- [iddoeldor/frida-snippets](https://github.com/iddoeldor/frida-snippets): Not identified by the API.
- [httptoolkit/frida-interception-and-unpinning](https://github.com/httptoolkit/frida-interception-and-unpinning): AGPL-3.0.
- [fkie-cad/friTap](https://github.com/fkie-cad/friTap): GPL-3.0.
- [federicodotta/Brida](https://github.com/federicodotta/Brida): MIT.
- [emanuele-f/PCAPdroid](https://github.com/emanuele-f/PCAPdroid): GPL-3.0.
- [mitmproxy/mitmproxy](https://github.com/mitmproxy/mitmproxy): MIT.
- [chame1eon/jnitrace](https://github.com/chame1eon/jnitrace): MIT.
- [chame1eon/jnitrace-engine](https://github.com/chame1eon/jnitrace-engine): MIT.
- [nowsecure/r2frida](https://github.com/nowsecure/r2frida): MIT.
- [lasting-yang/frida_hook_libart](https://github.com/lasting-yang/frida_hook_libart): MIT.
- [lasting-yang/frida_dump](https://github.com/lasting-yang/frida_dump): Not identified by the API.
- [hluwa/frida-dexdump](https://github.com/hluwa/frida-dexdump): GPL-3.0.
- [vfsfitvnm/frida-il2cpp-bridge](https://github.com/vfsfitvnm/frida-il2cpp-bridge): MIT.
- [worawit/blutter](https://github.com/worawit/blutter): MIT.
- [Impact-I/reFlutter](https://github.com/Impact-I/reFlutter): GPL-3.0.
- [monkeywave/BoringSecretHunter](https://github.com/monkeywave/BoringSecretHunter): MIT.
- [m0bilesecurity/RMS-Runtime-Mobile-Security](https://github.com/m0bilesecurity/RMS-Runtime-Mobile-Security): GPL-3.0.
- [NationalSecurityAgency/ghidra](https://github.com/NationalSecurityAgency/ghidra): Apache-2.0.
- [CENSUS/ghidra-frida-hook-gen](https://github.com/CENSUS/ghidra-frida-hook-gen): BSD-2-Clause.
- [federicodotta/ghidra2frida](https://github.com/federicodotta/ghidra2frida): MIT.
- [NationalSecurityAgency/ghidra-frida](https://github.com/NationalSecurityAgency/ghidra-frida): Not identified by the API.
- [skylot/jadx](https://github.com/skylot/jadx): Apache-2.0.
- [iBotPeaches/Apktool](https://github.com/iBotPeaches/Apktool): Apache-2.0.
- [patrickfav/uber-apk-signer](https://github.com/patrickfav/uber-apk-signer): Apache-2.0.
- [MobSF/Mobile-Security-Framework-MobSF](https://github.com/MobSF/Mobile-Security-Framework-MobSF): GPL-3.0.
- [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy): Apache-2.0.
- [ksg97031/frida-gadget](https://github.com/ksg97031/frida-gadget): MIT.
- [lico-n/ZygiskFrida](https://github.com/lico-n/ZygiskFrida): MIT.
- [OWASP/mastg](https://github.com/OWASP/mastg): CC-BY-SA-4.0.
- [OWASP/mastg-hacking-playground](https://github.com/OWASP/MASTG-Hacking-Playground): GPL-3.0.
- [DERE-ad2001/Frida-Labs](https://github.com/DERE-ad2001/Frida-Labs): MIT.
- [httptoolkit/android-ssl-pinning-demo](https://github.com/httptoolkit/android-ssl-pinning-demo): Apache-2.0.
- [dweinstein/awesome-frida](https://github.com/dweinstein/awesome-frida): CC0-1.0.
- [r0ysue/AndroidFridaBeginnersBook](https://github.com/r0ysue/AndroidFridaBeginnersBook): Not identified by the API.
- [darvincisec/DetectFrida](https://github.com/darvincisec/DetectFrida): MIT.
- [google/ssl_logger](https://github.com/google/ssl_logger): Apache-2.0.
- [Nightbringer21/fridump](https://github.com/Nightbringer21/fridump): Not identified by the API.
- [CrackerCat/strongR-frida-android](https://github.com/CrackerCat/strongR-frida-android): Not identified by the API.
- [hzzheyang/strongR-frida-android](https://github.com/hzzheyang/strongR-frida-android): Not identified by the API.
- [rendiix/termux-zipalign](https://github.com/rendiix/termux-zipalign): Apache-2.0.
- [b-erdem/rekit](https://github.com/b-erdem/rekit): MIT.
