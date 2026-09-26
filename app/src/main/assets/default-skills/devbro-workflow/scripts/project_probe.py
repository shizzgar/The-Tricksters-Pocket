#!/usr/bin/env python3
"""Bounded project inventory. No subprocesses, installs or project-code execution."""
import argparse
import hashlib
import json
from pathlib import Path

MANIFESTS = {
    'pyproject.toml': 'Python project metadata and tool configuration',
    'requirements.txt': 'Python dependencies; inspect before installing',
    'package.json': 'Node scripts/dependencies; inspect script hooks',
    'Cargo.toml': 'Rust package and workspace',
    'go.mod': 'Go module and runtime requirement',
    'build.gradle.kts': 'Gradle Kotlin build; inspect wrapper and settings',
    'build.gradle': 'Gradle build; inspect wrapper and settings',
    'settings.gradle.kts': 'Gradle module graph',
    'pom.xml': 'Maven project',
    'CMakeLists.txt': 'C/C++ build definition',
    'Makefile': 'Build targets; read before running',
    'AGENTS.md': 'Repository operating instructions',
    'README.md': 'Project usage and setup',
}
EXCLUDED = {'.git', '.gradle', '.venv', 'venv', 'node_modules', 'vendor', 'build', 'dist', '__pycache__'}


def probe(root):
    root = Path(root).resolve(strict=True)
    if not root.is_dir():
        raise ValueError('root must be a directory')
    manifests = []
    scanned = 0
    truncated = False
    pending = [(root, 0)]
    while pending and scanned < 1000:
        directory, depth = pending.pop()
        try:
            entries = sorted(directory.iterdir(), key=lambda p: p.name)
        except OSError:
            continue
        for entry in entries:
            scanned += 1
            if scanned > 1000:
                truncated = True
                break
            if entry.is_symlink():
                continue
            if entry.name in MANIFESTS and entry.is_file():
                size = entry.stat().st_size
                manifests.append({'path': str(entry.relative_to(root)), 'purpose': MANIFESTS[entry.name],
                                  'size': size, 'sha256': hashlib.sha256(entry.read_bytes()).hexdigest() if size <= 1048576 else None})
            if depth < 2 and entry.is_dir() and entry.name not in EXCLUDED and not entry.name.startswith('.'):
                pending.append((entry, depth + 1))
    return {'root': str(root), 'manifests': manifests, 'bounded_to_depth': 3,
            'truncated': truncated or bool(pending), 'project_code_executed': False,
            'note': 'Read project instructions and manifests before choosing checks; this inventory is not a test.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root')
    args = parser.parse_args()
    try:
        print(json.dumps(probe(args.root), ensure_ascii=False, indent=2))
    except (OSError, ValueError) as error:
        print(json.dumps({'error': str(error)}))
        raise SystemExit(2)
