"""Read-only environment report. --root adds narrowly scoped privileged reads."""
import argparse
import json
import os
from pathlib import Path
import platform
import shutil
from common import atomic_json, capture, memory_mib, now, read_text


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--root", action="store_true")
    p.add_argument("--probe-frida", action="store_true")
    p.add_argument("--workspace", default=str(Path.home()))
    p.add_argument("--out", help="Optional JSON path, outside the imported kit")
    a = p.parse_args()
    os.umask(0o077)
    d = {"schema": 1, "observed_at": now(), "source": "local_read_only_probe",
         "platform": platform.platform(), "machine": platform.machine(),
         "uid": os.getuid(), "page_size": os.sysconf("SC_PAGE_SIZE"),
         "boot_id": read_text("/proc/sys/kernel/random/boot_id"),
         "memory_mib": memory_mib(), "cgroup": read_text("/proc/self/cgroup"),
         "loadavg": read_text("/proc/loadavg"), "pressure_memory": read_text("/proc/pressure/memory")}
    disk = shutil.disk_usage(a.workspace)
    d["disk"] = {"path": str(Path(a.workspace).resolve()),
                 "free_bytes": disk.free, "total_bytes": disk.total}
    names = "java jadx apktool aapt aapt2 apksigner zipalign rg jq sqlite3 tmux timeout git curl clang llvm-readelf llvm-objdump r2 frida frida-ps".split()
    d["tools"] = {n: shutil.which(n) for n in names}
    d["android"] = {}
    if shutil.which("getprop"):
        for name in ("ro.product.model", "ro.product.device", "ro.soc.manufacturer",
                     "ro.soc.model", "ro.board.platform", "ro.product.cpu.abilist",
                     "ro.build.version.sdk", "ro.build.version.security_patch",
                     "ro.build.fingerprint", "ro.boot.verifiedbootstate", "ro.debuggable"):
            result = capture(["getprop", name])
            d["android"][name] = result.get("stdout", "").strip() if result.get("returncode") == 0 else result
    d["cpu_policies"] = [{"path": str(x), "cpus": read_text(x / "related_cpus"),
                           "max_khz": read_text(x / "cpuinfo_max_freq")}
                          for x in sorted(Path("/sys/devices/system/cpu/cpufreq").glob("policy*"))]
    d["cpuinfo"] = (read_text("/proc/cpuinfo") or "")[:20000]
    d["package_probe"] = capture(["dpkg-query", "-W", "aapt", "apksigner", "openjdk-21", "ripgrep", "jq", "sqlite"])
    d["aapt_files"] = capture(["dpkg-query", "-L", "aapt"])
    if a.root:
        d["root"] = {}
        for key, cmd in {"id": "id", "selinux": "getenforce",
                         "thermal": "dumpsys thermalservice", "battery": "dumpsys battery",
                         "self_cgroup": "cat /proc/" + str(os.getpid()) + "/cgroup"}.items():
            d["root"][key] = capture(["su", "-c", cmd])
    if a.probe_frida:
        import sys
        d["frida"] = capture([sys.executable, str(Path(__file__).with_name("frida_run.py")), "--health"], timeout=20)
    d["interpretation"] = ["Tool presence is not a capability test.",
        "A successful health probe does not prove attach, Java hooks, spawn or Compiler.",
        "Memory and free space are instantaneous observations; swap is not a heap budget."]
    if a.out:
        atomic_json(a.out, d)
    print(json.dumps(d, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
