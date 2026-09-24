#!/usr/bin/env python3
"""Regenerate deterministic hashes for the app's bundled NetBro skill packages."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
PACKAGES = {
    "netbro-workflow": "Сетевое исследование и evidence",
    "netbro-environment": "Среда и установка инструментов",
    "netbro-bbot": "BBOT: разведка и события",
    "netbro-nmap": "Nmap: хосты, порты и сервисы",
    "netbro-nuclei": "Nuclei: шаблоны и находки",
    "netbro-legba": "Legba: протоколы и аутентификация",
}


def render():
    generated = {}
    catalog = []
    for name, title in PACKAGES.items():
        directory = ASSETS / "default-skills" / name
        files = {}
        for path in sorted(directory.rglob("*")):
            if path.is_symlink():
                raise ValueError("Symlinks are not packaged: " + str(path))
            if not path.is_file() or path.name == "MANIFEST.sha256":
                continue
            if "__pycache__" in path.parts or path.suffix == ".pyc":
                raise ValueError("Remove generated Python bytecode: " + str(path))
            files[path.relative_to(directory).as_posix()] = path.read_bytes()
        if "SKILL.md" not in files:
            raise ValueError("Missing SKILL.md: " + name)
        manifest = "".join(hashlib.sha256(data).hexdigest() + "  " + path + "\n" for path, data in files.items()).encode()
        generated[directory / "MANIFEST.sha256"] = manifest
        catalog.append({
            "name": name, "title": title, "files": len(files) + 1,
            "bytes": sum(map(len, files.values())) + len(manifest),
            "manifest_sha256": hashlib.sha256(manifest).hexdigest(),
        })
    generated[ASSETS / "assistant-presets/netbro/catalog.json"] = (
        json.dumps({"schema_version": 1, "release": "1.0.0", "packages": catalog}, ensure_ascii=False, indent=2) + "\n"
    ).encode()
    return generated


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    changed = []
    for path, content in render().items():
        if not path.exists() or path.read_bytes() != content:
            changed.append(path.relative_to(ROOT).as_posix())
            if not args.check:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
    if changed:
        print("\n".join(changed))
    else:
        print("NetBro package manifests and catalog are current.")
    return 1 if args.check and changed else 0


if __name__ == "__main__":
    raise SystemExit(main())
