"""Plan/apply/reconcile a full-set install. Default operations are explicit subcommands."""
import argparse
import datetime
import fcntl
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import subprocess
from artifacts import VERSION, read_json, regular
from apkset import check_manifest, inspect_set
from common import atomic_json, digest, now, read_text


def su(argv, stdin=None, timeout=90):
    result = subprocess.run(['su', '-c', shlex.join(list(map(str, argv)))],
                            stdin=stdin if stdin is not None else subprocess.DEVNULL,
                            capture_output=True, timeout=timeout)
    text = result.stdout.decode(errors='replace').strip()
    err = result.stderr.decode(errors='replace').strip()
    if result.returncode != 0 or err:
        raise RuntimeError('Root command failed or emitted stderr: ' + text + ' ' + err)
    return text


def pm(*args, **kwargs):
    return su(['/system/bin/pm', *args], **kwargs)


def installed_users(package):
    users = sorted(set(int(x) for x in re.findall(r'UserInfo\{(\d+):', pm('list', 'users'))))
    if not users:
        raise ValueError('Cannot parse Android users; do not guess user 0')
    present, system = [], False
    for user in users:
        output = pm('list', 'packages', '--user', str(user), package)
        lines = output.splitlines()
        if any(line and not line.startswith('package:') for line in lines):
            raise ValueError('Unrecognized package list output')
        if 'package:' + package in lines:
            present.append(user)
            system = system or ('package:' + package in pm('list', 'packages', '-s', '--user', str(user), package).splitlines())
    return users, present, system


def installed_snapshot(package, user):
    users, present, system = installed_users(package)
    if user not in users:
        raise ValueError('Requested Android user does not exist')
    paths, hashes = {}, {}
    for uid in present:
        rows = pm('path', '--user', str(uid), package).splitlines()
        if not rows or any(not x.startswith('package:/') or not x.endswith('.apk') for x in rows):
            raise ValueError('Unexpected installed package paths')
        paths[str(uid)] = sorted(x[len('package:'):] for x in rows)
        for path in paths[str(uid)]:
            if path not in hashes:
                value = su(['/system/bin/toybox', 'sha256sum', path]).split()[0]
                if not re.fullmatch('[0-9a-f]{64}', value):
                    raise ValueError('Unrecognized root SHA-256 result')
                hashes[path] = value
    return {'all_users': users, 'installed_users': present, 'system_app': system,
            'paths_by_user': paths, 'sha256_by_path': hashes,
            'boot_id': read_text('/proc/sys/kernel/random/boot_id')}


def matches_target(observed, manifest, user):
    paths = observed['paths_by_user'].get(str(user), [])
    actual = sorted(observed['sha256_by_path'][x] for x in paths)
    return bool(paths) and actual == sorted(x['sha256'] for x in manifest['entries'])


def check_plan_identity(plan, manifest):
    if (plan.get('schema') != 2 or plan.get('toolkit_version') != VERSION or
            plan.get('kind') != 'install-plan' or manifest.get('kind') != 'signed' or
            plan.get('package') != manifest.get('package') or
            not isinstance(plan.get('android_user'), int) or plan['android_user'] < 0 or
            plan.get('expected_apk_hashes') != sorted(x['sha256'] for x in manifest['entries'])):
        raise ValueError('Plan identity or expected APK hashes are inconsistent')


def copy_installed(observed, out):
    out = Path(out)
    out.mkdir()
    files = []
    paths = sorted(observed['sha256_by_path'])
    # Android normally shares one code path across users. Fail if versions diverge.
    user_sets = {tuple(x) for x in observed['paths_by_user'].values()}
    if len(user_sets) > 1:
        raise ValueError('Code paths differ across users; manual analysis required')
    for i, path in enumerate(paths):
        size = int(su(['/system/bin/toybox', 'stat', '-c', '%s', path]))
        if shutil.disk_usage(out).free < size + 1024 ** 3:
            raise ValueError('Insufficient room to preserve installed APKs')
        dest = out / f'{i:03d}.apk'
        with dest.open('xb') as stream:
            subprocess.run(['su', '-c', shlex.join(['/system/bin/cat', path])],
                           stdin=subprocess.DEVNULL, stdout=stream, check=True, timeout=180)
        if digest(dest) != observed['sha256_by_path'][path]:
            raise ValueError('Installed APK changed while preserving it')
        files.append(dest)
    return files


def make_plan(set_path, user, out, allow_other_users=False):
    manifest = read_json(set_path)
    check_manifest(manifest)
    if manifest['kind'] != 'signed':
        raise ValueError('Only a signed set is installable')
    out = Path(out).resolve()
    out.mkdir(parents=True, exist_ok=False)
    # Verify actual cryptography again before planning a device mutation.
    actual = inspect_set([x['path'] for x in manifest['entries']], 'signed', out / 'signed-verification')
    if actual['cert_sha256'] != manifest['cert_sha256']:
        raise ValueError('Certificate changed')
    observed = installed_snapshot(manifest['package'], user)
    reasons = []
    other = [x for x in observed['installed_users'] if x != user]
    if observed['system_app']:
        reasons.append('system_package_not_supported')
    if other and not allow_other_users:
        reasons.append('code_update_affects_other_users')
    current_manifest = None
    if observed['installed_users']:
        files = copy_installed(observed, out / 'installed-before')
        current_manifest = inspect_set(files, 'source', out / 'installed-verification')
        atomic_json(out / 'installed-before.json', current_manifest)
        if current_manifest['cert_sha256'] != manifest['cert_sha256']:
            reasons.append('certificate_mismatch_no_automatic_uninstall')
        old_version = (current_manifest['version_code_major'], current_manifest['version_code'])
        new_version = (manifest['version_code_major'], manifest['version_code'])
        if new_version < old_version:
            reasons.append('downgrade_not_supported')
        if set(current_manifest['split_ids']) != set(manifest['split_ids']):
            reasons.append('installed_split_topology_differs')
    if installed_snapshot(manifest['package'], user) != observed:
        raise ValueError('Device package changed during planning')
    plan = {'schema': 2, 'toolkit_version': VERSION, 'kind': 'install-plan',
            'created_at': now(), 'status': 'blocked' if reasons else 'ready', 'reasons': reasons,
            'package': manifest['package'], 'android_user': user, 'signed_set': str(regular(set_path)),
            'signed_set_sha256': digest(set_path), 'before': observed,
            'affects_other_users': other, 'allow_other_users': allow_other_users,
            'expected_apk_hashes': sorted(x['sha256'] for x in manifest['entries']),
            'already_matches': matches_target(observed, manifest, user),
            'operations': ['install-create', 'install-write for every APK via stdin', 'install-commit', 'compare installed hashes']}
    atomic_json(out / 'install-plan.json', plan)
    return {'plan': str(out / 'install-plan.json'), 'sha256': digest(out / 'install-plan.json'), 'status': plan['status']}


def apply_plan(plan_path, expected_sha, out):
    plan_path = regular(plan_path)
    if digest(plan_path) != expected_sha:
        raise ValueError('Plan hash differs from the reviewed plan')
    plan = read_json(plan_path)
    if plan.get('schema') != 2 or plan.get('status') != 'ready':
        raise ValueError('Plan is not ready')
    age = (datetime.datetime.now(datetime.timezone.utc) - datetime.datetime.fromisoformat(plan['created_at'])).total_seconds()
    if age < -30 or age > 600:
        raise ValueError('Plan expired; repeat read-only planning')
    if digest(plan['signed_set']) != plan['signed_set_sha256']:
        raise ValueError('Signed manifest changed')
    manifest = read_json(plan['signed_set'])
    check_manifest(manifest)
    check_plan_identity(plan, manifest)
    before = installed_snapshot(plan['package'], plan['android_user'])
    if before != plan['before']:
        raise ValueError('Device state changed; do not execute stale plan')
    journal = plan_path.with_name('execution.json')
    if journal.exists():
        raise ValueError('Plan already attempted; use reconcile, never repeat commit blindly')
    out = Path(out).resolve()
    out.mkdir(parents=True, exist_ok=False)
    report = {'schema': 2, 'toolkit_version': VERSION, 'kind': 'install-report', 'status': 'started',
              'package': plan['package'], 'android_user': plan['android_user'], 'started_at': now(),
              'plan': str(plan_path), 'plan_sha256': expected_sha, 'report_path': str(out / 'install-report.json'),
              'phase': 'preflight', 'session_id': None, 'post_install_hashes_match': False}
    def save():
        atomic_json(out / 'install-report.json', report)
        atomic_json(journal, report)
    save()
    try:
        if matches_target(before, manifest, plan['android_user']):
            report.update(status='pass', phase='already_present', post_install_hashes_match=True)
            return report
        report['phase'] = 'create_requested'
        save()
        total = sum(x['bytes'] for x in manifest['entries'])
        created = pm('install-create', '--user', str(plan['android_user']), '-r', '-S', str(total))
        (out / 'create.log').write_text(created + '\n')
        match = re.fullmatch(r'Success: created install session \[(\d+)\]', created)
        if not match:
            raise ValueError('Cannot identify newly created session')
        session = match.group(1)
        report.update(session_id=int(session), phase='writing')
        save()
        for i, row in enumerate(manifest['entries']):
            if digest(row['path']) != row['sha256']:
                raise ValueError('APK changed before streaming')
            with open(row['path'], 'rb') as stream:
                written = pm('install-write', '-S', str(row['bytes']), session, f'{i:03d}.apk', '-', stdin=stream, timeout=180)
            (out / f'write-{i:03d}.log').write_text(written + '\n')
            if not written.startswith('Success:'):
                raise ValueError('Unrecognized install-write response')
        check_manifest(manifest)
        report['phase'] = 'commit_requested'
        save()
        committed = pm('install-commit', session, timeout=180)
        (out / 'commit.log').write_text(committed + '\n')
        if committed != 'Success':
            raise ValueError('Unrecognized commit response')
        report['phase'] = 'commit_acknowledged'
        save()
        after = installed_snapshot(plan['package'], plan['android_user'])
        atomic_json(out / 'installed-after.json', after)
        if not matches_target(after, manifest, plan['android_user']):
            raise ValueError('Committed but installed bytes do not match intended set')
        report.update(status='pass', phase='verified_installed_bytes', post_install_hashes_match=True)
    except BaseException as e:
        report.update(status='unknown' if report['phase'] in ('create_requested', 'commit_requested', 'commit_acknowledged') else 'failed', error=type(e).__name__ + ': ' + str(e))
        if report['session_id'] is not None and report['phase'] not in ('commit_requested', 'commit_acknowledged'):
            try:
                report['abandon_result'] = pm('install-abandon', str(report['session_id']))
            except BaseException as cleanup:
                report['abandon_error'] = str(cleanup)
        raise
    finally:
        report['finished_at'] = now()
        save()
    return report


def reconcile(report_path, output):
    report = read_json(report_path)
    plan = read_json(report['plan'])
    if digest(report['plan']) != report['plan_sha256'] or digest(plan['signed_set']) != plan['signed_set_sha256']:
        raise ValueError('Evidence changed')
    manifest = read_json(plan['signed_set'])
    check_manifest(manifest)
    check_plan_identity(plan, manifest)
    if (report.get('schema') != 2 or report.get('toolkit_version') != VERSION or
            report.get('kind') != 'install-report' or report.get('package') != plan['package'] or
            report.get('android_user') != plan['android_user']):
        raise ValueError('Report identity differs from the plan')
    after = installed_snapshot(plan['package'], plan['android_user'])
    ok = matches_target(after, manifest, plan['android_user'])
    result = {**report, 'status': 'pass' if ok else 'unknown', 'phase': 'reconciled_by_read_only_observation',
              'reconciled_at': now(), 'post_install_hashes_match': ok, 'installed_after': after,
              'prior_status': report['status'], 'prior_report_sha256': digest(report_path)}
    if Path(output).exists():
        raise ValueError('Refusing to overwrite reconciliation evidence')
    atomic_json(output, result)
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest='command', required=True)
    plan = sub.add_parser('plan')
    plan.add_argument('--set', required=True)
    plan.add_argument('--android-user', type=int, required=True)
    plan.add_argument('--out-dir', required=True)
    plan.add_argument('--allow-other-users', action='store_true', help='Only when updating code for these other users is in the authorized task')
    apply = sub.add_parser('apply')
    apply.add_argument('--plan', required=True)
    apply.add_argument('--expected-plan-sha256', required=True)
    apply.add_argument('--out-dir', required=True)
    rec = sub.add_parser('reconcile')
    rec.add_argument('--report', required=True)
    rec.add_argument('--out', required=True)
    a = p.parse_args()
    os.umask(0o077)
    # This lock coordinates this toolkit, not unrelated PackageManager callers.
    lock_path = Path.home() / 'rebro/.locks/install.lock'
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open('a+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if a.command == 'plan':
            result = make_plan(a.set, a.android_user, a.out_dir, a.allow_other_users)
        elif a.command == 'apply':
            result = apply_plan(a.plan, a.expected_plan_sha256, a.out_dir)
        else:
            result = reconcile(a.report, a.out)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return {'ready': 0, 'pass': 0, 'blocked': 3, 'unknown': 4}.get(result.get('status'), 1)


if __name__ == '__main__':
    raise SystemExit(main())
