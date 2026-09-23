"""Shared helpers. Python stdlib only; all generated files stay outside the kit."""
import sys
sys.dont_write_bytecode = True
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def digest(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def atomic_json(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=".write-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
            f.write("\n")
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def read_text(path):
    try:
        return Path(path).read_text(errors="replace").strip()
    except OSError:
        return None


def memory_mib():
    result = {}
    for line in (read_text("/proc/meminfo") or "").splitlines():
        key, value = line.split(":", 1)
        if key in ("MemTotal", "MemAvailable", "SwapTotal", "SwapFree"):
            result[key] = int(value.split()[0]) // 1024
    return result


def capture(argv, timeout=12):
    try:
        p = subprocess.run(argv, stdin=subprocess.DEVNULL, capture_output=True,
                           text=True, errors="replace", timeout=timeout)
        return {"argv": argv, "returncode": p.returncode,
                "stdout": p.stdout[:24000], "stderr": p.stderr[:4000],
                "truncated": len(p.stdout) > 24000 or len(p.stderr) > 4000}
    except (OSError, subprocess.TimeoutExpired) as e:
        return {"argv": argv, "returncode": None, "error": str(e)}
