#!/usr/bin/env python3
"""Compare bounded local artifact entries with an explicit expected manifest; never execute artifacts."""
import argparse
import hashlib
import json
import re
from pathlib import Path


def verify(root, manifest):
    root = Path(root).resolve(strict=True)
    if not root.is_dir():
        raise ValueError('root must be a directory')
    entries = manifest.get('files') if isinstance(manifest, dict) else None
    if not isinstance(entries, list) or not entries or len(entries) > 1000:
        raise ValueError('files must contain 1..1000 entries')
    checks, seen = [], set()
    for entry in entries:
        try:
            if not isinstance(entry, dict):
                raise ValueError('entry must be an object')
            raw = entry.get('path')
            if not isinstance(raw, str) or not raw or '\\' in raw:
                raise ValueError('path must be a relative slash-separated path')
            rel = Path(raw)
            if rel.is_absolute() or '..' in rel.parts or ':' in raw or rel == Path('.'):
                raise ValueError('absolute/traversal paths are not allowed')
            normalized = rel.as_posix()
            if normalized in seen:
                raise ValueError('duplicate file entry')
            seen.add(normalized)
            expected = entry.get('sha256', '')
            if not isinstance(expected, str) or re.fullmatch(r'[0-9a-fA-F]{64}', expected) is None:
                raise ValueError('expected sha256 must contain 64 hexadecimal digits')
            size = entry.get('size')
            if size is not None and (type(size) is not int or size < 0):
                raise ValueError('expected size must be a nonnegative integer')
            candidate = root / rel
            if any((root.joinpath(*rel.parts[:i])).is_symlink() for i in range(1, len(rel.parts) + 1)):
                raise ValueError('symlink paths are not accepted')
            actual = candidate.resolve(strict=True)
            if root not in actual.parents or not actual.is_file():
                raise ValueError('file must be within selected root')
            digest = hashlib.sha256()
            with actual.open('rb') as stream:
                for chunk in iter(lambda: stream.read(1048576), b''):
                    digest.update(chunk)
            actual_size = actual.stat().st_size
            matches = digest.hexdigest() == expected.lower() and (size is None or actual_size == size)
            checks.append({'path': raw, 'status': 'CONFIRMED' if matches else 'ERRORS FOUND',
                           'sha256': digest.hexdigest(), 'size': actual_size})
        except (OSError, ValueError) as error:
            checks.append({'path': entry.get('path') if isinstance(entry, dict) else None,
                           'status': 'ERRORS FOUND', 'error': str(error)})
    ok = all(check['status'] == 'CONFIRMED' for check in checks)
    return {'status': 'CONFIRMED' if ok else 'ERRORS FOUND', 'checks': checks,
            'scope': 'file bytes match the supplied manifest only',
            'not_verified': ['manifest authenticity', 'signature', 'source provenance', 'runtime behavior', 'safety']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root')
    parser.add_argument('manifest')
    args = parser.parse_args()
    try:
        path = Path(args.manifest)
        if path.stat().st_size > 1048576:
            raise ValueError('manifest exceeds 1 MiB')
        result = verify(args.root, json.loads(path.read_text(encoding='utf-8')))
        print(json.dumps(result, ensure_ascii=False, indent=2))
        raise SystemExit(0 if result['status'] == 'CONFIRMED' else 1)
    except (OSError, ValueError) as error:
        print(json.dumps({'status': 'ERRORS FOUND', 'error': str(error)}))
        raise SystemExit(2)
