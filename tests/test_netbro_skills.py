"""Offline contract tests for bundled NetBro helpers; no network scans."""
import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/default-skills"
WORKFLOW = ASSETS / "netbro-workflow/scripts"
PREFLIGHT = ASSETS / "netbro-environment/scripts/preflight.py"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


summary = load("netbro_summary", WORKFLOW / "summarize.py")
scope = load("netbro_scope", WORKFLOW / "scope_filter.py")
preflight = load("netbro_preflight", PREFLIGHT)


class NetbroHelpersTest(unittest.TestCase):
    def setUp(self):
        directory = ROOT / "build/netbro-tests"
        directory.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=directory)
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def file(self, name, content):
        path = self.directory / name
        path.write_text(content)
        return path

    def jsonl(self, *records):
        return self.file("results.jsonl", "".join(json.dumps(item) + "\n" for item in records))

    def cli(self, script, *args):
        return subprocess.run([sys.executable, "-B", str(script), *map(str, args)],
                              cwd=self.directory, capture_output=True, text=True, timeout=5)

    def test_catalog_and_manifests_are_reproducible(self):
        result = self.cli(ROOT / "scripts/update_netbro_catalog.py", "--check")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_skill_frontmatter_and_relative_resources_resolve(self):
        import re
        packages = list(ASSETS.glob("netbro-*"))
        self.assertEqual(6, len(packages))
        for directory in packages:
            content = (directory / "SKILL.md").read_text()
            self.assertTrue(content.startswith("---\nname: " + directory.name + "\n"))
            self.assertIn("\ndescription: ", content)
            self.assertNotIn("auto_load: true", content)
            for path in directory.rglob("*.md"):
                for link in re.findall(r"\]\(([^)]+)\)", path.read_text()):
                    if "://" not in link:
                        self.assertTrue((path.parent / link).is_file(), (path, link))

    def test_nmap_states_extraports_and_completion_remain_distinct(self):
        xml = self.file("nmap.xml", """<?xml version="1.0"?><!DOCTYPE nmaprun><nmaprun>
        <host><status state="up"/><address addr="192.0.2.10" addrtype="ipv4"/><ports>
        <extraports state="closed" count="97"/>
        <port protocol="tcp" portid="22"><state state="open"/><service name="ssh" product="Fixture" version="1"/></port>
        <port protocol="udp" portid="53"><state state="open|filtered"/></port>
        <port protocol="tcp" portid="443"><state state="filtered"/></port>
        </ports></host><runstats><finished exit="success"/></runstats></nmaprun>""")
        report = summary.summarize("nmap", xml, limit=1)
        self.assertEqual({"open": 1, "open|filtered": 1, "filtered": 1, "closed": 97}, report["port_states"])
        self.assertEqual({"up": 1}, report["hosts"])
        self.assertEqual("success", report["scan_completion"])
        self.assertEqual(1, len(report["preview"]))
        self.assertTrue(report["preview_truncated"])

    def test_nmap_missing_finish_is_unknown(self):
        report = summary.summarize("nmap", self.file("empty.xml", "<nmaprun/>"))
        self.assertEqual("not_recorded", report["scan_completion"])

    def test_truncated_nmap_is_error_not_empty_success(self):
        result = self.cli(WORKFLOW / "summarize.py", "nmap", self.file("bad.xml", "<nmaprun><host>"))
        self.assertEqual(2, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertIn("incomplete", result.stderr)

    def test_xml_entity_declarations_and_non_utf8_are_rejected(self):
        text = '<!DOCTYPE nmaprun [<!ENTITY item "expanded">]><nmaprun>&item;</nmaprun>'
        path = self.file("entity.xml", text)
        with self.assertRaisesRegex(ValueError, "entity"):
            summary.summarize("nmap", path)
        path.write_bytes(text.encode("utf-16"))
        with self.assertRaisesRegex(ValueError, "UTF-8"):
            summary.summarize("nmap", path)

    def test_bbot_legacy_and_current_structured_records_are_counted_without_dumping_payloads(self):
        path = self.jsonl(
            {"type": "DNS_NAME", "data": "app.example.test", "scope_distance": 0},
            {"type": "FINDING", "data": {"description": "private-old"}, "scope_distance": 1},
            {"type": "FINDING", "data_json": {"description": "private-new"}, "scope_distance": 0},
        )
        report = summary.summarize("bbot", path)
        self.assertEqual({"DNS_NAME": 1, "FINDING": 2}, report["counts"])
        self.assertEqual({"in_scope": 2, "other_or_unknown": 1}, report["states"])
        self.assertNotIn("private-", json.dumps(report))

    def test_nuclei_report_omits_raw_requests_extracted_secrets_and_url_credentials(self):
        path = self.jsonl({
            "template-id": "fixture", "info": {"severity": "medium"},
            "matched-at": "https://user:private-password@app.example.test/path?token=private-token#private-fragment",
            "request": "private-request", "response": "private-response", "extracted-results": ["private-extracted"],
        })
        result = self.cli(WORKFLOW / "summarize.py", "nuclei", path)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("private-", result.stdout)
        self.assertEqual("https://app.example.test/path", json.loads(result.stdout)["preview"][0]["endpoint"])

    def test_legba_does_not_print_credentials_and_preserves_partial_state(self):
        path = self.jsonl(
            {"plugin": "ssh", "target": "127.0.0.1:2222", "partial": False, "data": {"username": "private-user", "password": "private-password"}},
            {"plugin": "http.basic", "target": "https://example.test/?token=private-token", "partial": True, "data": {"password": "private-password"}},
        )
        result = self.cli(WORKFLOW / "summarize.py", "legba", path)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("private-", result.stdout)
        self.assertEqual({"full_match": 1, "partial": 1}, json.loads(result.stdout)["states"])

    def test_empty_jsonl_does_not_claim_successful_scan(self):
        report = summary.summarize("nuclei", self.file("empty.jsonl", ""))
        self.assertEqual(0, report["records"])
        self.assertEqual("not_recorded_in_jsonl", report["scan_completion"])

    def test_jsonl_bad_line_is_reported_without_copying_its_secret(self):
        result = self.cli(WORKFLOW / "summarize.py", "legba", self.file("bad.jsonl", '{"password":"private-secret"'))
        self.assertEqual(2, result.returncode)
        self.assertNotIn("private-secret", result.stderr)
        self.assertIn("line 1", result.stderr)

    def test_oversized_result_and_line_are_rejected(self):
        path = self.jsonl({"plugin": "ssh", "target": "localhost", "data": {"password": "a" * 200}})
        with self.assertRaisesRegex(ValueError, "byte limit"):
            summary.summarize("legba", path, max_bytes=100)
        with patch.object(summary, "MAX_LINE", 100):
            with self.assertRaisesRegex(ValueError, "line limit"):
                summary.summarize("legba", path)

    def test_many_categories_have_bounded_output_without_losing_record_count(self):
        path = self.jsonl(*({"template-id": "fixture-" + str(i), "info": {"severity": "info"}} for i in range(105)))
        report = summary.summarize("nuclei", path, limit=0)
        self.assertEqual(105, report["records"])
        self.assertEqual(105, report["distinct_categories"])
        self.assertEqual(100, len(report["counts"]))
        self.assertTrue(report["counts_truncated"])

    def test_scope_matches_host_boundaries_and_explicit_subdomains(self):
        allowed = [scope.rule("app.example.test"), scope.rule("*.lab.example.test")]
        candidates = ["app.example.test", "APP.EXAMPLE.TEST.", "https://x.lab.example.test/a", "lab.example.test", "evilapp.example.test", "app.example.test.attacker.test"]
        kept, rejected = scope.filter_candidates(candidates, allowed, [])
        self.assertEqual(candidates[:3], kept)
        self.assertEqual(3, rejected)

    def test_scope_cidr_ipv6_and_exclusion_precedence(self):
        allowed = [scope.rule("192.0.2.0/28"), scope.rule("2001:db8::/126")]
        denied = [scope.rule("192.0.2.3")]
        kept, rejected = scope.filter_candidates(["192.0.2.2", "192.0.2.3", "192.0.2.16", "https://[2001:db8::2]:8443/a"], allowed, denied)
        self.assertEqual(["192.0.2.2", "https://[2001:db8::2]:8443/a"], kept)
        self.assertEqual(2, rejected)
        self.assertFalse(scope.matches("app.example.test", allowed))

    def test_scope_rejects_command_options_credentials_and_ambiguous_addresses(self):
        self.assertEqual("0xsecurity.example.test", scope.host("0xsecurity.example.test"))
        for candidate in ["--script=all", "app.example.test other.test", "https://user:secret@app.example.test/", "0177.0.0.1", "2130706433", "0x7f000001", "app.example.test..", "https://app.example.test:bad", "ftp://app.example.test", "host:22"]:
            with self.subTest(candidate=candidate), self.assertRaises(ValueError):
                scope.host(candidate)

    def test_scope_output_is_private_exclusive_and_validation_precedes_writing(self):
        rules = self.file("scope.txt", "app.example.test\n")
        candidates = self.file("targets.txt", "https://app.example.test/a\n")
        output = self.directory / "selected.txt"
        args = ["--scope", rules, "--input", candidates, "--output", output]
        result = self.cli(WORKFLOW / "scope_filter.py", *args)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(0o600, stat.S_IMODE(output.stat().st_mode))
        self.assertEqual("https://app.example.test/a\n", output.read_text())
        self.assertEqual(2, self.cli(WORKFLOW / "scope_filter.py", *args).returncode)
        output.unlink()
        candidates.write_text("app.example.test\n--script=all\n")
        self.assertEqual(2, self.cli(WORKFLOW / "scope_filter.py", *args).returncode)
        self.assertFalse(output.exists())

    def executable(self, code):
        path = self.file("fake binary", "#!" + sys.executable + "\n" + code)
        path.chmod(0o700)
        return str(path)

    def test_preflight_only_runs_expected_version_argument(self):
        executable = self.executable("import sys\nassert sys.argv[1:] == ['-version']\nprint('fixture nuclei 1')\n")
        with patch.object(preflight.shutil, "which", return_value=executable):
            result = preflight.probe("nuclei", 2)
        self.assertTrue(result["version_ok"])
        self.assertEqual("fixture nuclei 1", result["version_output"])

    def test_preflight_missing_and_nonzero_do_not_claim_ready(self):
        with patch.object(preflight.shutil, "which", return_value=None):
            self.assertEqual("missing", preflight.probe("bbot", 1)["status"])
        executable = self.executable("import sys\nprint('fixture load failure')\nsys.exit(7)\n")
        with patch.object(preflight.shutil, "which", return_value=executable):
            result = preflight.probe("legba", 2)
        self.assertFalse(result["version_ok"])
        self.assertEqual(7, result["exit_code"])

    def test_preflight_bounds_output_and_enforces_timeout(self):
        executable = self.executable("print('x' * 20000)\n")
        with patch.object(preflight.shutil, "which", return_value=executable):
            result = preflight.probe("nmap", 2)
        self.assertTrue(result["output_truncated"])
        self.assertEqual(preflight.OUTPUT_LIMIT, len(result["version_output"]))
        executable = self.executable("import time\ntime.sleep(10)\n")
        started = time.monotonic()
        with patch.object(preflight.shutil, "which", return_value=executable):
            result = preflight.probe("bbot", 0.15)
        self.assertLess(time.monotonic() - started, 3)
        self.assertEqual("timeout", result["status"])
        self.assertFalse(result["version_ok"])

    def test_all_helpers_have_runnable_help(self):
        for script in [PREFLIGHT, WORKFLOW / "summarize.py", WORKFLOW / "scope_filter.py"]:
            result = self.cli(script, "--help")
            self.assertEqual(0, result.returncode, (script, result.stderr))
            self.assertIn("usage:", result.stdout)


if __name__ == "__main__":
    unittest.main()
