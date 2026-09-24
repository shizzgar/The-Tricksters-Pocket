#!/usr/bin/env python3
"""Read-only NetBro environment/version probes; no installation or scans."""
import argparse
import json
import os
import platform
import selectors
import shutil
import signal
import subprocess
import sys
import time

VERSION_ARGS = {
    "bbot": ["--version"],
    "nmap": ["--version"],
    "nuclei": ["-version"],
    "legba": ["--version"],
}
OUTPUT_LIMIT = 4096


def stop_group(process):
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait(timeout=2)


def probe(name, timeout):
    executable = shutil.which(name)
    if executable is None:
        return {"tool": name, "status": "missing", "version_ok": False}
    result = {"tool": name, "path": executable, "version_ok": False}
    try:
        process = subprocess.Popen(
            [executable, *VERSION_ARGS[name]], stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True,
        )
    except OSError as error:
        return {**result, "status": "launch_error", "error": str(error)}
    output = bytearray()
    total = 0
    timed_out = False
    deadline = time.monotonic() + timeout
    try:
        with selectors.DefaultSelector() as selector:
            os.set_blocking(process.stdout.fileno(), False)
            selector.register(process.stdout, selectors.EVENT_READ)
            while selector.get_map() or process.poll() is None:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    timed_out = True
                    stop_group(process)
                    break
                for key, _ in selector.select(min(remaining, 0.1)):
                    chunk = os.read(key.fd, 16384)
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    total += len(chunk)
                    output.extend(chunk[:max(0, OUTPUT_LIMIT - len(output))])
            code = process.wait(timeout=2)
    finally:
        if process.poll() is None:
            stop_group(process)
        process.stdout.close()
    text = output.decode("utf-8", errors="replace").strip()
    return {
        **result, "status": "timeout" if timed_out else "checked",
        "exit_code": code, "version_ok": not timed_out and code == 0 and bool(text),
        "version_output": text, "output_truncated": total > OUTPUT_LIMIT,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tool", action="append", choices=VERSION_ARGS)
    parser.add_argument("--timeout", type=float, default=5.0, help="Seconds per version command (0.1-30)")
    args = parser.parse_args(argv)
    if not 0.1 <= args.timeout <= 30:
        parser.error("--timeout must be between 0.1 and 30 seconds")
    names = list(dict.fromkeys(args.tool or VERSION_ARGS))
    report = {
        "schema_version": 1, "kind": "local_version_probe",
        "platform": sys.platform, "machine": platform.machine(),
        "python": platform.python_version(), "uid": os.getuid(),
        "prefix": os.environ.get("PREFIX"), "home": os.path.expanduser("~"),
        "tools": [probe(name, args.timeout) for name in names],
        "note": "Version checks do not establish scan/plugin readiness.",
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if all(item["version_ok"] for item in report["tools"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
