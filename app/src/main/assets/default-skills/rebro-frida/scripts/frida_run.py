"""Use the existing remote Frida service. Never starts, replaces or stops it."""
import argparse
import json
import os
from pathlib import Path
import threading
import time
from common import digest, now


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--endpoint", default="127.0.0.1:27044")
    p.add_argument("--health", action="store_true", help="Metadata and process count only, no attach")
    p.add_argument("--pid", type=int)
    p.add_argument("--agent", help="Reviewed plain JS or a compiled bundle")
    p.add_argument("--out", help="NEW JSONL file")
    p.add_argument("--seconds", type=float, default=15)
    p.add_argument("--max-bytes", type=int, default=16 * 1024 ** 2)
    p.add_argument("--expect-kind", help="Require a send payload with this kind; not a generic correctness proof")
    a = p.parse_args()
    if not a.health and (not a.pid or a.pid < 1 or not a.agent or not a.out):
        p.error("--pid, --agent and --out required for attach")
    if a.seconds <= 0 or a.max_bytes < 4096:
        p.error("Invalid limits")
    os.umask(0o077)
    import frida  # Deliberately use the existing pinned installation.
    device = frida.get_device_manager().add_remote_device(a.endpoint)
    if a.health:
        start = time.monotonic()
        parameters = device.query_system_parameters()
        processes = device.enumerate_processes()
        print(json.dumps({"observed_at": now(), "client_version": frida.__version__,
            "endpoint": a.endpoint, "system_parameters": parameters,
            "process_count": len(processes), "elapsed_ms": round((time.monotonic()-start)*1000, 1)}, default=str))
        return 0
    source = Path(a.agent).read_text(encoding="utf-8")
    stop, mutex = threading.Event(), threading.Lock()
    observed = set()
    error = [None]
    closing = [False]
    session = script = None
    used = [0]
    with open(a.out, "x", encoding="utf-8") as output:
        def emit(event, **fields):
            line = json.dumps({"at": now(), "event": event, **fields}, ensure_ascii=False, default=str) + "\n"
            with mutex:
                if output.closed:
                    return
                size = len(line.encode("utf-8"))
                if used[0] + size > a.max_bytes:
                    error[0] = "output_limit"
                    stop.set()
                    return
                output.write(line)
                output.flush()
                used[0] += size

        def on_message(message, data):
            payload = message.get("payload")
            if message.get("type") == "send" and isinstance(payload, dict):
                kind = payload.get("kind")
                if isinstance(kind, str):
                    observed.add(kind)
                if kind == "probe-error":
                    error[0] = str(payload.get("error", "probe_error"))
                    stop.set()
            emit("message", message=message, binary_bytes=len(data) if data else 0)
            if message.get("type") == "error":
                error[0] = "script_error"
                stop.set()

        def detached(reason, crash=None):
            emit("detached", reason=reason, crash=crash)
            if not closing[0]:
                error[0] = "unexpected_detach"
                stop.set()

        try:
            emit("start", endpoint=a.endpoint, pid=a.pid, agent=str(Path(a.agent).resolve()),
                 agent_sha256=digest(a.agent), client_version=frida.__version__)
            session = device.attach(a.pid)
            session.on("detached", detached)
            script = session.create_script(source, name=Path(a.agent).name)
            script.on("message", on_message)
            script.load()
            stop.wait(a.seconds)
            if a.expect_kind and a.expect_kind not in observed:
                error[0] = error[0] or "expected_event_missing"
        except Exception as e:
            error[0] = type(e).__name__ + ": " + str(e)
            emit("error", error=error[0])
        finally:
            closing[0] = True
            for obj, method in ((script, "unload"), (session, "detach")):
                if obj is not None:
                    try:
                        getattr(obj, method)()
                    except Exception as e:
                        error[0] = error[0] or "cleanup_failed"
                        emit("cleanup_error", operation=method, error=str(e))
            emit("finish", ok=error[0] is None, error=error[0], observed_kinds=sorted(observed))
    print(json.dumps({"out": a.out, "ok": error[0] is None, "error": error[0]}, ensure_ascii=False))
    return 0 if error[0] is None else 70


if __name__ == "__main__":
    raise SystemExit(main())
