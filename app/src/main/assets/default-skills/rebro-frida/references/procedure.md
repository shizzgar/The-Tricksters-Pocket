# Frida Pack in the modular Rebro kit

## Contents and inputs

`assets/rebro-frida-pack/` contains all 85 files from the supplied pack 1.0.0:
38 modules, 16 profiles, controller/runtime, examples, documentation, tests and MIT
license. This English adaptation changes documentation and catalog descriptions;
vendor runtime code remains unchanged. The adapter verifies the updated `SHA256SUMS`
at startup. Original source archive hashes remain in the app's provenance metadata.
This is a component within rebro-frida, used by analyze/verify when needed.

In The Trickster's Pocket, run through `scripts/frida_pack.py`. It uses external config/evidence,
preserves skill_root and holds the shared Frida lock. Direct `rebro.py configure/run`
examples in the original README apply to a standalone copy: their `local.json`/`runs`
defaults are unsuitable for a synchronized skill.

Obtain this skill's own skill_root through use_skill/sync. Read the contract and
baseline; verify task scope, process/package/user. Get PID from the current process
list. External profiles/options accept absolute paths; example placeholders are
not actual target class names or addresses.

## First use

The supplied prompt already established this environment's full baseline and loader
recipe. Use `config/pins.rebro-known-good-20260922.json`; broad bridge discovery is
unnecessary. Read system-prompt-integration.md. The collector remains available
for specific observations in a different unknown environment.

Create external config from an existing trusted kit-format manifest:

```sh
python3 scripts/frida_pack.py --config "$FRIDA_CONFIG" configure --pins "$BASELINE_PINS"
python3 scripts/frida_pack.py --config "$FRIDA_CONFIG" doctor --online
python3 scripts/frida_pack.py --config "$FRIDA_CONFIG" ps
```

For first use of the supplied baseline from the current skill_root, use this form
(the named config must not already exist):

```sh
python3 -B scripts/frida_pack.py \
  --config "$HOME/rebro/config/frida-pack.2.3.json" configure \
  --pins config/pins.rebro-known-good-20260922.json --root-read
```

The manifest declares rebro-flat; configure selects it automatically and verifies
files/the loaded binding. It does not attach, start a service or install packages.
Config references the immutable manifest in this skill version: retain that Termux
copy while in use, or first copy the manifest into a separate private config directory
and supply its explicit --pins path. After an update, do not guess the old skill_root
or overwrite config automatically.

`FRIDA_CONFIG` must be a new file outside skill_root. For a root-only server binary,
add `--root-read` at configure; it permits only scoped su sha256sum. Manifest,
service file, bridge and actually loaded Python extension must match the already
selected pins. Repeated configure never overwrites the file.

Supported plain-bridge modes: auto/global/expression from upstream docs/BRIDGE.md,
and rebro-flat for the supplied baseline's frida_java_bridge_default export.
rebro-flat preserves bridge source at the beginning of the combined Script, then
publishes globalThis.Java for pack modules. It never changes the bridge file.
A missing export gives an explicit JS error, not another bridge. The controller
uses QJS. For Compiler bundles/ESM/private loaders, read their actual contract first.
A separate config with `configure --native-only` permits native profiles while Java
stays on its existing loader; it does not mark Java verified. Do not install a new
bridge merely to pass the check.

## Smoke and profiles

Full smoke is unnecessary before every case. The user already reported native
spawn/attach/load/RPC/cleanup and Compiler build success; the new Java adapter needs
a separate check on the current lab target. CLI smoke runs both phases; for a
focused Java check, use run with the selected Java module/profile.

Use managed Termux jobs from skill_root for long commands. The controller has its
own duration/startup bounds and best-effort cleanup; the outer deadline must cover
both smoke phases and detach, for example 90 seconds for short smoke.
The controller runs as Termux UID; the existing root service performs injection.
CLI PID input does not replace external `(boot_id, PID, start_ticks)` checks:
verify current identity/profile and record it before attach. Examples use explicitly
selected LAB_PID/TARGET_PID, never a historical report's PID.

The java-smoke profile checks java_probe only, without hooks. To also verify a
lifecycle hit in the selected lab app:

```sh
python3 -B scripts/frida_pack.py --config "$FRIDA_CONFIG" run \
  --pid "$LAB_PID" --agents java_probe,android_lifecycle \
  --duration 15 --startup-timeout 10 --output "$JAVA_EVIDENCE"
```

Supply current identity and a new evidence path. After hook readiness, perform the
authorized Activity transition and require an activity event from that session.
No hit without a trigger does not prove a bridge defect. Preserve script errors,
statuses, event and cleanup summary; ready alone does not pass the gate.

```sh
python3 scripts/frida_pack.py --config "$FRIDA_CONFIG" smoke \
  --pid "$LAB_PID" --duration 5 --output "$SMOKE_EVIDENCE"
python3 scripts/frida_pack.py --config "$FRIDA_CONFIG" run \
  --pid "$TARGET_PID" --profile java-survey --duration 10 --output "$SURVEY_EVIDENCE"
```

One profile per session. The nested session directory has a unique ID; keep its
printed absolute path. The shared lock coordinates only this adapter, not the
direct upstream controller or other launchers.

| Task | Profile / module |
|---|---|
| Check transport/runtime | doctor, native-smoke, java-smoke |
| Select module/loader/class | native-survey, java-survey, exports |
| Observe late code | modules, dynamic-code, jni |
| UI and IPC | app-observe, binder |
| Files/databases/preferences | storage, native-io |
| Network endpoints | network, okhttp |
| Crypto algorithm/mode/size | crypto-metadata |
| Threads | threads |
| Exact method/symbol | java_trace / native_trace + separate options JSON |
| Small memory region | native_memory / native_scan with an explicit range |
| Short single-thread tracing | native_stalker_calls, experimental, separate lab target |

Metadata capture has specific boundaries: key names, paths and URL paths may appear
in evidence. Native memory/scan are separately selected operations. Profiles do not
automatically capture keys or alter business-logic results.

## Building an agent and using an existing loader

A native bundle can be prepared entirely offline:

```sh
python3 scripts/frida_pack.py build-native --profile native-survey --out "$BUNDLE_JS"
```

For Java bundles, use `--config "$FRIDA_CONFIG" build ...`; the bridge enters the
same Script context. Focused hook example:

```sh
python3 scripts/frida_pack.py --config "$FRIDA_CONFIG" run \
  --pid "$TARGET_PID" --agents native_trace --options "$TRACE_OPTIONS" \
  --duration 10 --output "$TRACE_EVIDENCE"
```

Pack modules can use an existing loader through native build/an explicitly prepared
Java adapter. Do not concatenate opaque Frida Compiler bundles with plain JavaScript.
The kit's original minimal probes and Compiler wrapper remain available; read
`references/frida.md` for them.

## Accepting the result

Read session.json, summary.json and events.jsonl from the session directory, then:

```sh
python3 -B assets/rebro-frida-pack/tools/summarize.py "$SESSION_DIR"
```

`active` means initialization. Require hook_installed/java_hooks and an actual
target hit. Inspect reason, ready, cleanup_errors, unavailable, quota/truncation
and agent_counters.dropped. Exit 0 after quota/interrupt does not establish full
coverage. Also check the relevant scenario without hooks.

Include the session directory and interpretation in the current analyze/verify
attempt's outputs. Do not mutate logs after finish. Frida smoke does not establish
a static APK patch's acceptance; verify needs actual target/regression tests.

After timeout/interruption, reconcile the managed job, process and saved session
report first. Do not automatically restart the service/app. Cleanup after SIGKILL
is not guaranteed.
