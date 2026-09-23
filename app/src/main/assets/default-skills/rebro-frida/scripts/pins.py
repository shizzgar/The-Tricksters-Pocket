"""Verify explicit full SHA-256 pins; never accept current bytes as a new baseline."""
import argparse
import json
from pathlib import Path
import re
from common import atomic_json, digest, now


REQUIRED = {"frida_service", "frida_python_extension", "java_bridge"}


def verify(manifest):
    results = []
    items = manifest.get("artifacts", [])
    names = [x.get("id") for x in items]
    for name in sorted(REQUIRED - set(names)):
        results.append({"id": name, "status": "missing_entry"})
    if len(names) != len(set(names)):
        results.append({"id": "manifest", "status": "duplicate_ids"})
    for item in items:
        name, path, expected = item.get("id"), item.get("path"), item.get("sha256")
        row = {"id": name, "path": path, "expected_sha256": expected}
        if not isinstance(path, str) or not Path(path).is_absolute() or not isinstance(expected, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", expected):
            row["status"] = "unconfigured"
        else:
            try:
                row["actual_sha256"] = digest(path)
                row["status"] = "ok" if row["actual_sha256"] == expected.lower() else "mismatch"
            except OSError as e:
                row.update(status="unreadable", error=str(e))
        results.append(row)
    return {"schema": 1, "checked_at": now(), "ok": bool(results) and all(x["status"] == "ok" for x in results), "artifacts": results}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("manifest")
    p.add_argument("--out")
    a = p.parse_args()
    result = verify(json.loads(Path(a.manifest).read_text()))
    if a.out:
        atomic_json(a.out, result)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
