#!/usr/bin/env python3
"""Rebro Pack 1.0 — stdlib controller for an existing Frida installation."""
from __future__ import annotations
import argparse
from contextlib import nullcontext
import hashlib
import importlib
import json
import os
from pathlib import Path
import re
import signal
import sys
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parent
VERSION = "1.0.0"
DEFAULT_CONFIG = ROOT / "local.json"
LIMITS = {"max_events": 2000, "max_bytes": 2_000_000, "max_string": 256,
          "max_items": 100, "max_hooks": 64, "max_per_second": 100,
          "capture_strings": False, "backtrace": False}

class PackError(Exception):
    pass

def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def read_json(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise PackError(f"Cannot read JSON {path}: {e}") from e

def write_json(path, data):
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    temp = p.with_name(p.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        temp.write_text(json.dumps(data, ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf-8")
        os.chmod(temp, 0o600)
        os.replace(temp, p)
    finally:
        if temp.exists():
            temp.unlink()

def config(path):
    if not Path(path).exists():
        return {"endpoint": "127.0.0.1:27044", "bridge_mode": "auto", "pins": {}}
    c = read_json(path)
    if not isinstance(c, dict):
        raise PackError("Config must be an object")
    return c

def catalog():
    return read_json(ROOT / "catalog.json")

def selection(profile, agents, options_path):
    ppath = Path(profile)
    if not ppath.is_file():
        ppath = ROOT / "profiles" / (profile + ".json")
    data = read_json(ppath)
    opts = data.get("options", {})
    if options_path:
        extra = read_json(options_path)
        if not isinstance(extra, dict):
            raise PackError("Options file must be a JSON object keyed by agent ID")
        opts = {**opts, **extra}
    names = agents.split(",") if agents else data["agents"]
    names = list(dict.fromkeys(x.strip() for x in names if x.strip()))
    entries = {x["id"]: x for x in catalog()}
    option_specs = read_json(ROOT / 'options.json')
    if not names or any(n not in entries for n in names):
        raise PackError("Unknown/empty agents; use: python rebro.py catalog")
    if set(opts) - set(entries):
        raise PackError(f"Unknown agent option keys: {set(opts) - set(entries)}")
    for n in names:
        if not isinstance(opts.get(n, {}), dict):
            raise PackError(f"{n}: options must be an object")
        for key, value in opts.get(n, {}).items():
            rule = option_specs[n].get(key)
            if rule is None:
                raise PackError(f'{n}: unknown option {key}; see options.json')
            kind = rule['type']
            if (kind == 'string' and not isinstance(value, str)
                or kind == 'integer' and (isinstance(value, bool) or not isinstance(value, int))
                or kind == 'array' and (not isinstance(value, list) or any(not isinstance(x, str) for x in value))):
                raise PackError(f'{n}.{key}: expected {kind}')
            if kind == 'integer' and not rule.get('minimum', value) <= value <= rule.get('maximum', value):
                raise PackError(f'{n}.{key}: outside allowed range')
            if 'pattern' in rule and not re.fullmatch(rule['pattern'], value):
                raise PackError(f'{n}.{key}: invalid format')
        for key in entries[n].get("required", []):
            if opts.get(n, {}).get(key) in (None, "", []):
                raise PackError(f"{n}: required option '{key}' is missing")
        if n in ('native_trace', 'native_stalker_calls'):
            target = opts.get(n, {})
            if bool(target.get('symbol')) == bool(target.get('offset')):
                raise PackError(f'{n}: provide exactly one of symbol or offset')
    return names, opts, {**LIMITS, **data.get("limits", {})}

def bridge_source(c):
    path = c.get("bridge")
    if not path:
        raise PackError("Java agents need an external bridge. Run configure --bridge /absolute/path/bridge-final.js")
    p = Path(path)
    if not p.is_file():
        raise PackError(f"Bridge does not exist: {p}")
    source = p.read_text(encoding="utf-8-sig")
    if source.startswith("📦") or re.search(r"^\s*(import\s|export\s)", source, re.M):
        raise PackError("Bridge is ESM/a Frida bundle, not a plain script. See docs/BRIDGE.md; use a plain IIFE/global adapter.")
    mode = c.get("bridge_mode", "auto")
    if mode not in ("auto", "expression", "global"):
        raise PackError("Unknown bridge_mode")
    if mode == "expression":
        body = "return (" + source.rstrip().rstrip(";") + ");"
    else:
        body = source + """
;return (typeof Java !== 'undefined' ? Java :
         typeof bridge !== 'undefined' ? bridge :
         globalThis.Java || (module.exports && (module.exports.default || module.exports)));
"""
    # Scope the bridge once, in the same Script runtime as the agents.
    return """
globalThis.Java = (function() {
var global = globalThis;
var module = {exports:{}};
var exports = module.exports;
""" + body + """
})();
if (globalThis.Java && !globalThis.Java.perform && globalThis.Java.default)
  globalThis.Java = globalThis.Java.default;
if (!globalThis.Java || typeof globalThis.Java.perform !== 'function')
  throw new Error('Bridge did not expose Java.perform. Read docs/BRIDGE.md; do not install another bridge automatically.');
"""

def validate_limits(limits):
    for key in ("max_events", "max_bytes", "max_string", "max_items", "max_hooks", "max_per_second"):
        value = limits.get(key)
        if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
            raise PackError(f"Invalid positive integer limit: {key}")
    if limits["max_string"] > 4096 or limits["max_items"] > 2000 or limits["max_hooks"] > 512:
        raise PackError("Limits exceed pack bounds (string 4096/items 2000/hooks 512)")
    for key in ("capture_strings", "backtrace"):
        if not isinstance(limits[key], bool):
            raise PackError(f"{key} must be boolean")

def build(c, names, opts, limits):
    validate_limits(limits)
    entries = {x["id"]: x for x in catalog()}
    pieces = []
    java = any(entries[n]["java"] for n in names)
    if java:
        pieces.append(bridge_source(c))
    settings = {"agents": names, "options": opts, "limits": limits, "version": VERSION}
    pieces.append("globalThis.REBRO_CONFIG = " + json.dumps(settings, ensure_ascii=True) + ";")
    pieces.append((ROOT / "runtime.js").read_text())
    for n in names:
        pieces.append("\n// agent: " + n + "\n" + (ROOT / "agents" / (n + ".js")).read_text())
    pieces.append("\nRebro.start();\n")
    return "\n".join(pieces)

def import_frida():
    try:
        return importlib.import_module("frida")
    except Exception as e:
        raise PackError(f"Existing Frida binding cannot load: {e}. Use baseline Python; this pack never installs Frida.") from e

def fingerprint(frida):
    info = {"python": sys.executable, "python_version": sys.version.split()[0],
            "frida_version": getattr(frida, "__version__", None), "frida_file": getattr(frida, "__file__", None)}
    try:
        native = importlib.import_module("frida._frida")
        info["binding"] = str(Path(native.__file__).resolve())
        info["binding_sha256"] = sha(info["binding"])
    except Exception as e:
        info["binding_error"] = str(e)
    return info

def check_pins(c, frida=None):
    pins = c.get("pins", {})
    if not pins:
        return ["baseline is not pinned; configure on the phone first"]
    issues = []
    if pins.get("binding"):
        current = fingerprint(frida or import_frida())
        if current.get("binding_sha256") != pins["binding"]:
            raise PackError("Baseline mismatch: loaded Frida native binding hash changed")
    for key in ("bridge", "server_binary"):
        if pins.get(key):
            path = c.get(key)
            if not path or not Path(path).is_file():
                raise PackError(f"Pinned {key} is unavailable: {path}")
            if sha(path) != pins[key]:
                raise PackError(f"Baseline mismatch: {key} hash changed")
    if c.get("server_binary"):
        issues.append("server binary hash covers this file, not proof of the running PID's executable")
    return issues

def bounded_call(fn, seconds=5):
    result = []
    done = threading.Event()
    module = sys.modules.get('frida')
    cancellable = module.Cancellable() if module and hasattr(module, 'Cancellable') else None
    def call():
        try:
            with cancellable if cancellable is not None else nullcontext():
                result.append((True, fn()))
        except BaseException as e:
            result.append((False, e))
        finally:
            done.set()
    threading.Thread(target=call, daemon=True).start()
    if not done.wait(seconds):
        if cancellable is not None:
            cancellable.cancel()
            done.wait(0.5)
        raise PackError(f"Operation exceeded {seconds}s; cancellation requested where supported")
    ok, value = result[0]
    if not ok:
        raise value
    return value

def connect(frida, c):
    # API present in the user's binding generation; no usb discovery.
    manager = frida.get_device_manager()
    return bounded_call(lambda: manager.add_remote_device(c.get("endpoint", "127.0.0.1:27044")), 8)

class EventLog:
    def __init__(self, directory, limits):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=False)
        os.chmod(self.directory, 0o700)
        self.file = open(self.directory / "events.jsonl", "x", encoding="utf-8")
        os.chmod(self.directory / "events.jsonl", 0o600)
        self.limits = limits
        self.lock = threading.RLock()
        self.stop = threading.Event()
        self.ready = threading.Event()
        self.records = 0
        self.bytes = 0
        self.errors = []
        self.statuses = {}
        self.reason = None
        self.closed = False

    def message(self, message, data=None):
        with self.lock:
            payload = message.get('payload', {}) if message.get('type') == 'send' else {}
            kind = payload.get('kind') if isinstance(payload, dict) else None
            if self.closed or (self.stop.is_set() and kind != 'cleanup_error'):
                return
            record = {"host_time": time.time(), "message": message}
            if data:
                # Current agents emit bounded JSON only; do not write arbitrary binary blobs.
                record["binary_omitted_bytes"] = len(data)
            payload = message.get("payload", {}) if message.get("type") == "send" else {}
            if message.get("type") == "error":
                self.errors.append(str(message.get("description", "script error"))[:2000])
                self.reason = "script-error"
                self.stop.set()
            if isinstance(payload, dict):
                if kind in ('hook_error', 'observer_error', 'jni_read_error', 'cleanup_error'):
                    self.errors.append(str(payload)[:2000])
                    self.reason = self.reason or 'agent-error'
                    self.stop.set()
                if payload.get("kind") == "agent_status":
                    self.statuses[payload.get("agent", "?")] = payload.get("data", {})
                if payload.get("kind") == "ready":
                    self.ready.set()
                if payload.get("kind") == "quota":
                    self.reason = "agent-quota"
                    self.stop.set()
            line = json.dumps(record, ensure_ascii=True, default=str) + "\n"
            size = len(line.encode("utf-8"))
            if self.records >= self.limits["max_events"] or self.bytes + size > self.limits["max_bytes"]:
                self.reason = "host-quota"
                self.stop.set()
                return
            self.file.write(line)
            self.file.flush()
            self.records += 1
            self.bytes += size

    def close(self):
        with self.lock:
            self.closed = True
            self.file.close()

def session_run(c, frida, device, args, names, opts, limits):
    check_pins(c, frida)
    source = build(c, names, opts, limits)
    now = time.strftime("%Y%m%d-%H%M%S")
    out = Path(args.output) / (now + "-" + uuid.uuid4().hex[:8])
    log = EventLog(out, limits)
    meta = {"pack_version": VERSION, "endpoint": c.get("endpoint"), "agents": names, "options": opts,
            "limits": limits, "duration": args.duration, "source_sha256": hashlib.sha256(source.encode()).hexdigest(),
            "client": fingerprint(frida), "bridge_sha256": sha(c["bridge"]) if c.get("bridge") else None,
            "started_at": time.time(), "target": {"pid": args.pid, "name": args.name, "spawn": args.spawn},
            "verification": "device-run"}
    write_json(out / "session.json", meta)
    session = script = None
    spawned = None
    resumed = False
    cleanup_errors = []
    agent_counters = None
    exit_code = 0
    old_sig = None
    closing = False
    try:
        if threading.current_thread() is threading.main_thread():
            def interrupted(*_):
                log.reason = 'interrupted'
                log.stop.set()
            old_sig = signal.signal(signal.SIGINT, interrupted)
        if args.spawn:
            spawned = bounded_call(lambda: device.spawn([args.spawn]), 15)
            target = spawned
        elif args.pid:
            target = args.pid
        else:
            target = args.name
        session = bounded_call(lambda: device.attach(target), 15)
        def detached(*event):
            if closing:
                return
            log.reason = log.reason or "detached"
            log.errors.append('Target detached before cleanup: ' + str(event)[:1000])
            log.stop.set()
        session.on("detached", detached)
        script = bounded_call(lambda: session.create_script(source, name="rebro-pack", runtime="qjs"), 10)
        script.on("message", log.message)
        bounded_call(script.load, 10)
        if spawned is not None:
            bounded_call(lambda: device.resume(spawned), 5)
            resumed = True
        deadline = time.monotonic() + args.duration
        startup_deadline = min(deadline, time.monotonic() + args.startup_timeout)
        while not log.stop.is_set() and time.monotonic() < deadline:
            if not log.ready.is_set() and time.monotonic() >= startup_deadline:
                log.errors.append("Agent readiness timed out; inspect bridge, ClassLoader and events")
                log.reason = "startup-timeout"
                exit_code = 2
                break
            log.stop.wait(0.1)
        if not log.ready.is_set() and not log.errors:
            log.errors.append("No ready event received")
            exit_code = 2
        failed = [n for n in names if log.statuses.get(n, {}).get("status") != "active"]
        if failed:
            log.errors.append("Inactive/failed agents: " + ", ".join(failed))
            exit_code = 2
        if log.errors:
            exit_code = 2
    except Exception as e:
        log.errors.append(str(e))
        log.reason = log.reason or "controller-error"
        exit_code = 2
    finally:
        closing = True
        # Never kill/restart the existing server or any target.
        if spawned is not None and not resumed:
            try:
                bounded_call(lambda: device.resume(spawned), 3)
            except Exception as e:
                cleanup_errors.append("resume spawned process: " + str(e))
        if script is not None:
            try:
                agent_counters = bounded_call(lambda: script.exports_sync.stop(), 3)
            except Exception as e:
                cleanup_errors.append("agent stop: " + str(e))
            try:
                bounded_call(script.unload, 3)
            except Exception as e:
                cleanup_errors.append("unload: " + str(e))
        if session is not None:
            try:
                bounded_call(session.detach, 3)
            except Exception as e:
                cleanup_errors.append("detach: " + str(e))
        if old_sig is not None:
            signal.signal(signal.SIGINT, old_sig)
        log.close()
    summary = {"exit_code": exit_code, "reason": log.reason or "duration-complete", "events": log.records,
               "bytes": log.bytes, "ready": log.ready.is_set(), "agents": log.statuses,
               "agent_counters": agent_counters,
               "errors": log.errors, "cleanup_errors": cleanup_errors, "finished_at": time.time()}
    if cleanup_errors or log.errors:
        summary["exit_code"] = exit_code = 2
    write_json(out / "summary.json", summary)
    print(json.dumps({"session": str(out.resolve()), **summary}, ensure_ascii=False, indent=2))
    return exit_code

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default=str(DEFAULT_CONFIG))
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("catalog")
    p = sub.add_parser("configure")
    p.add_argument("--bridge", type=Path)
    p.add_argument("--bridge-mode", choices=["auto", "global", "expression"], default="auto")
    p.add_argument("--server-binary", type=Path)
    p.add_argument("--endpoint", default="127.0.0.1:27044")
    p.add_argument("--offline", action="store_true", help="Record paths only; binding will remain unpinned")
    p = sub.add_parser("doctor")
    p.add_argument("--online", action="store_true")
    sub.add_parser("ps")
    for command in ("build", "run", "smoke"):
        p = sub.add_parser(command)
        p.add_argument("--profile", default="native-survey")
        p.add_argument("--agents", help="Comma-separated IDs; overrides profile selection")
        p.add_argument("--options", type=Path)
        if command == "build":
            p.add_argument("--out", required=True, type=Path)
        else:
            t = p.add_mutually_exclusive_group(required=True)
            t.add_argument("--pid", type=int)
            t.add_argument("--name")
            if command == "run":
                t.add_argument("--spawn")
            else:
                p.set_defaults(spawn=None)
            p.add_argument("--duration", type=float, default=30 if command == "run" else 5)
            p.add_argument("--startup-timeout", type=float, default=10)
            p.add_argument("--output", default=str(ROOT / "runs"))
    args = parser.parse_args(argv)
    try:
        if args.command == "catalog":
            print(json.dumps(catalog(), ensure_ascii=False, indent=2))
            return 0
        c = config(args.config)
        if args.command == "configure":
            new = {"endpoint": args.endpoint, "bridge_mode": args.bridge_mode, "pins": {}, "pack_version": VERSION}
            for key in ("bridge", "server_binary"):
                value = getattr(args, key)
                if value:
                    path = value.expanduser().resolve(strict=True)
                    new[key] = str(path)
                    new["pins"][key] = sha(path)
            if args.bridge:
                bridge_source(new)
            if not args.offline:
                fp = fingerprint(import_frida())
                if "binding_sha256" not in fp:
                    raise PackError("Cannot pin native binding: " + str(fp))
                new["pins"]["binding"] = fp["binding_sha256"]
                new["client_at_pin"] = fp
            write_json(args.config, new)
            print(json.dumps({"config": str(Path(args.config).resolve()), **new}, ensure_ascii=False, indent=2))
            return 0
        if args.command == "build":
            check_pins(c) if c.get("pins") else None
            names, opts, limits = selection(args.profile, args.agents, args.options)
            source = build(c, names, opts, limits)
            args.out.parent.mkdir(parents=True, exist_ok=True)
            # Avoid overwriting any existing user file / pinned component.
            with open(args.out, "x", encoding="utf-8") as f:
                f.write(source)
            print(json.dumps({"out": str(args.out.resolve()), "agents": names, "sha256": sha(args.out)}))
            return 0
        frida = import_frida()
        warnings = check_pins(c, frida)
        if args.command == "doctor":
            result = {"pack_version": VERSION, "client": fingerprint(frida), "config": c, "warnings": warnings}
            if args.online:
                result["server_parameters"] = bounded_call(lambda: connect(frida, c).query_system_parameters(), 10)
            print(json.dumps(result, ensure_ascii=False, indent=2, default=str))
            return 0
        for warning in warnings:
            print("NOTE: " + warning, file=sys.stderr)
        device = connect(frida, c)
        if args.command == "ps":
            ps = bounded_call(device.enumerate_processes, 10)
            print(json.dumps([{"pid": x.pid, "name": x.name} for x in ps], ensure_ascii=False, indent=2))
            return 0
        if not (0 < args.duration <= 3600) or not (0 < args.startup_timeout <= 60):
            raise PackError("duration must be 0..3600s; startup-timeout 0..60s")
        if args.pid is not None and args.pid <= 0:
            raise PackError("PID must be positive")
        if args.command == "smoke":
            codes = []
            if not c.get('bridge'):
                print('NOTE: No bridge configured; Java smoke will NOT run.', file=sys.stderr)
            for phase in (["native-smoke", "java-smoke"] if c.get("bridge") else ["native-smoke"]):
                names, opts, limits = selection(phase, None, None)
                print("PHASE " + phase, file=sys.stderr)
                codes.append(session_run(c, frida, device, args, names, opts, limits))
                if codes[-1]:
                    break
            return max(codes)
        names, opts, limits = selection(args.profile, args.agents, args.options)
        return session_run(c, frida, device, args, names, opts, limits)
    except (PackError, OSError, ValueError) as e:
        print("ERROR: " + str(e), file=sys.stderr)
        return 2
    except Exception as e:
        print(f"ERROR ({type(e).__name__}): {e}", file=sys.stderr)
        return 2

if __name__ == "__main__":
    raise SystemExit(main())
