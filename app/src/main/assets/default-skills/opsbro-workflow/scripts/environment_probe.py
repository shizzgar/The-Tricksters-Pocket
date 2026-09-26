#!/usr/bin/env python3
"""Local host evidence without subprocesses, network, writes or secret values."""
import argparse
import json
import os
import platform
import shutil
from pathlib import Path


def probe(root):
    root = Path(root).resolve(strict=True)
    if not root.is_dir():
        raise ValueError('root must be a directory')
    disk = shutil.disk_usage(root)
    commands = ('python3', 'git', 'java', 'node', 'npm', 'cargo', 'go', 'ssh', 'bash', 'sh', 'pkg', 'apt', 'dnf', 'apk', 'systemctl', 'termux-info')
    return {
        'scope': 'machine running this script only', 'root': str(root),
        'platform': platform.system(), 'release': platform.release(), 'architecture': platform.machine(),
        'python': platform.python_version(),
        'uid': os.getuid() if hasattr(os, 'getuid') else None,
        'termux_detected': shutil.which('termux-info') is not None,
        'disk': {'total_bytes': disk.total, 'free_bytes': disk.free, 'used_bytes': disk.used},
        'executables': {name: shutil.which(name) for name in commands},
        'not_checked': ['service health', 'network reachability', 'credentials', 'package versions', 'remote host', 'backup restoration'],
        'note': 'Executable presence does not establish a working service. No commands were executed.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', nargs='?', default='.')
    args = parser.parse_args()
    try:
        print(json.dumps(probe(args.root), ensure_ascii=False, indent=2))
    except (OSError, ValueError) as error:
        print(json.dumps({'error': str(error)}))
        raise SystemExit(2)
