# Profile and evidence boundaries

Original observations were supplied by the user: **22 September 2026, 19:41 MSK
(16:41 UTC)**. The kit authors did not have access to the phone. Past PIDs, boot ID,
RAM/free space and ports are not current observations without a fresh read.

| Component | Original profile | Operating rule |
|---|---|---|
| Android | API 36, Enforcing, KernelSU | Root does not guarantee access to every SELinux object |
| ABI | arm64-v8a | Android/Bionic arm64, not an arbitrary Linux/glibc arm64 binary |
| RAM | MemAvailable about 3.1 GB | Start with 1536 MiB heap; 2048 MiB only after a fresh measurement |
| Swap | About 8 GB already used | Not spare capacity for a second JVM |
| Disk | /data about 27 GB free, cases about 24 GB | 5 GiB minimum working reserve is kit policy |
| Frida | Custom core 17.18.0; Python 17.2.14-4+rebro.compiler1 | Preserve the known combination |
| Service | rebro-frida --serve, loopback:27044 | Do not start a second instance |
| Bridge | Patched bridge-final.js | Do not silently replace with upstream |
| Compiler | build/watch available | Verify the particular project's build separately |
| Power | AC, charging | Check temperature, Doze/freezer and LMKD separately |

## Correct the inventory

The original description mixed SM-S928B/e3q with "Galaxy S24 / Exynos 2400".
SM-S928B is a **Galaxy S24 Ultra** variant; Samsung describes S24 Ultra with
Snapdragon 8 Gen 3 for Galaxy. The stated eight cores/frequencies also do not
establish Exynos. Verify:

```sh
getprop ro.product.model
getprop ro.product.device
getprop ro.soc.manufacturer
getprop ro.soc.model
getprop ro.board.platform
getconf PAGESIZE
cat /proc/cpuinfo
```

Do not assign CPUs based on assumed A520/A720 topology before checking it.
CPU PAC/BTI features do not prove use by a specific ELF. Android 16 alone does
not establish whether this kernel uses 4 KiB or 16 KiB pages.

verifiedbootstate=green and ro.debuggable=0 do not refute observed KernelSU root.
Preserve both properties and actual `id`/context for reproducibility.
In Termux, `/usr/bin` usually refers to `$PREFIX/bin`, not a system `/usr/bin`.

## What the original Frida check does not establish

Handshake + process listing establish transport/enumeration at that time.
The capability matrix needs separate entries for attach to the selected PID,
script load, native-ready, Java-ready, observation hook, Compiler build and
unload/detach. Spawn/gating needs its own lab-APK check. Preserve different
client/core versions as a locally evidenced baseline without claiming every API
is compatible.

The initial request omitted full SHA-256 and absolute paths for three components.
`config/pins.example.json` intentionally leaves them null; abbreviated hashes are
not pins. The later supplied baseline is documented in system-prompt-integration.md
and config/pins.rebro-known-good-20260922.json. PID is never a pin.

Sources: [Samsung](https://www.samsung.com/levant/smartphones/galaxy-s24-ultra/),
[Android page sizes](https://developer.android.com/guide/practices/page-sizes).
