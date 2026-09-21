#!/usr/bin/env python3
"""Private Termux job supervisor. No network, root service, or shell interpolation in the RPC.

Waiting/cancelling the Android caller never repeats a launch. Durable metadata identifies
processes by boot ID + /proc start ticks, not PID alone. Logs are capped on disk, not only
in the LLM response. All runtime responses are bounded JSON.
"""
import base64
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import signal
import subprocess
import sys
import time

LOG_LIMIT = 8 * 1024 * 1024
STORE_LIMIT = 256 * 1024 * 1024
ACTIVE = {"starting", "running", "cancelling"}
BOOT_MARKER = None
BASE = Path(__file__).resolve().parent / "data"
BASH = "/data/data/com.termux/files/usr/bin/bash"
if not Path(BASH).exists():  # Host-side contract tests use the same supervisor.
    BASH = "/bin/bash"


def atomic(path, value):
    temp = path.with_name(path.name + ".tmp-" + str(os.getpid()))
    with open(temp, "w", encoding="utf-8") as out:
        json.dump(value, out, ensure_ascii=False)
        out.flush()
        os.fsync(out.fileno())
    os.replace(temp, path)


def read(path):
    with open(path, encoding="utf-8") as src:
        return json.load(src)


def boot_id():
    # Android can deny proc_random. The app supplies BOOT_COUNT through its platform
    # API, not model arguments. Workers keep their launch marker; observers get the
    # current marker so a reboot invalidates the saved process identity.
    if BOOT_MARKER is not None:
        return BOOT_MARKER
    return Path("/proc/sys/kernel/random/boot_id").read_text().strip()


def set_boot_marker(value):
    global BOOT_MARKER
    if value is not None and not re.fullmatch(r"android-boot-count:[0-9]+", str(value)):
        raise ValueError("invalid platform boot marker")
    BOOT_MARKER = value


def identity(pid):
    try:
        stat = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
        if stat[0] == "Z":
            return None
        return {"pid": int(pid), "start_ticks": stat[19], "boot_id": boot_id()}
    except (FileNotFoundError, ProcessLookupError, PermissionError):
        return None


def same_process(expected):
    return bool(expected) and identity(expected["pid"]) == expected


@contextlib.contextmanager
def lock(path):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with open(path, "a") as fd:
        fcntl.flock(fd, fcntl.LOCK_EX)
        yield


def validate_id(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{24}", value):
        raise ValueError("invalid job/owner id")
    return value


def job_dir(owner, job_id):
    return BASE / validate_id(owner) / validate_id(job_id)


def status(folder):
    spec = read(folder / "request.json")
    try:
        state = read(folder / "status.json")
    except FileNotFoundError:
        state = {"state": "starting", "created_at": spec["created_at"]}
    if state["state"] in ACTIVE:
        if state.get("worker") and not same_process(state["worker"]):
            state = dict(state, state="unknown", reason="supervisor_missing_or_device_rebooted")
        elif not state.get("worker") and time.time() - spec["created_at"] > 15:
            state = dict(state, state="unknown", reason="launch_not_confirmed; do not relaunch blindly")
    return dict(state, job_id=folder.name, operation_id=spec["operation_id"],
                working_dir=spec["working_dir"], log_path=str(folder),
                cancel_scope="managed process group; detached/new-session or privileged descendants may escape",
                log_limit_bytes=LOG_LIMIT, command=spec["command"])


def start(request):
    owner = validate_id(request["owner"])
    operation = request["operation_id"]
    if not isinstance(operation, str) or not 1 <= len(operation) <= 128:
        raise ValueError("operation_id must have 1–128 characters; reuse only for the same launch")
    command = request["command"]
    if not isinstance(command, str) or not 1 <= len(command) <= 128_000:
        raise ValueError("command must have 1–128000 characters")
    working_dir = request.get("working_dir") or str(Path.home())
    if not Path(working_dir).is_dir():
        raise ValueError("working_dir does not exist")
    timeout = max(1, min(int(request.get("execution_timeout_seconds", 3600)), 86400))
    spec_key = dict(command=command, working_dir=working_dir, execution_timeout_seconds=timeout)
    fingerprint = hashlib.sha256(json.dumps(spec_key, sort_keys=True).encode()).hexdigest()
    job_id = hashlib.sha256((owner + ":" + operation).encode()).hexdigest()[:24]
    folder = job_dir(owner, job_id)
    with lock(BASE / ".launch.lock"):
        if folder.exists():
            old = read(folder / "request.json")
            if old["fingerprint"] != fingerprint:
                return {"success": False, "error": "operation_id_conflict", "job_id": job_id}
            return dict(status(folder), reused=True)
        if identity(os.getpid()) is None:
            return {"success": False, "error": "process_identity_unavailable",
                    "recovery": "No command launched: reliable boot and process identity are required."}
        stored = sum(p.stat().st_size for p in BASE.glob("*/*/*.log"))
        active = sum(status(p.parent)["state"] in ACTIVE for p in BASE.glob("*/*/request.json"))
        if active >= 4:
            return {"success": False, "error": "active_job_limit", "limit": 4}
        if stored + (active + 1) * 2 * LOG_LIMIT > STORE_LIMIT:
            return {"success": False, "error": "log_quota_exhausted", "recovery": "Remove reviewed finished jobs with termux_job_forget."}
        folder.mkdir(parents=True, mode=0o700)
        spec = dict(spec_key, owner=owner, operation_id=operation, fingerprint=fingerprint,
                    platform_boot_marker=BOOT_MARKER, created_at=time.time())
        atomic(folder / "request.json", spec)
        atomic(folder / "status.json", {"state": "starting", "created_at": spec["created_at"]})
        try:
            process = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "worker", str(folder)],
                                       stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                       stderr=subprocess.DEVNULL, start_new_session=True, close_fds=True)
            # The worker writes its own identity before spawning the command. A lost ACK is
            # recoverable by deterministic job_id/operation_id; start never relaunches a record.
        except Exception as exc:
            atomic(folder / "status.json", {"state": "failed", "error": "launch_failed", "reason": str(exc), "finished_at": time.time()})
        for _ in range(20):
            result = status(folder)
            if result["state"] != "starting":
                break
            time.sleep(.025)
        return dict(result, reused=False)


def worker(folder):
    spec = read(folder / "request.json")
    set_boot_marker(spec.get("platform_boot_marker"))
    own = identity(os.getpid())
    state = {"state": "starting", "worker": own, "created_at": spec["created_at"], "started_at": time.time()}
    atomic(folder / "status.json", state)
    process = None
    try:
        if own is None:
            raise RuntimeError("process_identity_unavailable; command was not launched")
        process = subprocess.Popen([BASH, "-c", spec["command"]], cwd=spec["working_dir"],
                                   stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                   start_new_session=True)
        child = identity(process.pid)
        state.update(state="running", process=child)
        atomic(folder / "status.json", state)
        selector = selectors.DefaultSelector()
        outputs = {}
        counts = {"stdout": 0, "stderr": 0}
        for name in counts:
            pipe = getattr(process, name)
            os.set_blocking(pipe.fileno(), False)
            selector.register(pipe, selectors.EVENT_READ, name)
            outputs[name] = open(folder / (name + ".log"), "wb", buffering=0)
        # Observe exit WITHOUT reaping the group leader. Its PID/PGID remains reserved
        # until all our signalling is finished, avoiding PID reuse races during cancellation.
        def child_done():
            return os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT) is not None
        deadline = time.monotonic() + spec["execution_timeout_seconds"]
        stopping = None
        kill_at = None
        finished_at = None
        while selector.get_map() or not child_done():
            now = time.monotonic()
            if stopping is None and ((folder / "cancel.request").exists() or now >= deadline):
                stopping = "cancelled" if (folder / "cancel.request").exists() else "timed_out"
                state.update(state="cancelling")
                atomic(folder / "status.json", state)
                # This process is still our unreaped child. Its process-group ID cannot be
                # reused while this relationship is held. Signal the whole original group.
                with contextlib.suppress(ProcessLookupError):
                    os.killpg(process.pid, signal.SIGTERM)
                kill_at = now + 2
            if kill_at is not None and now >= kill_at:
                with contextlib.suppress(ProcessLookupError):
                    os.killpg(process.pid, signal.SIGKILL)
                kill_at = None
            for key, _ in selector.select(.1):
                data = os.read(key.fileobj.fileno(), 65536)
                name = key.data
                if not data:
                    selector.unregister(key.fileobj)
                    key.fileobj.close()
                    continue
                before = counts[name]
                counts[name] += len(data)
                if before < LOG_LIMIT:
                    outputs[name].write(data[:LOG_LIMIT - before])
            if child_done():
                if finished_at is None:
                    finished_at = now
                # A detached child may keep pipes open indefinitely. Bound drain time and
                # explicitly report that command exit does not prove those descendants ended.
                if now - finished_at >= 3 and kill_at is None:
                    break
        if kill_at is not None:
            with contextlib.suppress(ProcessLookupError):
                os.killpg(process.pid, signal.SIGKILL)
        rc = process.wait()
        pipes_open = bool(selector.get_map())
        selector.close()
        for output in outputs.values():
            output.close()
        state.update(state=stopping or ("completed" if rc == 0 else "failed"), exit_code=rc,
                     finished_at=time.time(), output_bytes=counts,
                     logs_truncated={name: count > LOG_LIMIT for name, count in counts.items()},
                     descendant_pipes_open=pipes_open)
        atomic(folder / "status.json", state)
    except BaseException as exc:
        if process is not None and process.returncode is None:
            with contextlib.suppress(ProcessLookupError, PermissionError):
                os.killpg(process.pid, signal.SIGKILL)
            with contextlib.suppress(subprocess.TimeoutExpired):
                process.wait(timeout=2)
        state.update(state="unknown", error=type(exc).__name__, reason=str(exc), finished_at=time.time())
        atomic(folder / "status.json", state)


def page(folder, request):
    stream = request.get("stream", "stdout")
    if stream not in ("stdout", "stderr"):
        raise ValueError("stream must be stdout or stderr")
    cursor = max(0, int(request.get("cursor", 0)))
    limit = max(256, min(int(request.get("max_bytes", 12000)), 32000))
    path = folder / (stream + ".log")
    data = b""
    size = path.stat().st_size if path.exists() else 0
    if path.exists():
        with path.open("rb") as src:
            src.seek(min(cursor, size))
            data = src.read(limit + 4)
    data = data[:limit]
    # Preserve complete UTF-8 sequences at page boundaries, including concurrent appends.
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as exc:
        if exc.reason == "unexpected end of data" and exc.start >= len(data) - 4:
            data = data[:exc.start]
        text = data.decode("utf-8", errors="replace")
    result = status(folder)
    return dict(result, stream=stream, text=text, cursor=cursor, next_cursor=min(cursor, size) + len(data),
                stored_bytes=size, has_more=min(cursor, size) + len(data) < size,
                encoding="utf-8; non-UTF8 bytes replaced")


def output_preview(folder, request, current):
    """Read bounded pages of both streams; observation never executes the command again."""
    result = dict(current)
    for stream in ("stdout", "stderr"):
        preview = page(folder, {"stream": stream, "cursor": request.get(stream + "_cursor", 0),
                                "max_bytes": min(6000, max(256, int(request.get("output_max_bytes", 6000))))})
        result[stream] = preview["text"]
        for key in ("cursor", "next_cursor", "has_more", "stored_bytes"):
            result[stream + "_" + key] = preview[key]
    result["output_observed_at"] = time.time()
    return result


def dispatch(request):
    set_boot_marker(request.get("platform_boot_marker"))
    action = request["action"]
    owner = validate_id(request["owner"])
    if action == "start":
        result = start(request)
        if result.get("job_id") and result.get("error") != "operation_id_conflict":
            return output_preview(job_dir(owner, result["job_id"]), request, result)
        return result
    if action == "list":
        jobs = sorted((BASE / owner).glob("*/request.json"), key=lambda p: p.stat().st_mtime, reverse=True)
        cursor = max(0, int(request.get("cursor", 0)))
        snapshots = [status(p.parent) for p in jobs]
        results = snapshots[cursor:cursor + 20]
        for item in results:
            item["command"] = item["command"][:240]
        return {"success": True, "jobs": results, "next_cursor": cursor + len(results), "has_more": cursor + len(results) < len(jobs),
                "total_jobs": len(jobs), "active_jobs": sum(item["state"] in ACTIVE for item in snapshots)}
    folder = job_dir(owner, request["job_id"])
    if not (folder / "request.json").exists():
        return {"success": False, "error": "job_not_found"}
    if action == "read":
        return page(folder, request)
    if action == "forget":
        with lock(BASE / ".launch.lock"):
            current = status(folder)
            if current["state"] in ACTIVE or current["state"] == "unknown":
                return {"success": False, "error": "job_not_confirmed_finished"}
            # Keep the operation receipt, so a later duplicate start cannot execute again.
            for name in ("stdout.log", "stderr.log"):
                (folder / name).unlink(missing_ok=True)
            state = read(folder / "status.json")
            atomic(folder / "status.json", dict(state, logs_removed=True))
            return dict(status(folder), success=True)
    if action == "cancel":
        current = status(folder)
        if current["state"] in ACTIVE and (same_process(current.get("worker")) or
                                            (current["state"] == "starting" and not current.get("worker"))):
            (folder / "cancel.request").touch(mode=0o600)
        else:
            return output_preview(folder, request, dict(current, cancel_confirmed=current["state"] == "cancelled"))
    if action not in ("wait", "cancel"):
        raise ValueError("unknown action")
    deadline = time.monotonic() + min(max(int(request.get("timeout_seconds", 20)), 1), 60)
    while True:
        current = status(folder)
        if current["state"] not in ACTIVE or time.monotonic() >= deadline:
            return output_preview(folder, request, dict(current, wait_timed_out=current["state"] in ACTIVE,
                        cancel_confirmed=current["state"] == "cancelled"))
        time.sleep(.2)


def main():
    os.umask(0o077)
    if len(sys.argv) == 3 and sys.argv[1] == "worker":
        worker(Path(sys.argv[2]))
        return
    try:
        request = json.loads(base64.b64decode(sys.argv[1], validate=True))
        result = dispatch(request)
        # Do not repeat a potentially huge command in every poll response.
        if "command" in result:
            result["command"] = result["command"][:1000]
        print(json.dumps(result, ensure_ascii=False))
    except Exception as exc:
        print(json.dumps({"success": False, "error": type(exc).__name__, "reason": str(exc)}))
        sys.exit(1)


if __name__ == "__main__":
    main()

