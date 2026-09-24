# Frida: operating playbook

## From transport to evidence

These are diagnostic stages to select as needed, not a mandatory full startup
checklist. Kit 2.3 obtained paths/pins/flat-loader recipe from the user's prompt;
the user also reported passing native RPC smoke and Compiler build. Verify the
new Java adapter and relevant target contract rather than reopening environment repair.

1. Verify pins and endpoint `127.0.0.1:27044`.
2. List processes through that endpoint; match PID, process name, package and
   Android user. Main process and `package:remote` are different targets.
3. Load `agents/native_probe.js` on the selected test PID for 5 seconds when needed.
4. Assemble the Java probe using the **existing verified patched-bridge loader**.
5. Check one Java/native hook and trigger exactly one corresponding UI action.
6. Save JSONL, errors and detach result. Compare with an unhooked run.

Build and attach affect different parts of the system: Compiler build is local;
script creation/hooks occur in the target. Success of the former does not prove
the latter. `Frida.version` inside the native probe better reflects the loaded
agent runtime than the Python binding version, but does not replace the custom
server hash and build history.

## Frida 17 and bridges

Frida 17 moved bridges out of GumJS. Upstream frida-tools REPL/frida-trace bundle
bridges for compatibility; `session.create_script()` does not add Java itself.
For **this environment**, use the already verified patched bridge.

Two distinct routes:

- With the patched bridge's ESM source project, import the existing pinned
  dependency/path and build with the installed `frida.Compiler()`.
- With only `bridge-final.js`, this baseline uses a flat UTF-8 script exporting
  `frida_java_bridge_default`. rebro-flat places it at the beginning of the same
  Script and exposes globalThis.Java for pack modules. For another baseline,
  establish the loader/format first. Do not append text to an opaque Compiler bundle.

Globals are not shared between separate `create_script()` calls. Loading a bridge
in one script does not make Java available in another. Kit Java templates expect
Java in the **same script**, not automatic insertion.

Upstream-style ESM entry, only when the selected dependency is already pinned:

```js
import Java from 'frida-java-bridge';
Java.perform(() => {
  const Clock = Java.use('android.os.SystemClock');
  send({kind: 'java-ready', uptimeMillis: Clock.uptimeMillis().toString()});
});
```

This import does not instruct replacement of the patched bridge with upstream.
Keep `package-lock.json` and bridge source revision with the case project.
`npm ci` may be used for a separate reviewed project; `npm install latest` is not a baseline.

## Older snippets

| Older pattern | Check/use in Frida 17 |
|---|---|
| Java exists in every create_script | Explicitly include the selected bridge |
| `Module.findExportByName('libx.so', 'f')` | `Process.getModuleByName('libx.so').findExportByName('f')` |
| Search exports in all modules | `Module.findGlobalExportByName('f')` when global lookup is required |
| `Memory.readUtf8String(p)` | `p.readUtf8String(limit)` |
| `Memory.readU32(p)` | `p.readU32()` |
| Permanent module polling | Check availability of `Process.attachModuleObserver` |
| Arbitrary `--no-pause` from old guides | Read the installed `frida --help` |

Do not mechanically replace every `Memory.*`: some APIs remain on Memory.
Handle exceptions for `get*` and `null` for `find*`.

## Hook practice

Prefer observing one method before changing behavior. Specify the exact overload,
class loader and process. Preserve the original call and exceptions; do not swallow
a business exception or return an arbitrary value.

For late-loaded code, identify the loader and use `Java.ClassFactory.get(loader)`.
Do not enumerate the entire heap/all methods on each call. `Java.perform` may wait
for the app loader; a quiet five-second log is not automatically a crash. Use the
main thread for UI operations when the app API requires it.

Manage callback, wrapper and native-allocation lifetimes. String memory does not
remain alive after JS garbage collection automatically: retain a reference for as
long as native code retains the pointer. Do not overwrite an original buffer with
a longer string. These issues are separate from page permissions.

## Performance and observability

The kit native hook counts calls and sends aggregates once per second. Do not
`send()` on every hot call. Set row/byte limits, sampling and an observation window.
Collect arguments, tokens, network bodies and keys only when the experiment needs them.

Take backtraces conditionally or for the first N events, not every iteration.
Use Stalker for one selected thread and a short window after a cheaper Interceptor
check; analyze results separately. JNI tracing is for a JNI question, not a default first step.

Run `script.unload()` and `session.detach()` on error too. Do not use
`Interceptor.detachAll()` to remove one owned hook from a combined script; retain
the listener. Killing the Android client/UID does not replace checking target state
after failure.

## Capability matrix

Record separate `pass / fail / not_tested` results, target PID/package, boot ID,
agent/baseline hashes, time and evidence:

| Capability | Evidence |
|---|---|
| endpoint/list | health JSON |
| native attach/load | native-ready |
| Java bridge | java-ready |
| Java interception | java-hook-installed + java-call after the action |
| native interception | hook-installed + call-count |
| Compiler build | Bundle SHA-256 and no build exception |
| unload/detach | Runner completed and target remains functional |
| spawn/child gating | Separate lab scenario; not automated by this kit |

## Adapting community examples

For a snippet, record URL, author, revision/date, license, SHA-256, expected API,
runtime, target signature and output schema. Read the whole script before execution.
Remove unrelated hooks/external downloads. Replace broad logging with minimal events,
add a timeout/cleanup, validate on a lab target, then place it in case scripts.

Do not load the entire frida-snippets collection into the system prompt. Use a
focused card: symptom → relevant API section → adapted script → result verification.
CodeShare is an example catalog, not vetted dependencies for automatic execution.

Primary sources: [bridges](https://frida.re/docs/bridges/),
[17.0 migration](https://frida.re/news/2025/05/17/frida-17-0-0-released/),
[API](https://frida.re/docs/javascript-api/),
[best practices](https://frida.re/docs/best-practices/),
[Java bridge](https://github.com/frida/frida-java-bridge),
[Stalker](https://frida.re/docs/stalker/).
