"""Replace explicitly selected split IDs, copy all other source APKs, inspect the result."""
import argparse
import json
import os
from pathlib import Path
import shutil
from artifacts import read_json, regular
from apkset import check_manifest, inspect_set
from common import atomic_json, digest


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source-set', required=True)
    p.add_argument('--replace', action='append', default=[], help='SPLIT_ID=ABSOLUTE_APK_PATH; base for base APK')
    p.add_argument('--out-dir', required=True)
    a = p.parse_args()
    os.umask(0o077)
    source = read_json(a.source_set)
    check_manifest(source)
    replacements = {}
    for pair in a.replace:
        key, sep, value = pair.partition('=')
        if not sep or key in replacements or key not in source['split_ids']:
            p.error('Unknown or duplicate replacement split ID')
        replacements[key] = regular(value)
    if not replacements:
        p.error('At least one replacement required; use apkset for external intake')
    out = Path(a.out_dir).resolve()
    out.mkdir(parents=True, exist_ok=False)
    files = []
    for i, row in enumerate(source['entries']):
        src = replacements.get(row['split_id'], Path(row['path']))
        before = digest(src)
        dest = out / f'{i:03d}.apk'
        shutil.copyfile(src, dest)
        if digest(dest) != before or digest(src) != before:
            raise ValueError('APK changed during copy')
        files.append(dest)
    result = inspect_set(files, 'candidate', out / 'inspection')
    if (result['package'], result['version_code'], result['version_code_major'], result['split_ids']) != (source['package'], source['version_code'], source.get('version_code_major', 0), source['split_ids']):
        raise ValueError('Rebuild changed package/version/split topology; explicit migration workflow required')
    result['source_set_sha256'] = digest(a.source_set)
    result['replaced_split_ids'] = sorted(replacements)
    result['membership'] = {'basis': 'preserved_source_topology',
                            'source_basis': source.get('membership', {}).get('basis', 'provided_members'),
                            'dependency_closure_checked': False}
    check_manifest(source)
    atomic_json(out / 'candidate-set.json', result)
    print(json.dumps({'candidate_set': str(out / 'candidate-set.json')}))


if __name__ == '__main__':
    main()
