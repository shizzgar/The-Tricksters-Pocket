"""Align, sign and verify the WHOLE candidate APK set using one pinned certificate."""
import argparse
import json
import os
from pathlib import Path
import re
import stat
from artifacts import VERSION, regular, read_json
from apkset import check_manifest, command, inspect_set
from common import atomic_json, digest, now


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--set', required=True)
    p.add_argument('--keystore', required=True)
    p.add_argument('--alias', required=True)
    p.add_argument('--ks-pass-file', required=True)
    p.add_argument('--key-pass-file', required=True)
    p.add_argument('--expected-cert-sha256', required=True)
    p.add_argument('--out-dir', required=True)
    a = p.parse_args()
    os.umask(0o077)
    expected = a.expected_cert_sha256.lower()
    if not re.fullmatch('[0-9a-f]{64}', expected):
        p.error('Expected full SHA-256 certificate digest, no colons')
    for item in (a.keystore, a.ks_pass_file, a.key_pass_file):
        regular(item)
    for item in (a.ks_pass_file, a.key_pass_file):
        if stat.S_IMODE(Path(item).stat().st_mode) not in (0o400, 0o600):
            p.error('Password files must have mode 400/600')
    source = read_json(a.set)
    check_manifest(source)
    if source['kind'] != 'candidate':
        p.error('Signing requires candidate set; do not silently re-sign a signed release')
    out = Path(a.out_dir).resolve()
    out.mkdir(parents=True, exist_ok=False)
    progress = {'schema': 2, 'toolkit_version': VERSION, 'kind': 'sign-progress', 'status': 'started',
                'started_at': now(), 'candidate_set_sha256': digest(a.set), 'expected_cert_sha256': expected}
    atomic_json(out / 'progress.json', progress)
    try:
        signed_files = []
        for index, row in enumerate(source['entries']):
            aligned = out / f'{index:03d}.aligned.apk'
            signed = out / f'{index:03d}.signed.apk'
            command(['zipalign', '-P', '16', '-v', '4', row['path'], aligned], out / 'logs', f'{index:03d}-align')
            command(['apksigner', 'sign', '--ks', str(regular(a.keystore)), '--ks-key-alias', a.alias,
                     '--ks-pass', 'file:' + str(regular(a.ks_pass_file)), '--key-pass', 'file:' + str(regular(a.key_pass_file)),
                     '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true', '--v4-signing-enabled', 'false',
                     '--out', signed, aligned], out / 'logs', f'{index:03d}-sign')
            signed_files.append(signed)
        result = inspect_set(signed_files, 'signed', out / 'verification')
        if result['cert_sha256'] != [expected]:
            raise ValueError('Signer certificate does not match pinned lab certificate')
        if (result['package'], result['version_code'], result['version_code_major'], result['split_ids']) != (source['package'], source['version_code'], source.get('version_code_major', 0), source['split_ids']):
            raise ValueError('Signing changed APK set identity')
        check_manifest(source)
        result['candidate_set_sha256'] = digest(a.set)
        result['expected_cert_sha256'] = expected
        result['membership'] = {'basis': 'preserved_candidate_topology',
                                'candidate_basis': source.get('membership', {}).get('basis', 'provided_members'),
                                'dependency_closure_checked': False}
        atomic_json(out / 'signed-set.json', result)
        progress.update(status='pass', finished_at=now())
    except Exception as e:
        progress.update(status='failed', error=str(e), finished_at=now())
        atomic_json(out / 'progress.json', progress)
        raise
    atomic_json(out / 'progress.json', progress)
    print(json.dumps({'signed_set': str(out / 'signed-set.json'), 'status': 'pass'}))


if __name__ == '__main__':
    main()
