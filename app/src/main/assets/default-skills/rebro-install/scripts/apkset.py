"""Inspect actual APK bytes into a hash-bound manifest with package/split identity."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
from artifacts import VERSION, regular, read_json, snapshot, check_snapshot
from common import atomic_json, digest, now


def command(argv, log_dir, label, timeout=120):
    log_dir = Path(log_dir)
    log_dir.mkdir(parents=True, exist_ok=True)
    log = log_dir / (label + '.log')
    with log.open('xb') as stream:
        result = subprocess.run(list(map(str, argv)), stdin=subprocess.DEVNULL, stdout=stream,
                                stderr=subprocess.STDOUT, timeout=timeout,
                                env={**os.environ, 'LC_ALL': 'C', 'LANG': 'C'})
    if log.stat().st_size > 4 * 1024 ** 2:
        raise ValueError('Tool log exceeds parse limit: ' + str(log))
    text = log.read_text(errors='replace')
    if result.returncode != 0:
        raise ValueError('Command failed (' + str(result.returncode) + '): see ' + str(log))
    return text


def parse_badging(text):
    package_lines = [x for x in text.splitlines() if x.startswith('package:')]
    if len(package_lines) != 1:
        raise ValueError('Expected one package line in aapt2 output')
    attrs = dict(re.findall(r"([A-Za-z0-9_]+)='([^']*)'", package_lines[0]))
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+', attrs.get('name', '')):
        raise ValueError('Invalid package name')
    version = attrs.get('versionCode', '')
    if not version.isdigit():
        raise ValueError('Missing numeric versionCode')
    # Preserve versionCodeMajor where this aapt2 exposes it.
    major = attrs.get('versionCodeMajor', '0')
    if not major.isdigit():
        raise ValueError('Invalid versionCodeMajor')
    return {'package': attrs['name'], 'version_code': int(version), 'version_code_major': int(major),
            'split_id': attrs.get('split', '') or 'base'}


def parse_verification(text):
    certs = sorted(set(x.lower() for x in re.findall(r'Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})', text)))
    if not certs:
        raise ValueError('No signer certificate digest in successful verification output')
    v2 = bool(re.search(r'Verified using v2 scheme[^\r\n]*:\s*true', text))
    return certs, v2


def bind_acquisition(manifest, acquisition_path):
    """Prove every copied member of one recorded installed snapshot was included."""
    acquisition_path = regular(acquisition_path)
    acquisition = read_json(acquisition_path)
    if (manifest['kind'] != 'source' or acquisition.get('schema') != 1 or
            acquisition.get('status') != 'complete' or acquisition.get('package') != manifest['package']):
        raise ValueError('Need a complete matching acquisition for a source set')
    rows = acquisition.get('apks', [])
    expected = []
    for row in rows:
        name = row['file']
        if Path(name).name != name or name in ('.', '..'):
            raise ValueError('Unsafe acquisition member name')
        expected.append((str(regular(acquisition_path.parent / name)), row['sha256'], row['size_bytes']))
    actual = [(x['path'], x['sha256'], x['bytes']) for x in manifest['entries']]
    if sorted(expected) != sorted(actual):
        raise ValueError('Source members do not match the complete acquisition')
    manifest['acquisition'] = snapshot(acquisition_path)
    manifest['membership'] = {'basis': 'installed_snapshot', 'dependency_closure_checked': False}


def inspect_set(files, kind, logs):
    if kind not in ('source', 'candidate', 'signed') or not files or len(files) > 200:
        raise ValueError('Invalid kind or APK count (1..200)')
    entries = []
    for index, file in enumerate(files):
        path = regular(file)
        before = digest(path)
        meta = parse_badging(command(['aapt2', 'dump', 'badging', path], logs, f'{index:03d}-badging'))
        certs, v2 = [], False
        verified = kind in ('source', 'signed')
        if verified:
            result = command(['apksigner', 'verify', '--verbose', '--print-certs', path], logs, f'{index:03d}-signature')
            certs, v2 = parse_verification(result)
        if kind == 'signed':
            command(['zipalign', '-c', '-P', '16', '-v', '4', path], logs, f'{index:03d}-alignment')
            if not v2:
                raise ValueError('Signed output lacks verified V2 signature')
        if digest(path) != before:
            raise ValueError('APK changed during inspection')
        entries.append({**meta, 'path': str(path), 'sha256': before, 'bytes': path.stat().st_size,
                        'signature_verified': verified, 'cert_sha256': certs, 'v2_verified': v2,
                        'alignment_16k_verified': kind == 'signed'})
    identities = {(x['package'], x['version_code'], x['version_code_major']) for x in entries}
    splits = [x['split_id'] for x in entries]
    if len(identities) != 1 or splits.count('base') != 1 or len(splits) != len(set(splits)):
        raise ValueError('Mixed package/version, missing base, or duplicate split IDs')
    if kind in ('source', 'signed') and len({tuple(x['cert_sha256']) for x in entries}) != 1:
        raise ValueError('Mixed signer sets across APKs')
    pkg, version, major = next(iter(identities))
    result = {'schema': 2, 'toolkit_version': VERSION, 'kind': kind, 'status': 'pass',
              'observed_at': now(), 'package': pkg, 'version_code': version, 'version_code_major': major,
              'entries': entries, 'split_ids': sorted(splits),
              'cert_sha256': entries[0]['cert_sha256'] if kind != 'candidate' else [],
              'membership': {'basis': 'provided_members', 'dependency_closure_checked': False}}
    check_manifest(result)
    return result


def check_manifest(manifest):
    if manifest.get('schema') != 2 or manifest.get('toolkit_version') != VERSION or manifest.get('status') != 'pass':
        raise ValueError('Incomplete or unsupported APK set')
    if manifest.get('kind') not in ('source', 'candidate', 'signed'):
        raise ValueError('Unknown APK set kind')
    entries = manifest.get('entries', [])
    if not entries or len(entries) > 200:
        raise ValueError('Invalid APK entries')
    ids, paths = [], []
    for row in entries:
        path = regular(row['path'])
        if digest(path) != row['sha256'] or path.stat().st_size != row['bytes']:
            raise ValueError('APK changed: ' + str(path))
        if (row['package'], row['version_code'], row.get('version_code_major', 0)) != (manifest['package'], manifest['version_code'], manifest.get('version_code_major', 0)):
            raise ValueError('Mixed identity in manifest')
        ids.append(row['split_id'])
        paths.append(str(path))
        if manifest['kind'] in ('source', 'signed'):
            if row.get('signature_verified') is not True or not row.get('cert_sha256') or row['cert_sha256'] != manifest.get('cert_sha256'):
                raise ValueError('Unverified or inconsistent certificates')
        if manifest['kind'] == 'signed' and (row.get('v2_verified') is not True or row.get('alignment_16k_verified') is not True):
            raise ValueError('Signed manifest missing verification gates')
    if ids.count('base') != 1 or len(ids) != len(set(ids)) or len(paths) != len(set(paths)) or sorted(ids) != manifest['split_ids']:
        raise ValueError('Invalid split set')
    if 'acquisition' in manifest:
        check_snapshot(manifest['acquisition'])
        bind_acquisition(dict(manifest), manifest['acquisition']['path'])


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--kind', choices=['source', 'candidate', 'signed'])
    p.add_argument('--out')
    p.add_argument('--logs')
    p.add_argument('--check')
    p.add_argument('--acquisition', help='Bind a source set to every APK of a complete acquisition.json')
    p.add_argument('apk', nargs='*')
    a = p.parse_args()
    os.umask(0o077)
    if a.check:
        check_manifest(read_json(a.check))
        print(json.dumps({'ok': True}))
        return
    if not a.kind or not a.out or not a.logs or not a.apk or Path(a.out).exists():
        p.error('Need kind, NEW out, logs and APK paths')
    result = inspect_set(a.apk, a.kind, a.logs)
    if a.acquisition:
        bind_acquisition(result, a.acquisition)
    check_manifest(result)
    atomic_json(a.out, result)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
