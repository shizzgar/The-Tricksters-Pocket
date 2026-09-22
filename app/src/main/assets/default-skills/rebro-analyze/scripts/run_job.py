"""Bounded foreground job for a RikkaHub managed background job; stdlib only."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time
from common import atomic_json, memory_mib, now


def terminate_group(proc):
    if proc is None:
        return
    try:
        os.killpg(proc.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        proc.wait(timeout=3)
    except subprocess.TimeoutExpired:
        pass
    # Also stop descendants that outlived their direct parent in the same group.
    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    proc.wait()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--job-dir", required=True, help="Must not exist")
    p.add_argument("--cwd", required=True)
    p.add_argument("--resource", choices=["jvm", "light"], default="jvm")
    p.add_argument("--heap-mib", type=int, default=1536)
    p.add_argument("--reserve-mib", type=int, default=1024)
    p.add_argument("--min-free-mib", type=int, default=5120)
    p.add_argument("--max-log-mib", type=int, default=32)
    p.add_argument("--seconds", type=float, default=900)
    p.add_argument("--lock-file", default=str(Path.home() / "rebro/.locks/jvm.lock"))
    p.add_argument("command", nargs=argparse.REMAINDER)
    a = p.parse_args()
    argv = a.command[1:] if a.command[:1] == ["--"] else a.command
    if not argv or a.seconds <= 0 or a.heap_mib < 128 or a.reserve_mib < 0 or a.min_free_mib < 0 or a.max_log_mib < 1:
        p.error("A command and valid positive limits are required")
    os.umask(0o077)
    job = Path(a.job_dir).resolve()
    job.mkdir(parents=True, exist_ok=False)
    state = {"schema": 1, "started_at": now(), "argv": argv, "cwd": str(Path(a.cwd).resolve()),
             "resource": a.resource, "state": "preflight", "returncode": None,
             "boot_id": Path("/proc/sys/kernel/random/boot_id").read_text().strip()}
    status = job / "status.json"
    atomic_json(status, state)
    lock = None
    proc = None
    code = 70
    cancelled = [False]
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: cancelled.__setitem__(0, True))
    try:
        if a.resource == "jvm":
            lock_path = Path(a.lock_file).expanduser()
            lock_path.parent.mkdir(parents=True, exist_ok=True)
            lock = lock_path.open("a+")
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                state.update(state="resource_busy", detail="Another JVM job holds the global lock")
                code = 75
                return code
            available = memory_mib().get("MemAvailable")
            state["available_memory_mib"] = available
            if available is None or available < a.heap_mib + a.reserve_mib:
                state.update(state="resource_refused", detail="Insufficient MemAvailable for heap plus reserve")
                code = 75
                return code
        if shutil.disk_usage(a.cwd).free < a.min_free_mib * 1024 ** 2:
            state.update(state="resource_refused", detail="Insufficient free space")
            code = 75
            return code
        env = os.environ.copy()
        if a.resource == "jvm":
            env["_JAVA_OPTIONS"] = f"-Xms64m -Xmx{a.heap_mib}m -XX:ActiveProcessorCount=2"
        stdout, stderr = job / "stdout.log", job / "stderr.log"
        with stdout.open("xb") as out, stderr.open("xb") as err:
            proc = subprocess.Popen(argv, cwd=a.cwd, env=env, stdin=subprocess.DEVNULL,
                                    stdout=out, stderr=err, start_new_session=True)
            state.update(state="running", pid=proc.pid)
            atomic_json(status, state)
            started = time.monotonic()
            while proc.poll() is None:
                reason = None
                if cancelled[0]:
                    reason, code = "cancelled", 130
                elif time.monotonic() - started >= a.seconds:
                    reason, code = "timed_out", 124
                elif stdout.stat().st_size + stderr.stat().st_size > a.max_log_mib * 1024 ** 2:
                    reason, code = "log_limit", 74
                elif shutil.disk_usage(a.cwd).free < a.min_free_mib * 1024 ** 2:
                    reason, code = "disk_limit", 74
                if reason:
                    state["state"] = reason
                    terminate_group(proc)
                    break
                time.sleep(0.2)
            else:
                raw = proc.returncode
                code = raw if raw >= 0 else 128 - raw
                state["state"] = "completed" if code == 0 else "failed"
                if stdout.stat().st_size + stderr.stat().st_size > a.max_log_mib * 1024 ** 2:
                    state["state"], code = "log_limit", 74
                elif shutil.disk_usage(a.cwd).free < a.min_free_mib * 1024 ** 2:
                    state["state"], code = "disk_limit", 74
            state["child_returncode"] = proc.returncode
            state["elapsed_seconds"] = round(time.monotonic() - started, 3)
        # No lingering same-group subprocesses, including after normal completion.
        terminate_group(proc)
    except (OSError, ValueError) as e:
        state.update(state="failed", error=str(e))
        terminate_group(proc)
    finally:
        state.update(returncode=code, finished_at=now())
        atomic_json(status, state)
        if lock is not None:
            lock.close()
        print(json.dumps(state, ensure_ascii=False))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
