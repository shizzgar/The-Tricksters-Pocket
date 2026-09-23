"""Offline controller contracts; mocked Frida, never connects to a device."""
import argparse
from contextlib import redirect_stdout, redirect_stderr
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import types
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("rebro", ROOT / "rebro.py")
rebro = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rebro)

class Tests(unittest.TestCase):
    def test_catalog_files_and_profile_selections(self):
        ids = [x["id"] for x in rebro.catalog()]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(set(ids), {p.stem for p in (ROOT / "agents").glob("*.js")})
        for path in (ROOT / "profiles").glob("*.json"):
            names, opts, limits = rebro.selection(str(path), None, None)
            self.assertTrue(set(names) <= set(ids))
            rebro.validate_limits(limits)

    def test_build_all_profiles_and_bridge_adapters_in_node(self):
        # Evaluate real generated bridge wrappers; Node is not GumJS/ART.
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "bridge.js"
            for mode, body in [
                ("auto", "var bridge = {perform: function () {}, available:true};"),
                ("global", "globalThis.Java = {perform: function () {}, available:true};"),
                ("auto", "module.exports = {perform: function () {}, available:true};"),
                ("auto", "var bridge = {default:{perform: function () {}, available:true}};"),
                ("expression", "({perform: function () {}, available:true});"),
            ]:
                p.write_text(body)
                c = {"bridge": str(p), "bridge_mode": mode}
                wrapper = rebro.bridge_source(c)
                subprocess.run(["node", "-e", wrapper + ";if(!Java.available)process.exit(1);"], check=True, capture_output=True)
                for profile in (ROOT / "profiles").glob("*.json"):
                    names, opts, limits = rebro.selection(str(profile), None, None)
                    source = rebro.build(c, names, opts, limits)
                    subprocess.run(["node", "--check"], input=source, text=True, check=True, capture_output=True)

    def test_unsupported_bridge_and_missing_bridge(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "bridge.js"
            for body in ["export default 1;", "import Java from 'frida-java-bridge';", "📦fixture"]:
                p.write_text(body)
                with self.assertRaises(rebro.PackError):
                    rebro.bridge_source({"bridge": str(p)})
        with self.assertRaises(rebro.PackError):
            rebro.bridge_source({})

    def test_required_options_and_bad_limits(self):
        with self.assertRaises(rebro.PackError):
            rebro.selection("native-survey", "native_trace", None)
        with self.assertRaises(rebro.PackError):
            rebro.selection("native-survey", "typo", None)
        for key, value in [("max_hooks", 1000), ("max_events", True), ("max_items", 0), ("backtrace", "yes")]:
            with self.assertRaises(rebro.PackError):
                rebro.validate_limits({**rebro.LIMITS, key: value})

    def test_file_pins_detect_tamper(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "bridge.js"
            path.write_text("original")
            c = {"bridge": str(path), "pins": {"bridge": rebro.sha(path)}}
            self.assertEqual(rebro.check_pins(c), [])
            path.write_text("changed")
            with self.assertRaises(rebro.PackError):
                rebro.check_pins(c)

    def test_option_schema_and_example_selections(self):
        for agent, filename in [('native_trace','native-trace'),('java_trace','java-trace'),
                                ('native_memory','memory'),('native_scan','scan'),('native_stalker_calls','stalker')]:
            names, _, _ = rebro.selection('native-survey', agent, ROOT/'examples'/(filename+'.options.json'))
            self.assertEqual(names, [agent])
        with tempfile.TemporaryDirectory() as td:
            p = Path(td)/'options.json'
            for opts in [{'module':'libc.so','symbl':'open'}, {'module':'libc.so','symbol':'open','offset':'0x1'},
                         {'module':'libc.so','offset':1}, {'module':'libc.so','offset':'bad'}]:
                p.write_text(json.dumps({'native_trace':opts}))
                with self.assertRaises(rebro.PackError):
                    rebro.selection('native-survey','native_trace',p)

    def test_native_build_needs_no_frida_or_bridge(self):
        names, opts, limits = rebro.selection("native-survey", None, None)
        with patch.object(rebro, "import_frida", side_effect=AssertionError("must not import")):
            source = rebro.build({}, names, opts, limits)
        self.assertIn("native_probe", source)

    def test_host_quota_boundaries_and_errors(self):
        with tempfile.TemporaryDirectory() as td:
            log = rebro.EventLog(Path(td) / "a", {**rebro.LIMITS, "max_events": 2})
            for _ in range(5):
                log.message({"type": "send", "payload": {"kind": "fixture"}})
            log.close()
            self.assertEqual(log.records, 2)
            self.assertEqual(log.reason, "host-quota")
            self.assertLessEqual((Path(td) / "a/events.jsonl").stat().st_size, rebro.LIMITS["max_bytes"])
            log = rebro.EventLog(Path(td) / "b", rebro.LIMITS)
            log.message({"type": "send", "payload": {"kind": "hook_error", "data": {"error": "bad export"}}})
            log.message({"type": "send", "payload": {"kind": "cleanup_error", "data": {"error": "restore"}}})
            self.assertTrue(log.stop.is_set())
            self.assertEqual(len(log.errors), 2)
            log.close()

    def test_timeout_requests_frida_cancellation(self):
        cancelled = threading.Event()
        class Cancellable:
            def __enter__(self): return self
            def __exit__(self, *a): pass
            def cancel(self): cancelled.set()
        fake = types.SimpleNamespace(Cancellable=Cancellable)
        with patch.dict("sys.modules", {"frida": fake}):
            with self.assertRaises(rebro.PackError):
                rebro.bounded_call(lambda: cancelled.wait(1), .005)
        self.assertTrue(cancelled.is_set())

    def session(self, td, fail_load=False, script_error=False, cleanup_error=False, spawn=False):
        calls = []
        class Script:
            def on(self, name, cb): self.cb = cb
            def load(self):
                calls.append("load")
                if fail_load: raise RuntimeError("load failed")
                self.cb({"type": "send", "payload": {"kind": "agent_status", "agent": "native_probe", "data": {"status": "active"}}})
                self.cb({"type": "send", "payload": {"kind": "ready"}})
                if script_error: self.cb({"type": "error", "description": "fixture script error"})
            def stop(self):
                calls.append("stop")
                if cleanup_error:
                    self.cb({"type": "send", "payload": {"kind": "cleanup_error", "data": {"error": "fixture"}}})
                return {"stopped": True, "dropped": 0}
            def unload(self): calls.append("unload")
            @property
            def exports_sync(self): return self
        class Session:
            def on(self, name, callback): self.detached_callback = callback
            def create_script(self, source, **kwargs):
                calls.append("create")
                assert kwargs["runtime"] == "qjs"
                return Script()
            def detach(self):
                calls.append("detach")
                self.detached_callback('application-requested', None)
        class Device:
            def spawn(self, argv): calls.append("spawn"); return 123
            def attach(self, target): calls.append("attach"); return Session()
            def resume(self, pid): calls.append("resume")
        args = argparse.Namespace(pid=None if spawn else 123, name=None, spawn="com.fixture" if spawn else None,
                                  duration=.02, startup_timeout=.01, output=td)
        names, opts, limits = rebro.selection("native-smoke", None, None)
        with patch.object(rebro, "fingerprint", return_value={"frida_version": "fixture"}), redirect_stdout(io.StringIO()):
            code = rebro.session_run({"endpoint": "127.0.0.1:27044"}, object(), Device(), args, names, opts, limits)
        summary = json.loads(next(Path(td).glob("*/summary.json")).read_text())
        return code, calls, summary

    def test_session_success_cleans_up_and_records_counters(self):
        with tempfile.TemporaryDirectory() as td:
            code, calls, summary = self.session(td)
            self.assertEqual(code, 0)
            self.assertEqual(calls[-3:], ["stop", "unload", "detach"])
            self.assertTrue(summary["ready"])
            self.assertEqual(summary['reason'], 'duration-complete')
            self.assertTrue(summary["agent_counters"]["stopped"])

    def test_spawn_load_failure_resumes_and_cleans_up(self):
        with tempfile.TemporaryDirectory() as td:
            code, calls, summary = self.session(td, fail_load=True, spawn=True)
            self.assertEqual(code, 2)
            self.assertEqual(calls.count("resume"), 1)
            self.assertEqual(calls[-3:], ["stop", "unload", "detach"])

    def test_runtime_and_cleanup_errors_produce_nonzero_exit(self):
        for kwargs in ({"script_error": True}, {"cleanup_error": True}):
            with tempfile.TemporaryDirectory() as td:
                code, _, summary = self.session(td, **kwargs)
                self.assertEqual(code, 2)
                self.assertTrue(summary["errors"])

if __name__ == "__main__":
    unittest.main(verbosity=2)
