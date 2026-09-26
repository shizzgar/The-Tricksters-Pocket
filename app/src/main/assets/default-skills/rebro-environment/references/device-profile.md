# Device inventory: 22 September 2026, 23:23 MSK

Source: user-supplied `inventory-20260922-232336-bc9bd0.zip`, collector 2.1.0.
Internal SHA256SUMS were verified. Derived JSON is in config/device-observed.json
in environment/frida and DEVICE-PROFILE.observed.json in the original distribution.
This is a snapshot for command selection, not a trusted pin manifest.

**Kit 2.3 addition:** after inventory, the user supplied the full prompt with canonical
paths/pins and frida_java_bridge_default export. A separate
pins.rebro-known-good-20260922.json was created; observations below were not relabeled
as new measurements. An agent text report also states native RPC smoke and Compiler
TS build passed. Another broad collector run for paths/pins is unnecessary.
Read system-prompt-integration.md; the earlier inventory diagnosis remains below.

## Established by the report

| Parameter | Observation | Implication |
|---|---|---|
| Hardware | SM-S928B / e3q, QTI SM8650, pineapple | Do not use the earlier Exynos assumption |
| Android / ABI / page size | Android 16, API 36, arm64-v8a, 4096 bytes | Native tools must target Android arm64; 16 KiB zipalign is an APK parameter, not this kernel's page size |
| RAM / data | MemAvailable 2905080 KiB, about 2.77 GiB; about 25.91 GiB free | One JVM job, 1536 MiB heap, 1024 MiB reserve; signing 512 MiB; refresh before a job |
| Power | 41%, AC/USB/wireless false; battery 36.2°C | Earlier "always charging" observation was stale |
| Root / freezer | su works; service in u:r:ksu:s0; cgroup.freeze=0 | Bounded root reads available; no guarantee against later freezer/LMKD |
| APK tools | aapt/aapt2 16.0.0.4-2; apksigner package 37.0.0; Java 21.0.12 | Do not download another signer or reinstall working packages |
| zipalign | Included in aapt; help supports -P 4/16/64 | Old `zipalign -h` returned 2 for unknown -h, not a missing tool |
| Utilities | rg 15.2.0, jq 1.8.2 installed; sqlite3 CLI absent | SQLite CLI is optional for database tasks |
| Android users | 0 and 150 | Select an explicit user; shared APK-code changes can affect other profiles |
| The Trickster's Pocket | excp.rikkahub.debug 2.5.1 / code 186, plus two other installations | Select the intended installation; version alone does not prove its source commit |

`apksigner version` returned **0.9**, while the Termux package version was **37.0.0**;
these are different fields. Tools/help do not establish a real align→sign→verify→install
operation. Preserve the signer and perform relevant lab acceptance through sign/install/verify.

## Frida: working transport and remaining evidence

Endpoint `127.0.0.1:27044`: handshake **19.4 ms**, access=full, 401 processes.
Observed service PID 17436; resolve it again for the next execution.

| Artifact | Full observed SHA-256 |
|---|---|
| Running service `/proc/17436/exe` | c377c4bb2eb42bfc99a89bb68bc65d4581f1fd84057b23459887d1da3da3fd87 |
| Actually loaded `_frida.abi3.so` | d3550a89c0cdf32717417f3fdf116e1b462e7c1feb434fbd61ac7f7747eec61a |

Paths are in JSON. Binding reports 17.2.14; dpkg reports 17.2.14-4+rebro.compiler1.
Compiler.build/watch availability was observed, but no compilation ran in this
inventory. Both hashes match the previously supplied prefix/suffix values. These
full hashes were observed, so do not automatically promote them to trusted pins.

Search 2.1.0 visited exactly 25000 entries and stopped in build trees. Bridge/baseline
were not found. Three Python candidates lacked create_script, bridge or other Java
loader markers. The old missing summary incorrectly treated any read candidate as
sufficient; the loader contract remained unknown.

Collector 2.2.0 searches breadth-first, bounds each directory/candidate type, reads
allowed bridge paths from candidate manifests and inspects neighboring launchers
by content. A manifest match is useful correlation, not proof of provenance.
No discovered script is executed.

Before the full prompt was supplied, a focused Termux run was suggested:

```sh
python3 rebro_collect.py --focus frida
```

That repeat is no longer required for this baseline. For an unknown environment,
it skips full hardware/tool/package inventory, uses scoped su reads, checks the
same loopback endpoint and permits up to 100000 search entries. Preserve the new ZIP.
Do not select the first bridge or newest mtime as known-good. Offline native build
and the APK skills remain available while Java loader identity is unresolved;
live Frida adapter use still requires the complete trusted baseline.

## Fork integration

The contract was checked against PR #1 head `68f0038279735eb33502815cc874f930021abbd7`.
For external imports, use the 10 individual ZIPs from imports/, not the whole kit
as one skill. Sync from the selected The Trickster's Pocket installation and take skill_root
from its response. Multiple package IDs have separate Termux copies. Do not derive
paths from versionName or use another package ID's copy.

Native/Java smoke was not yet supplied at inventory time. Later the user reported
native RPC smoke and Compiler build; that run did not perform a fresh Java hook.
Actual lab-APK signing/installation and use of the new kit on the phone were not
yet confirmed. Do not label phone end-to-end passed from this evidence.

Contract source: [fork guide](https://github.com/shizzgar/The-Tricksters-Pocket/blob/68f0038279735eb33502815cc874f930021abbd7/docs/agent-runtime/trajectory-and-termux-skills.ru.md).
