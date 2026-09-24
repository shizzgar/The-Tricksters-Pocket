# Collecting missing environment evidence

**Paths/pins for the supplied phone were resolved in kit 2.3:** the user provided
a prompt with three full hashes and the flat-bridge recipe. Use the supplied
pins.rebro-known-good-20260922.json and rebro-frida procedure. The commands below
are for unknown environments or a specific evidence gap, not mandatory startup.

Standalone `rebro_collect.py` needs only Python 3. Run it as ordinary Termux using
the Python that loads the working Frida binding:

```sh
python3 rebro_collect.py
```

From the imported rebro-environment package, use `python3 scripts/rebro_collect.py`.
Root mode auto checks KernelSU `su`, then `sudo -n`; commands remain bounded reads.
Do not run the Python controller as root: that changes HOME/PATH and the Python
baseline. Set `--root su` explicitly if needed.

Defaults include loopback Frida handshake and process count, without attach/spawn.
The overall probe budget is 240 seconds, with per-command output bounds. Tools are
checked sequentially; JVM version commands get 256 MiB heap. Search uses HOME/rebro,
depth 12, at most 25000 entries, breadth-first traversal and 1024 entries per directory.
Large decompilation sources/node_modules are skipped. Bridge/baseline/loader/tool
limits are independent, so many build helpers cannot displace bridges. Incompleteness
is recorded in limit_reasons. This is not a filesystem dump of all /data.

After a full inventory, use **focused follow-up**:

```sh
python3 rebro_collect.py --focus frida
```

This skips hardware/tool/package inventory and increases search to 100000 entries,
retaining the 240-second overall budget. It keeps root reads, loaded binding,
loopback handshake and bridge/loader/pin candidates. No dependency installation,
service restart or attach occurs. --root su is supported, but auto already selects
available KernelSU. If search is incomplete, specify several concrete --search-root
paths; a file not found within limits is not proven absent from the phone.

| Information | Purpose |
|---|---|
| Actual model/SoC/ABI/page size/kernel | Verify device profile and native alignment |
| RAM/disk/CPU policies/thermal/cgroup | Configure resource budgets and job resilience |
| Tool paths/versions/packages/local apt candidates | Resolve zipalign/apksigner and wrapper identity |
| Binding path/full hash + server parameters | Check active Python and transport |
| Root process candidates and `/proc/PID/exe` hash | Compare baseline file with running executable |
| Listener 27044 | Check endpoint without exposing unrelated connections |
| Bridge candidates/hash/format markers | Choose plain adapter or preserve private loader |
| Loader AST markers/runtime literals | Check QJS/V8 and assembly without copying source |
| RikkaHub/Termux package versions | Compare the phone with the sync contract |

If paths are already known, supply them in the same run:

```sh
python3 rebro_collect.py \
  --bridge /absolute/path/bridge-final.js \
  --loader /absolute/path/existing-launcher.py \
  --baseline /absolute/path/trusted-pins.json
```

Flags are repeatable; paths must be actual local files, not unresolved placeholders.
Without --baseline, candidate manifest search extracts only expected artifact
paths/hashes and excludes other JSON fields. Bridge references inside allowed RE
roots are also inspected even when not named bridge-final.js; relative paths resolve
from the manifest directory. Nearby Python/shell launchers are inspected too.
Loader candidates retain AST markers, not source text. A file such as frida_version.py
is not a Java loader merely because of its name/hash: create_script and bridge
indicators are required. contract_verified remains false until live smoke.
No discovered candidate is executed or automatically trusted. Supported read formats:
kit artifacts{id,path,sha256} and Frida Pack pins{binding,bridge,server_binary}.

Output is a new private `HOME/rebro/reports/inventory-...` directory and adjacent ZIP.
stdout reports report_zip. The ZIP contains report.json, SUMMARY.ru.md (English
content; historical filename retained), observed-artifacts.json and SHA256SUMS.
Provide that ZIP when environment evidence is requested.

The collector does not read shell history, environment secrets, keystores, password
files or app data. It does not copy bridge/loader source. The report still contains
system paths, RE directory names, versions, PIDs and working hashes: it is a technical
device report, not anonymous public telemetry.

Access errors, missing tools, timeouts and incomplete search remain explicit statuses;
they do not authorize package installation or SELinux changes during collection.
--no-online skips transport, --no-tools skips versions, --no-discover skips search.
Ctrl-C preserves a partial report. Existing output is never overwritten.

The collector establishes observations, not baseline provenance. Bridge format is
heuristic; a private launcher may need separate analysis. Actual native/Java smoke,
hook hits and align→sign→install on a lab APK remain device acceptance checks.
Successful inventory must not conceal those untested boundaries.
