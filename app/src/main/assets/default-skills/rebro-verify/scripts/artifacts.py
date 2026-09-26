"""Small shared artifact contract for Rebro The Trickster's Pocket packages, version 2.0."""
import hashlib
import json
from pathlib import Path, PurePosixPath
from common import digest

VERSION = "2.0"


def relative_name(value):
    p = PurePosixPath(value)
    if not value or p.is_absolute() or any(x in ('', '.', '..') for x in value.split('/')) or '\\' in value or '\x00' in value:
        raise ValueError('Unsafe relative path: ' + repr(value))
    return p


def regular(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise ValueError('Expected regular file, no symlink: ' + str(path))
    return path.resolve()


def tree_snapshot(root):
    root = Path(root)
    if root.is_symlink() or not root.is_dir():
        raise ValueError('Expected directory, no symlink: ' + str(root))
    rows = []
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise ValueError('Symlink in artifact: ' + str(path))
        if path.is_dir():
            continue
        if not path.is_file():
            raise ValueError('Special file in artifact: ' + str(path))
        rows.append({'path': path.relative_to(root).as_posix(), 'sha256': digest(path), 'bytes': path.stat().st_size})
        if len(rows) > 100000:
            raise ValueError('Tree exceeds 100000-file limit')
    encoded = json.dumps(rows, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
    return {'kind': 'tree', 'path': str(root.resolve()), 'sha256': hashlib.sha256(encoded).hexdigest(),
            'file_count': len(rows), 'bytes': sum(r['bytes'] for r in rows)}


def snapshot(path):
    p = Path(path)
    if p.is_dir():
        return tree_snapshot(p)
    p = regular(p)
    return {'kind': 'file', 'path': str(p), 'sha256': digest(p), 'bytes': p.stat().st_size}


def check_snapshot(record):
    actual = snapshot(record['path'])
    if actual != record:
        raise ValueError('Artifact changed: ' + record['path'])


def read_json(path):
    return json.loads(regular(path).read_text(encoding='utf-8'))
