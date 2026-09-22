import base64
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
import zipfile

MODULE = Path(__file__).resolve().parents[1] / 'app/src/main/assets/termux/skill_runtime.py'
spec = importlib.util.spec_from_file_location('skill_runtime', MODULE)
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class SkillRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'app-skills'
        self.key = hashlib.sha256(b'skill').hexdigest()

    def archive(self, files=None):
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as z:
            for name, content in (files or {'SKILL.md': '# Example'}).items():
                z.writestr(name, content)
        return output.getvalue()

    def request(self, action, archive_data, **extra):
        return runtime.dispatch(self.root, dict(action=action, key=self.key,
            revision=hashlib.sha256(archive_data).hexdigest(), **extra))

    def transfer(self, data):
        self.request('begin', data, archive_bytes=len(data))
        for offset in range(0, len(data), 49152):
            self.request('chunk', data, offset=offset, data=base64.b64encode(data[offset:offset+49152]).decode())
        return self.request('commit', data)

    def test_complete_package_executes_with_relative_binary_asset(self):
        image = bytes(range(256)) * 5
        script = '#!/usr/bin/env python3\nfrom pathlib import Path\nprint(sum(Path("assets/файл.bin").read_bytes()))\n'
        data = self.archive({'SKILL.md': '# test', 'scripts/test.py': script, 'assets/файл.bin': image})
        result = self.transfer(data)
        root = Path(result['skill_root'])
        self.assertEqual((root / 'assets/файл.bin').read_bytes(), image)
        self.assertTrue(os.access(root / 'scripts/test.py', os.X_OK))
        output = subprocess.check_output(['python3', 'scripts/test.py'], cwd=root, text=True)
        self.assertEqual(int(output.strip()), sum(image))
        self.assertTrue(self.request('probe', data)['ready'])
        self.assertEqual(runtime.dispatch(self.root, {'action': 'status'})['packages'], 1)

    def test_copy_does_not_execute_script(self):
        sentinel = Path(self.temp.name) / 'executed'
        data = self.archive({'SKILL.md': '# test', 'scripts/install.sh': '#!/bin/sh\ntouch ' + str(sentinel)})
        self.transfer(data)
        self.assertFalse(sentinel.exists())

    def test_two_versions_preserve_old_files(self):
        first = self.transfer(self.archive({'SKILL.md': '# v1'}))
        second = self.transfer(self.archive({'SKILL.md': '# v2'}))
        self.assertNotEqual(first['skill_root'], second['skill_root'])
        self.assertEqual((Path(first['skill_root']) / 'SKILL.md').read_text(), '# v1')

    def test_interrupted_transfer_and_idempotent_chunk_retry(self):
        data = self.archive({'SKILL.md': '# example', 'blob': os.urandom(120000)})
        self.request('begin', data, archive_bytes=len(data))
        chunk = base64.b64encode(data[:49152]).decode()
        self.request('chunk', data, offset=0, data=chunk)
        self.request('chunk', data, offset=0, data=chunk)
        self.assertFalse(self.request('probe', data)['ready'])
        self.transfer(data)
        self.assertTrue(self.request('probe', data)['ready'])

    def test_corruption_never_installs(self):
        data = self.archive()
        self.request('begin', data, archive_bytes=len(data))
        self.request('chunk', data, offset=0, data=base64.b64encode(b'bad').decode())
        with self.assertRaisesRegex(ValueError, 'archive_hash_mismatch'):
            self.request('commit', data)
        self.assertFalse(self.request('probe', data)['ready'])

    def test_edited_copy_is_detected_without_overwriting(self):
        data = self.archive()
        result = self.transfer(data)
        target = Path(result['skill_root']) / 'SKILL.md'
        target.write_text('edited locally')
        self.assertFalse(self.request('probe', data)['ready'])
        with self.assertRaisesRegex(ValueError, 'revision_exists'):
            self.transfer(data)
        self.assertEqual(target.read_text(), 'edited locally')

    def test_unsafe_paths_and_missing_skill_rejected(self):
        for bad in ('../outside', '/absolute', 'a/../../outside', 'a\\b'):
            with self.subTest(bad=bad), self.assertRaisesRegex(ValueError, 'unsafe_package_path'):
                self.transfer(self.archive({'SKILL.md': '# test', bad: 'bad'}))
        with self.assertRaisesRegex(ValueError, 'missing_skill_md'):
            self.transfer(self.archive({'other.txt': 'no instructions'}))

    def test_symlink_zip_rejected(self):
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w') as z:
            z.writestr('SKILL.md', '# test')
            info = zipfile.ZipInfo('link')
            info.create_system = 3
            info.external_attr = (stat.S_IFLNK | 0o777) << 16
            z.writestr(info, '/tmp/outside')
        with self.assertRaisesRegex(ValueError, 'unsupported_file_type'):
            self.transfer(output.getvalue())

    def test_caps_and_chunk_order(self):
        data = self.archive()
        self.request('begin', data, archive_bytes=len(data))
        with self.assertRaisesRegex(ValueError, 'missing_chunk'):
            self.request('chunk', data, offset=20, data=base64.b64encode(b'x').decode())
        with self.assertRaisesRegex(ValueError, 'too_many_files'):
            self.transfer(self.archive({str(i): 'x' for i in range(201)}))
        with self.assertRaisesRegex(ValueError, 'archive_too_large'):
            self.request('begin', data, archive_bytes=runtime.MAX_ARCHIVE+1)

    def test_clear_only_managed_namespace(self):
        outside = Path(self.temp.name) / 'keep.txt'
        outside.write_text('keep')
        self.transfer(self.archive())
        runtime.dispatch(self.root, {'action': 'clear'})
        self.assertEqual(outside.read_text(), 'keep')
        self.assertFalse((self.root / 'packages').exists())


if __name__ == '__main__':
    unittest.main()
