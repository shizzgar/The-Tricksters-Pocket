"""Copy all installed APK splits to a NEW directory. Root reads only; no install."""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
from common import atomic_json, digest, now


def root_read(argv, timeout=30):
    return subprocess.check_output(["su", "-c", shlex.join(argv)], stdin=subprocess.DEVNULL,
                                   timeout=timeout, text=True).strip()


def package_paths(package, user):
    lines = root_read(["/system/bin/pm", "path", "--user", str(user), package]).splitlines()
    result = []
    for line in lines:
        if not line.startswith("package:"):
            raise ValueError("Unexpected pm path output: " + line)
        path = line[len("package:"):]
        if not path.startswith("/") or not path.endswith(".apk") or "\n" in path:
            raise ValueError("Invalid APK path")
        result.append(path)
    if not result or len(result) != len(set(result)):
        raise ValueError("No APKs or duplicate paths returned")
    return sorted(result)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("package")
    p.add_argument("--android-user", type=int, required=True)
    p.add_argument("--out-dir", required=True, help="Must not exist")
    a = p.parse_args()
    if a.android_user < 0 or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", a.package):
        p.error("Invalid package or user")
    os.umask(0o077)
    out = Path(a.out_dir).resolve()
    out.mkdir(parents=True, exist_ok=False)
    report = {"schema": 1, "started_at": now(), "package": a.package,
              "android_user": a.android_user, "status": "incomplete", "apks": []}
    atomic_json(out / "acquisition.json", report)
    try:
        paths = package_paths(a.package, a.android_user)
        for index, source in enumerate(paths):
            size = int(root_read(["/system/bin/toybox", "stat", "-c", "%s", source]))
            if shutil.disk_usage(out).free < size + 1024 ** 3:
                raise ValueError("Less than APK size + 1 GiB free")
            dest = out / f"{index:02d}-{Path(source).name}"
            with dest.open("xb") as f:
                subprocess.run(["su", "-c", shlex.join(["/system/bin/cat", source])],
                               stdin=subprocess.DEVNULL, stdout=f, check=True, timeout=180)
            actual = digest(dest)
            expected = root_read(["/system/bin/toybox", "sha256sum", source], timeout=180).split()[0]
            if actual != expected or dest.stat().st_size != size:
                raise ValueError("APK changed during acquisition or copy failed: " + source)
            dest.chmod(0o400)
            report["apks"].append({"source": source, "file": dest.name, "size_bytes": size, "sha256": actual})
            atomic_json(out / "acquisition.json", report)
        if package_paths(a.package, a.android_user) != paths:
            raise ValueError("Installed APK set changed during acquisition")
        report["status"] = "complete"
    except Exception as e:
        report.update(status="failed", error=type(e).__name__ + ": " + str(e))
    report["finished_at"] = now()
    atomic_json(out / "acquisition.json", report)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["status"] == "complete" else 1


if __name__ == "__main__":
    raise SystemExit(main())
