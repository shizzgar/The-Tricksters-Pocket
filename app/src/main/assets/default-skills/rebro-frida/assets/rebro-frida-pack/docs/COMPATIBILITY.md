# Compatibility and limits

| Layer | Assumption | Check |
|---|---|---|
| Device | Android16/API36, arm64 | native_probe + java_probe |
| Python | Interpreter loading the patched binding | doctor: python/frida paths and hash |
| Transport | localhost:27044, existing root service | doctor --online, ps |
| Java | Pinned plain-script bridge supports ART | configure + java-smoke |
| Native observer | API actually available in agent runtime | Capability guards and events |
| App | Class/symbol exists and relevant path executes | Inventory, hook_installed/java_hooks, hits |
| Build | Original JS + stdlib controller | SHA256SUMS, offline tests |

Client/server version differences are not repaired automatically. Frida.version in
native_probe reflects the observed GumJS runtime and may differ from the binding's
version string. Connection alone does not establish all-API compatibility.

**App coverage.** Java survey lists loaded classes: max_items limits output, not
the cost of enumerateLoadedClassesSync. Attach misses events before hooks exist.
Activity base hooks miss overrides that do not call super. ContextWrapper does not
cover every custom Context. SharedPreferences excludes DataStore. SQLite hooks do
not cover arbitrary native/Room/SQLCipher routes. URL.openConnection does not cover
OkHttp/Cronet. OkHttp hooks may miss shaded/obfuscated/custom stacks. Native network
observes selected libc calls, not HTTP content/TLS plaintext or every syscall.
Crypto metadata excludes keys and independent native crypto. Binder reports codes/
flags without Parcel decoding. JNI observes future RegisterNatives calls; no future
calls can legitimately produce empty output.

**Errors and partial coverage.** agent_status=active means init completed. A tracer
can be active with zero hooks while its ELF is not loaded. Unavailable optional Java
methods leave other hooks working. hook_error / observer_error / jni_read_error /
cleanup_error produce a nonzero outcome. Inspect events, not only exit status.

**Limits.** The controller enforces JSONL bounds; runtime also bounds serialization
and rate. max_per_second drops events; dropped counts return at normal stop.
session.json / summary.json and total runs size are outside the JSONL limit. Java
inventory and some symbol enumeration first obtain the full list. native_scan checks
at most 1 MiB per run, not a bulk dump. Memory ranges must remain readable throughout
read/scan operations.

**Cleanup.** Normal exit/Ctrl-C calls stop, unload and detach; an owned spawn with
a known PID is resumed even after failure where possible. SIGKILL, interpreter
crash, transport loss or a stuck custom server prevent guaranteed cleanup.
Cancellable/timeouts are not atomic rollback of remote spawn/attach. Begin with
attach to an already running sample app. Cleanup errors appear in summary.

**Instrumentation effects.** Even observation changes timing and may trigger app
responses. Profiles contain no automatic bypasses, result replacement, key capture,
root hiding or system-policy changes. Stalker remains experimental: one thread,
selected function, 100–5000 ms window. Use it after an ordinary native hook on a
separate test process.

**Local environment.** The controller does not read root-only /proc, require su
or manage adbd. The existing server performs injection. JVM/JADX/Apktool are not
required. This is dynamic analysis, not a complete APK signing toolchain.
