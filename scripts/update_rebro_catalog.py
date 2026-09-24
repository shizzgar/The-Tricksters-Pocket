#!/usr/bin/env python3
"""Regenerate adapted ReBro manifests while retaining original source ZIP provenance."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
TITLES = {
    'rebro-workflow': 'Workflow orchestration',
    'rebro-environment': 'Environment and baseline',
    'rebro-acquire': 'APK acquisition',
    'rebro-analyze': 'Analysis',
    'rebro-patch': 'Patching',
    'rebro-build': 'Rebuilding',
    'rebro-sign': 'Signing',
    'rebro-install': 'Installation',
    'rebro-verify': 'Behavior verification',
    'rebro-frida': 'Runtime Frida',
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def files_under(directory, excluded):
    files = {}
    for path in sorted(directory.rglob('*')):
        if path.is_symlink():
            raise ValueError('Symlinks are not packaged: ' + str(path))
        if not path.is_file() or path == directory / excluded:
            continue
        if '__pycache__' in path.parts or path.suffix == '.pyc':
            raise ValueError('Remove generated Python bytecode: ' + str(path))
        files[path.relative_to(directory).as_posix()] = path.read_bytes()
    return files


def manifest(files):
    return ''.join(digest(content) + '  ' + name + '\n' for name, content in sorted(files.items())).encode()


def render():
    generated = {}
    vendor = ASSETS / 'default-skills/rebro-frida/assets/rebro-frida-pack'
    vendor_manifest = manifest(files_under(vendor, 'SHA256SUMS'))
    generated[vendor / 'SHA256SUMS'] = vendor_manifest
    provenance = json.loads((ASSETS / 'assistant-presets/rebro/provenance.json').read_text())
    sources = {item['name']: item for item in provenance['source_packages']}
    if set(sources) != set(TITLES):
        raise ValueError('Source package inventory differs from bundled skills')
    packages = []
    for name, title in TITLES.items():
        directory = ASSETS / 'default-skills' / name
        files = files_under(directory, 'MANIFEST.sha256')
        if name == 'rebro-frida':
            files['assets/rebro-frida-pack/SHA256SUMS'] = vendor_manifest
        if 'SKILL.md' not in files:
            raise ValueError('Missing SKILL.md: ' + name)
        content = manifest(files)
        generated[directory / 'MANIFEST.sha256'] = content
        packages.append({
            'name': name, 'title': title, 'files': len(files) + 1,
            'bytes': sum(map(len, files.values())) + len(content),
            'manifest_sha256': digest(content),
            'source_zip': sources[name]['zip'],
            'source_zip_sha256': sources[name]['zip_sha256'],
        })
    generated[ASSETS / 'assistant-presets/rebro/catalog.json'] = (json.dumps({
        'schema_version': 2, 'release': provenance['adaptation_release'],
        'source_kit_release': provenance['source_kit_release'],
        'caseflow_contract': '2.0', 'instruction_language': 'en', 'packages': packages,
    }, indent=2) + '\n').encode()
    return generated


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    changed = []
    for path, data in render().items():
        if not path.exists() or path.read_bytes() != data:
            changed.append(path.relative_to(ROOT).as_posix())
            if not args.check:
                path.write_bytes(data)
    print('\n'.join(changed) if changed else 'ReBro package manifests and catalog are current.')
    return 1 if args.check and changed else 0


if __name__ == '__main__':
    raise SystemExit(main())
