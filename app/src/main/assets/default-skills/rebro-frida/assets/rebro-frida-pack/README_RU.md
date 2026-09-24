# Rebro Frida Pack 1.0.0

Portable tools for an existing working Frida in Termux: **38 agents, 16 profiles,
a Python controller with no external dependency except the already installed frida**.
Original build date: 2026-09-22. Instructions are now English; historical filenames
ending in _RU are retained for existing links.

Supplied baseline: Android 16 / API 36 / arm64; KernelSU; SELinux Enforcing; endpoint
`127.0.0.1:27044`; custom core 17.18.0, binding 17.2.14-4+rebro.compiler1, external
patched `bridge-final.js`. These versions came from the supplied report. The code
does not install, update or restart Frida.

**Original validation:** syntax, builds and controller/agent contracts using mocks.
**Validation on the user's phone was not performed by the pack authors.** A working
server does not establish every hook in every app. `native_stalker_calls` is experimental.

## Termux quick start

For a standalone copy, unpack into its own directory under Termux home. The baseline
Python + frida suffice; Node is needed only for offline tests. Run the following
from the package root without `su`. In a synchronized RikkaHub skill, use the outer
`scripts/frida_pack.py` adapter and external config/output as described by that
skill's procedure; standalone local.json/runs defaults would modify the skill package.

```sh
python tools/verify.py
python tools/find_bridge.py "$HOME/rebro"
```

Select **the bridge actually used by the working baseline**, not the first result.
Replace the explicit placeholder below with its real absolute path:

```sh
python rebro.py configure --bridge /absolute/path/to/bridge-final.js
python rebro.py doctor --online
python rebro.py ps
```

`configure` records full hashes of the loaded Python binding and selected bridge
in `local.json`. This records current files, not independent provenance. To also
record the server file, use `--server-binary /absolute/path/to/rebro-frida`; that does
not check a live PID's executable. A later hash mismatch blocks execution.

Select a running test app's PID from `ps`. **12345 is an example**, not a live PID:

```sh
python rebro.py smoke --pid 12345
python rebro.py run --pid 12345 --profile native-survey --duration 10
python rebro.py run --pid 12345 --profile java-survey --duration 10
```

`smoke` creates two short sessions sequentially: native, then Java. Without a configured
bridge, only the native phase runs, with an explicit message. Java smoke reads runtime
information; it does not establish compatibility of every Java hook.

Each run creates `runs/<time>-<id>/session.json`, `events.jsonl`, `summary.json`.
The controller prints the absolute path. Summarize with:

```sh
python tools/summarize.py runs/ACTUAL_SESSION_DIRECTORY
```

Exit `0`: selected agents initialized and no controller-detected errors; `2`:
configuration/load/observer/cleanup error. Quota or Ctrl-C may still exit 0: always
inspect `reason`, `agent_counters.dropped` and coverage events. `active` means
initialized, not that the relevant method was called.

## Profiles

| Profile | Purpose |
|---|---|
| native-smoke / java-smoke | Minimal environment check |
| native-survey | ABI, modules, threads |
| java-survey | Runtime, classes, ClassLoader |
| modules | ELF loading and dlopen |
| exports | Filtered libc exports |
| native-io | open/openat and connect/sendto |
| app-observe | Activity, Intent, WebView, assets |
| storage | SharedPreferences, SQLite, Java files |
| network | Native destinations + java.net.URL |
| okhttp | Separate optional OkHttp hook |
| crypto-metadata | Cipher/Digest/Mac algorithms, modes, sizes |
| dynamic-code | DEX loaders, loadClass, ELF |
| threads | Java/native thread creation |
| jni | RegisterNatives and modules |
| binder | BinderProxy.transact codes/flags |

Use one profile per run, not all 38 modules at once. Java profiles need the bridge
and a suitable Java process. OkHttp may be absent/renamed. Observer APIs are checked
at runtime.

## Focused tracing

```sh
python rebro.py run --pid 12345 --agents native_trace --options examples/native-trace.options.json --duration 15
python rebro.py build --profile native-survey --out "$TMPDIR/rebro-native-survey.js"
```

The first example observes `libc.so!openat`. For Java, replace class/method in a copy
of `examples/java-trace.options.json`, then use `--agents java_trace --options <file>`.
`build` produces standalone JS for an existing loader; the live controller provides
session deadlines/reporting. `build` never overwrites an existing file. In Termux,
use `$TMPDIR` or a home path instead of `/tmp`.

Defaults: 30 seconds, 64 hooks, 2000 events, 2 MB JSONL per session, 100 events/second,
256-character strings, 100-element collections. Total runs-directory size is not
automatically bounded. Metadata may contain paths, addresses, URLs without queries
and preference-key names. `capture_strings=false` governs generic Java tracer/SQL
values, not every text field.

## Executor guidance

Read [AGENTS.md](AGENTS.md), then [docs/HANDOFF_PROMPT_RU.md](docs/HANDOFF_PROMPT_RU.md).
The pack does not require npm/pip install, APK rebuild, ADB transport or SELinux changes.

- [docs/CATALOG.md](docs/CATALOG.md): 38 modules and options.
- [docs/API17_CHEATSHEET.md](docs/API17_CHEATSHEET.md): APIs and patterns.
- [docs/BRIDGE.md](docs/BRIDGE.md): supported bridge forms and diagnosis.
- [docs/RECIPES.md](docs/RECIPES.md): task recipes.
- [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md): coverage/environment limits.
- [docs/TESTING.md](docs/TESTING.md): exact validation scope.
- [docs/GITHUB_RESEARCH_RU.md](docs/GITHUB_RESEARCH_RU.md): historical research on 59 repositories; these external projects are not installed pack components.
- `catalog.json`, `options.json`, `profiles/*.json`: machine-readable references.
- `SHA256SUMS`: distribution integrity, not a digital signature; regenerated for the English adaptation.
