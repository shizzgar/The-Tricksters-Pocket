"""Explicit stage receipts: no guessed 'latest', no execution, no automatic retries."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import uuid
from artifacts import VERSION, check_snapshot, read_json, snapshot
from common import atomic_json, digest, now

FULL = {'environment': None, 'acquire': 'environment', 'analyze': 'acquire',
        'patch': 'analyze', 'build': 'patch', 'sign': 'build', 'install': 'sign', 'verify': 'install'}
SIGN_ONLY = {'environment': None, 'intake': 'environment', 'sign': 'intake', 'install': 'sign', 'verify': 'install'}


def validate_receipt(path, case_id, seen=None):
    path = Path(path).resolve()
    seen = set() if seen is None else seen
    if str(path) in seen:
        raise ValueError('Cyclic receipt chain')
    seen.add(str(path))
    data = read_json(path)
    if data.get('schema') != 2 or data.get('case_id') != case_id or data.get('status') != 'pass':
        raise ValueError('Parent is not a passing receipt for this case')
    for record in data['inputs'] + data['outputs'] + [data['evidence']]:
        check_snapshot(record)
    validate_evidence(data['stage'], data['evidence']['path'], data['package'])
    for parent in data['parents']:
        check_snapshot(parent)
        validate_receipt(parent['path'], case_id, seen.copy())
    return data


def validate_evidence(stage, evidence_path, package):
    e = read_json(evidence_path)
    if stage == 'environment':
        if not all(k in e for k in ('observed_at', 'memory_mib', 'disk', 'page_size')):
            raise ValueError('Expected doctor snapshot')
    elif stage in ('acquire', 'intake', 'build', 'sign'):
        from apkset import check_manifest
        check_manifest(e)
        expected = {'acquire': 'source', 'intake': 'candidate', 'build': 'candidate', 'sign': 'signed'}[stage]
        if e.get('kind') != expected or e.get('package') != package:
            raise ValueError('Wrong APK set kind or package')
    elif stage == 'patch':
        if e.get('kind') != 'patch-report' or e.get('status') != 'pass' or not e.get('changes'):
            raise ValueError('Expected completed patch report')
        check_snapshot(e['input_tree'])
        check_snapshot(e['output_tree'])
    elif stage == 'analyze':
        if e.get('kind') != 'analysis-report' or e.get('status') != 'pass' or e.get('package') != package or not e.get('acceptance_tests') or not e.get('target_split_id'):
            raise ValueError('Analysis needs target_split_id, acceptance_tests and matching package')
    elif stage == 'install':
        if e.get('kind') != 'install-report' or e.get('status') != 'pass' or e.get('package') != package or e.get('post_install_hashes_match') is not True:
            raise ValueError('Expected successful installation plus artifact reconciliation')
    elif stage == 'verify':
        tests = e.get('tests', [])
        required = [t for t in tests if t.get('required', True)]
        if e.get('kind') != 'verification-report' or e.get('status') != 'pass' or e.get('package') != package or not required or any(t.get('result') != 'pass' or not t.get('evidence') for t in required):
            raise ValueError('Every required functional test needs pass and evidence')
    return e


def main():
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest='command', required=True)
    init = sub.add_parser('init')
    init.add_argument('--case', required=True)
    init.add_argument('--package', required=True)
    init.add_argument('--android-user', type=int, help='Required for full; may be unbound for sign-only')
    init.add_argument('--mode', choices=['full', 'sign-only'], default='full')
    bind = sub.add_parser('bind-user', help='Bind an artifact-only case before its first install')
    bind.add_argument('--case', required=True)
    bind.add_argument('--android-user', type=int, required=True)
    begin = sub.add_parser('begin')
    begin.add_argument('--case', required=True)
    begin.add_argument('--stage', required=True)
    begin.add_argument('--parent', action='append', default=[])
    begin.add_argument('--input', action='append', default=[])
    finish = sub.add_parser('finish')
    finish.add_argument('--attempt', required=True)
    finish.add_argument('--verdict', choices=['pass', 'failed', 'blocked', 'unknown'], required=True)
    finish.add_argument('--evidence')
    finish.add_argument('--output', action='append', default=[])
    finish.add_argument('--reason', default='')
    check = sub.add_parser('check')
    check.add_argument('--case', required=True)
    check.add_argument('--receipt', required=True)
    a = p.parse_args()
    os.umask(0o077)
    if a.command == 'init':
        if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+', a.package) or (a.android_user is not None and a.android_user < 0):
            p.error('Invalid package/user')
        if a.mode == 'full' and a.android_user is None:
            p.error('Full case requires an explicit Android user')
        root = Path(a.case).resolve()
        root.mkdir(parents=True, exist_ok=False)
        for name in ('attempts', 'input', 'work', 'evidence', 'output'):
            (root / name).mkdir()
        result = {'schema': 2, 'toolkit_version': VERSION, 'case_id': str(uuid.uuid4()),
                  'package': a.package, 'android_user': a.android_user, 'mode': a.mode, 'created_at': now()}
        atomic_json(root / 'case.json', result)
        print(json.dumps(result))
        return
    if a.command == 'finish':
        attempt_path = Path(a.attempt).resolve()
        attempt = read_json(attempt_path)
        root = Path(attempt['case_path'])
    else:
        root = Path(a.case).resolve()
    case = read_json(root / 'case.json')
    if case.get('schema') != 2 or case.get('toolkit_version') != VERSION:
        raise ValueError('Unsupported case version')
    with (root / '.caseflow.lock').open('a+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        case = read_json(root / 'case.json')
        if a.command == 'bind-user':
            if a.android_user < 0 or case['android_user'] not in (None, a.android_user):
                raise ValueError('Cannot change an existing Android user binding; use a new case')
            if case['android_user'] is None:
                case.update(android_user=a.android_user, user_bound_at=now())
                atomic_json(root / 'case.json', case)
            print(json.dumps(case))
        elif a.command == 'check':
            validate_receipt(a.receipt, case['case_id'])
            print(json.dumps({'ok': True, 'receipt': a.receipt}))
        elif a.command == 'begin':
            graph = FULL if case['mode'] == 'full' else SIGN_ONLY
            if a.stage not in graph:
                raise ValueError('Stage unavailable in this mode')
            if a.stage in ('install', 'verify') and case['android_user'] is None:
                raise ValueError('Bind an explicit Android user before install/verify')
            parent_data = [validate_receipt(x, case['case_id']) for x in a.parent]
            expected = [] if graph[a.stage] is None else [graph[a.stage]]
            if [x['stage'] for x in parent_data] != expected:
                raise ValueError('Expected explicit parent stage(s): ' + repr(expected))
            inputs = [snapshot(x) for x in a.input]
            attempt_id = uuid.uuid4().hex[:16]
            directory = root / 'attempts' / a.stage / attempt_id
            directory.mkdir(parents=True)
            attempt = {'schema': 2, 'toolkit_version': VERSION, 'case_id': case['case_id'],
                'package': case['package'], 'android_user': case['android_user'],
                'case_path': str(root), 'stage': a.stage, 'attempt_id': attempt_id, 'status': 'started',
                'created_at': now(), 'inputs': inputs, 'parents': [snapshot(x) for x in a.parent]}
            atomic_json(directory / 'attempt.json', attempt)
            print(json.dumps({'attempt': str(directory / 'attempt.json'), 'attempt_dir': str(directory)}))
        elif a.command == 'finish':
            attempt = read_json(attempt_path)
            if attempt.get('case_id') != case['case_id'] or attempt.get('status') != 'started':
                raise ValueError('Attempt is foreign or already finalized')
            receipt = attempt_path.with_name('receipt.json')
            if receipt.exists():
                raise ValueError('Receipt already exists; reconcile instead of repeating')
            result = {**attempt, 'status': a.verdict, 'finished_at': now(), 'reason': a.reason}
            if a.verdict == 'pass':
                if not a.evidence or not a.output:
                    raise ValueError('Passing stage needs evidence and outputs')
                for output in a.output:
                    target = Path(output).resolve()
                    if target == attempt_path.parent or target in attempt_path.parents or target in (attempt_path, receipt):
                        raise ValueError('Output must not contain attempt/receipt control files')
                for item in attempt['inputs']:
                    check_snapshot(item)
                for item in attempt['parents']:
                    check_snapshot(item)
                    validate_receipt(item['path'], case['case_id'])
                validate_evidence(attempt['stage'], a.evidence, case['package'])
                if attempt['stage'] in ('install', 'verify') and read_json(a.evidence).get('android_user') != case['android_user']:
                    raise ValueError('Evidence belongs to a different Android user')
                result.update(evidence=snapshot(a.evidence), outputs=[snapshot(x) for x in a.output])
            else:
                if not a.reason:
                    raise ValueError('Failed/blocked/unknown stage needs a reason')
                result.update(evidence=snapshot(a.evidence) if a.evidence else None,
                              outputs=[snapshot(x) for x in a.output])
            atomic_json(receipt, result)
            atomic_json(attempt_path, {**attempt, 'status': a.verdict, 'receipt': str(receipt)})
            print(json.dumps({'receipt': str(receipt), 'status': a.verdict}))


if __name__ == '__main__':
    main()
