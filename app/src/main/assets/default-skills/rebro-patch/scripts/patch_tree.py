"""Apply an exact, hash-bound patch plan to a NEW copy of a decoded tree."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
from artifacts import VERSION, read_json, regular, relative_name, tree_snapshot
from common import atomic_json, digest, now


def apply_plan(source, plan_path, output):
    source, plan_path, output = Path(source).resolve(), regular(plan_path), Path(output).resolve()
    if output == source or source in output.parents:
        raise ValueError('Output must be outside source tree')
    if output.exists():
        raise ValueError('Refusing existing output')
    plan = read_json(plan_path)
    before = tree_snapshot(source)
    if plan.get('schema') != 2 or plan.get('input_tree_sha256') != before['sha256']:
        raise ValueError('Wrong schema or stale input tree')
    if not plan.get('purpose') or not plan.get('expected_behavior') or not plan.get('operations'):
        raise ValueError('purpose, expected_behavior and operations required')
    seen, prepared = set(), []
    for op in plan['operations']:
        name = relative_name(op['path']).as_posix()
        if name in seen:
            raise ValueError('Duplicate operation path: ' + name)
        seen.add(name)
        path = source / name
        action = op['action']
        if action == 'add_file':
            if path.exists() or op.get('before_sha256') is not None:
                raise ValueError('add_file requires absent path and null before_sha256')
            old = None
        else:
            old = regular(path).read_bytes()
            if hashlib.sha256(old).hexdigest() != op.get('before_sha256'):
                raise ValueError('Unexpected old bytes: ' + name)
        if action == 'replace_text':
            text = old.decode('utf-8')
            needle, replacement = op['old'], op['new']
            count = op.get('count', 1)
            if not isinstance(count, int) or count < 1 or not isinstance(needle, str) or not needle or not isinstance(replacement, str):
                raise ValueError('Invalid literal replacement')
            if text.count(needle) != count:
                raise ValueError('Literal match count differs: ' + name)
            new = text.replace(needle, replacement).encode('utf-8')
        elif action in ('replace_file', 'add_file'):
            payload = Path(op['payload'])
            payload = payload if payload.is_absolute() else plan_path.parent / payload
            new = regular(payload).read_bytes()
            if hashlib.sha256(new).hexdigest() != op.get('payload_sha256'):
                raise ValueError('Payload hash differs: ' + name)
        elif action == 'delete_file':
            new = None
        else:
            raise ValueError('Unsupported action: ' + str(action))
        if old == new:
            raise ValueError('No-op patch: ' + name)
        prepared.append((name, old, new, action))
    output.mkdir(parents=True)
    report = {'schema': 2, 'toolkit_version': VERSION, 'kind': 'patch-report', 'status': 'incomplete',
              'started_at': now(), 'input_tree': before, 'plan_sha256': digest(plan_path),
              'purpose': plan['purpose'], 'expected_behavior': plan['expected_behavior'], 'changes': []}
    atomic_json(output / 'patch-report.json', report)
    try:
        tree = output / 'tree'
        shutil.copytree(source, tree)
        if tree_snapshot(tree)['sha256'] != before['sha256']:
            raise ValueError('Source changed during copy')
        for name, old, new, action in prepared:
            dest = tree / name
            if new is None:
                dest.unlink()
            else:
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(new)
            report['changes'].append({'path': name, 'action': action,
                'before_sha256': hashlib.sha256(old).hexdigest() if old is not None else None,
                'after_sha256': hashlib.sha256(new).hexdigest() if new is not None else None})
        if tree_snapshot(source) != before:
            raise ValueError('Source changed during patch')
        report.update(status='pass', output_tree=tree_snapshot(tree), finished_at=now())
    except Exception as e:
        report.update(status='failed', error=str(e), finished_at=now())
        atomic_json(output / 'patch-report.json', report)
        raise
    atomic_json(output / 'patch-report.json', report)
    return report


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--tree', required=True)
    p.add_argument('--plan', required=True)
    p.add_argument('--out-dir', required=True)
    a = p.parse_args()
    os.umask(0o077)
    print(json.dumps(apply_plan(a.tree, a.plan, a.out_dir), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
