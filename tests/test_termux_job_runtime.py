import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / 'app/src/main/assets/termux/job_runtime.py'
OWNER = 'a' * 24


class JobRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="rikkahub-job-test-", dir=str(SOURCE.parents[6]))
        self.folder = Path(self.temp.name)
        self.helper = self.folder / 'runtime.py'
        shutil.copy(SOURCE, self.helper)

    def tearDown(self):
        for spec in self.folder.glob('data/*/*/request.json'):
            state = self.rpc('wait', job_id=spec.parent.name, timeout_seconds=1)
            if state.get('state') in ('starting', 'running', 'cancelling'):
                self.rpc('cancel', job_id=spec.parent.name, timeout_seconds=5)
        self.temp.cleanup()

    def rpc(self, action, **kwargs):
        request = dict(action=action, owner=OWNER, platform_boot_marker='android-boot-count:10')
        request.update(kwargs)
        payload = base64.b64encode(json.dumps(request).encode()).decode()
        result = subprocess.run([sys.executable, str(self.helper), payload], capture_output=True, text=True, timeout=15)
        return json.loads(result.stdout)

    def start(self, command, operation_id='test', **kwargs):
        return self.rpc('start', command=command, operation_id=operation_id, working_dir=str(self.folder), **kwargs)

    def finish(self, result):
        return self.rpc('wait', job_id=result['job_id'], timeout_seconds=8)

    def test_duplicate_launch_and_conflict(self):
        first = self.start("printf x >> counter; sleep .2; printf done")
        second = self.start("printf x >> counter; sleep .2; printf done")
        self.assertEqual(first['job_id'], second['job_id'])
        self.assertTrue(second['reused'])
        self.assertEqual('completed', self.finish(first)['state'])
        self.assertEqual('x', (self.folder / 'counter').read_text())
        self.assertEqual('operation_id_conflict', self.start('echo different')['error'])

    def test_wait_carries_separate_output_and_continuation_cursors(self):
        expected = 'А😀你好\n' * 300
        job = self.start(f"{sys.executable} - <<'EOF'\nimport sys\nprint({expected!r}, end='')\nprint('problem', file=sys.stderr, end='')\nEOF")
        reply = self.rpc('wait', job_id=job['job_id'], timeout_seconds=8, output_max_bytes=257)
        self.assertEqual('problem', reply['stderr'])
        chunks = [reply['stdout']]
        while reply['stdout_has_more']:
            reply = self.rpc('wait', job_id=job['job_id'], timeout_seconds=1, output_max_bytes=257,
                             stdout_cursor=reply['stdout_next_cursor'], stderr_cursor=reply['stderr_next_cursor'])
            self.assertEqual('', reply['stderr'])
            chunks.append(reply['stdout'])
        self.assertEqual(expected, ''.join(chunks))
        self.assertEqual('completed', reply['state'])

    def test_list_includes_global_counts_beyond_display_page(self):
        job = self.start('sleep 3')
        listing = self.rpc('list')
        self.assertEqual(1, listing['total_jobs'])
        self.assertEqual(1, listing['active_jobs'])
        self.finish(job)
        self.assertEqual(0, self.rpc('list')['active_jobs'])

    def test_nonzero_exit_and_separate_stderr(self):
        job = self.start("printf out; printf problem >&2; exit 7")
        status = self.finish(job)
        self.assertEqual(('failed', 7), (status['state'], status['exit_code']))
        self.assertEqual('out', self.rpc('read', job_id=job['job_id'])['text'])
        self.assertEqual('problem', self.rpc('read', job_id=job['job_id'], stream='stderr')['text'])

    def test_short_wait_does_not_cancel_and_fresh_client_recovers(self):
        job = self.start('sleep 2; printf recovered')
        early = self.rpc('wait', job_id=job['job_id'], timeout_seconds=1)
        self.assertEqual('running', early['state'])
        self.assertTrue(early['wait_timed_out'])
        self.assertEqual(job['job_id'], self.rpc('list')['jobs'][0]['job_id'])
        self.assertEqual('completed', self.finish(job)['state'])

    def test_cancel_group_and_execution_deadline(self):
        job = self.start("trap '' TERM; sleep 30 & wait")
        cancelled = self.rpc('cancel', job_id=job['job_id'], timeout_seconds=6)
        self.assertTrue(cancelled['cancel_confirmed'])
        self.assertEqual('cancelled', cancelled['state'])
        timed = self.start('sleep 30', operation_id='timeout', execution_timeout_seconds=1)
        self.assertEqual('timed_out', self.finish(timed)['state'])

    def test_utf8_cursor_roundtrip(self):
        expected = 'А😀你好\n' * 300
        script = f"{sys.executable} -c \"print('А😀你好\\\\n' * 300, end='')\""
        # Quoted heredoc avoids accidental escaping differences in the command under test.
        script = f"{sys.executable} - <<'EOF'\nprint({expected!r}, end='')\nEOF"
        job = self.start(script)
        self.finish(job)
        parts, cursor = [], 0
        while True:
            page = self.rpc('read', job_id=job['job_id'], cursor=cursor, max_bytes=257)
            parts.append(page['text'])
            cursor = page['next_cursor']
            if not page['has_more']:
                break
        self.assertEqual(expected, ''.join(parts))

    def test_log_cap_is_explicit_and_forget_keeps_dedup_receipt(self):
        command = f"{sys.executable} -c \"import sys; sys.stdout.write('x' * (9*1024*1024))\""
        job = self.start(command)
        final = self.finish(job)
        self.assertTrue(final['logs_truncated']['stdout'])
        self.assertEqual(8*1024*1024, (Path(final['log_path']) / 'stdout.log').stat().st_size)
        self.rpc('forget', job_id=job['job_id'])
        again = self.start(command)
        self.assertTrue(again['reused'])
        self.assertTrue(again['logs_removed'])

    def test_other_owner_and_traversal_are_rejected(self):
        job = self.start('printf secret')
        self.finish(job)
        self.assertEqual('job_not_found', self.rpc('read', owner='b'*24, job_id=job['job_id'])['error'])
        self.assertEqual('ValueError', self.rpc('read', job_id='../escape')['error'])

    def test_stale_identity_never_signals_reused_pid(self):
        job = self.start('true')
        self.finish(job)
        state_path = self.folder / 'data' / OWNER / job['job_id'] / 'status.json'
        state = json.loads(state_path.read_text())
        state.update(state='running', worker={'pid': 1, 'start_ticks': '0', 'boot_id': 'previous-boot'})
        state_path.write_text(json.dumps(state))
        observed = self.rpc('cancel', job_id=job['job_id'], timeout_seconds=1)
        self.assertEqual('unknown', observed['state'])
        self.assertFalse(observed['cancel_confirmed'])
        self.assertFalse((state_path.parent / 'cancel.request').exists())

    def test_platform_boot_marker_and_reboot_observation(self):
        job = self.start('sleep 2; printf survived')
        self.assertEqual('android-boot-count:10', job['worker']['boot_id'])
        after_reboot = self.rpc('cancel', job_id=job['job_id'], platform_boot_marker='android-boot-count:11')
        self.assertEqual('unknown', after_reboot['state'])
        self.assertFalse(after_reboot['cancel_confirmed'])
        self.assertFalse((Path(job['log_path']) / 'cancel.request').exists())
        self.assertEqual('completed', self.finish(job)['state'])


if __name__ == '__main__':
    unittest.main()

