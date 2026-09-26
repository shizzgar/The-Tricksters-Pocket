# Preparing the Termux environment

## Start the environment stage

Work from rebro-environment's skill_root. Create a separate data/ directory for
the current attempt; do not declare the whole case/attempt as output. Collect
actual observations:

```sh
python3 scripts/doctor.py --workspace "$REBRO_CASE" --out "$DOCTOR_JSON"
```

Add `--root` for scoped root/network inspection and `--probe-frida` when transport
verification belongs to the case. Doctor does not repair the environment or issue
a universal ready verdict. Check required tools, RAM/disk and operation availability
for the selected route. Signing alone does not require root/Frida.
Finish the environment receipt with evidence/output=doctor.json only after those
gates. If a dependency is missing, record blocked and use the relevant section below.

Signing requires Python, Java/apksigner, aapt2 and native zipalign with `-P 16`.
Rebuild also requires Apktool/JDK and framework inputs; install requires actual su/PM.

## Priorities

The **22 September 2026, 23:23 MSK** inventory already had apksigner, zipalign, rg
and jq installed. Read the [device profile](device-profile.md) and this package's
config/device-observed.json. Do not rerun the entire dependency installation below;
only the optional sqlite3 CLI was missing. Add it when the task needs it.
Kit 2.3 obtained full paths/pins/flat-bridge recipe from the supplied prompt:
config/pins.rebro-known-good-20260922.json. Prioritize the new Java adapter and
relevant lab acceptance of signing tools, not bridge rediscovery or full Frida repair.
Recheck only local facts that may have changed and matter to the current step.

| Priority | Action | Readiness criterion |
|---|---|---|
| P0 | Check zipalign ownership/PATH; install apksigner if missing | align → sign → verify passes on a test APK |
| P0 | Record full Frida baseline, loaders and known-good scripts | Three full hashes, capability matrix, recoverable files |
| P0 | Add case layout and JVM limits | Two heavy jobs cannot run together through the wrapper |
| P1 | Add ripgrep, jq, sqlite, tmux, coreutils, file, binutils as needed | Commands found and versions recorded |
| P1 | Preserve APK set and certificates | Inputs treated as immutable; acquisition manifest |
| P1 | Control storage, logs and temperature | Every long task has a deadline and file output |
| P2 | Offload Ghidra, heavy MobSF and large Gradle builds | As needed; not mandatory phone services |

## Inspect first

```sh
command -v apksigner zipalign aapt aapt2 rg jq sqlite3
dpkg-query -W aapt apksigner openjdk-21 ripgrep jq sqlite
dpkg-query -L aapt
apt-cache policy aapt apksigner ripgrep jq sqlite
```

`apksigner` exists in official termux-packages. The reviewed recipe uses an SDK JAR
and OpenJDK 21. An external JAR is a fallback, not the required route. `zipalign`
belongs to `termux/android-build-tools`, which underlies the `aapt` package.
Check local `dpkg-query -L aapt`: a standalone aapt2 does not establish the complete
package/zipalign. Check current mirror availability on the device.

Before package changes, preserve the inventory, local wrappers and custom Frida:

```sh
dpkg-query -W > "$REBRO_CASE/evidence/packages-before.tsv"
python3 -m pip freeze > "$REBRO_CASE/evidence/pip-before.txt"
```

`pip freeze` may contain private dependency URLs; do not publish automatically.
Separately preserve the trusted baseline and files needed to restore the service,
extension and bridge. This does not require copying all 24 GB of cases.

After preserving the baseline, refresh indexes and **inspect the plan**:

```sh
pkg update
apt-get --simulate install aapt apksigner ripgrep jq sqlite tmux coreutils file binutils
```

Then apply the reviewed set:

```sh
pkg install aapt apksigner ripgrep jq sqlite tmux coreutils file binutils
```

Versions in this note are not guaranteed mirror candidates. Do not disable apt
signature checks or add an arbitrary repository to obtain zipalign. Termux rolls
forward; long-lived partial upgrades can break dependencies. If the plan changes
Python/LLVM/JDK or ABI dependencies of custom Frida, treat it as environment
maintenance with verification/rollback; do not solve it with a global apt freeze.

After installation: `apksigner version`, `zipalign` (usage), `aapt2 version`,
`rg --version`, `jq --version`, `sqlite3 --version`; then hashes and Frida smoke.
The reviewed zipalign has no -h. With no arguments it may return 2 and display
usage; recognized help establishes flags, not successful alignment. The real
check is `zipalign -c -P 16 -v 4` on a specific aligned APK. If the package exists
but the file is absent, inspect `dpkg -V aapt` and PATH before reinstalling; first
establish that reinstalling will not replace local modifications.

## Optional additions

- `strace`: useful for an owned lab process; ptrace/SELinux may restrict access. Do not attach several tracers at once.
- `tcpdump`/Wireshark offload: investigate transport for a concrete hypothesis. Termux lacking `/proc/net/tcp` access does not prove no connections exist.
- `bundletool`: only for AAB/APKS work when needed; distinct from acquiring installed splits.
- Ghidra headless/MobSF: offload to another machine when needed. PRoot/glibc is unnecessary for the current minimal pipeline.
- wget/7z are conveniences; curl/zip/unzip suffice for this kit.

## Background resilience

For a long controlled run, use `termux-wake-lock`, followed by `termux-wake-unlock`.
It cannot guarantee survival of force-stop, freezer or LMKD. Check Android battery
policy for Termux/The Trickster's Pocket, foreground-service notifications, `dumpsys thermalservice`
and charging state. Do not disable thermal management. Tmux survives terminal loss,
not reboot or termination of the Termux UID.

Sources: [Termux apksigner](https://github.com/termux/termux-packages/blob/master/packages/apksigner/build.sh),
[aapt recipe](https://github.com/termux/termux-packages/blob/master/packages/aapt/build.sh),
[android-build-tools](https://github.com/termux/android-build-tools),
[package management](https://github.com/termux/termux-packages/wiki/Package-Management).
