import base64
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
import uuid

SOURCE = Path(__file__).resolve().parents[1] / 'app/src/main/assets/termux/workspace_runtime.py'
spec = importlib.util.spec_from_file_location('workspace_runtime', SOURCE)
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class WorkspaceRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / 'project'
        self.root.mkdir()
        self.state = self.base / 'state'

    def rpc(self, action, path='', **extra):
        return runtime.handle(str(self.root), dict(action=action, path=path, **extra), self.state)

    def write(self, path, data, **options):
        token = str(uuid.uuid4())
        self.rpc('begin', path, transfer_id=token, **options)
        for offset in range(0, len(data), runtime.CHUNK):
            self.rpc('chunk', transfer_id=token, offset=offset,
                     data=base64.b64encode(data[offset:offset + runtime.CHUNK]).decode())
        return self.rpc('commit', transfer_id=token, size=len(data)), token

    def test_binary_large_file_and_paged_read(self):
        data = bytes(range(256)) * 500
        self.write('binary.dat', data)
        result = bytearray()
        revision = self.rpc('stat', 'binary.dat')['entry']['revision']
        while True:
            page = self.rpc('read', 'binary.dat', offset=len(result), revision=revision)
            result.extend(base64.b64decode(page['data']))
            if page['eof']:
                break
        self.assertEqual(data, result)

    def test_empty_file_and_commit_retry(self):
        result, token = self.write('empty', b'')
        self.assertEqual(0, result['entry']['sizeBytes'])
        self.assertEqual(result, self.rpc('commit', transfer_id=token, size=0))

    def test_duplicate_chunk_must_match(self):
        token = str(uuid.uuid4())
        self.rpc('begin', 'file', transfer_id=token)
        self.rpc('chunk', transfer_id=token, offset=0, data='YWJj')
        self.rpc('chunk', transfer_id=token, offset=0, data='YWJj')
        with self.assertRaisesRegex(ValueError, 'Retry data'):
            self.rpc('chunk', transfer_id=token, offset=0, data='eHl6')
        self.rpc('commit', transfer_id=token, size=3)
        self.assertEqual(b'abc', (self.root / 'file').read_bytes())

    def test_save_conflict_preserves_external_edit(self):
        self.write('file', b'original')
        rev = self.rpc('stat', 'file')['entry']['revision']
        (self.root / 'file').write_bytes(b'external')
        with self.assertRaisesRegex(ValueError, 'revision_conflict'):
            self.write('file', b'mine', overwrite=True, revision=rev)
        self.assertEqual(b'external', (self.root / 'file').read_bytes())

    def test_external_change_during_transfer(self):
        self.write('file', b'original')
        token = str(uuid.uuid4())
        self.rpc('begin', 'file', transfer_id=token, overwrite=True)
        self.rpc('chunk', transfer_id=token, offset=0, data='YWJj')
        (self.root / 'file').write_bytes(b'external')
        with self.assertRaisesRegex(ValueError, 'revision_conflict'):
            self.rpc('commit', transfer_id=token, size=3)
        self.assertEqual(b'external', (self.root / 'file').read_bytes())

    def test_changed_file_rejected_between_read_pages(self):
        self.write('file', b'A' * 50000)
        rev = self.rpc('stat', 'file')['entry']['revision']
        self.rpc('read', 'file', revision=rev)
        (self.root / 'file').write_bytes(b'B' * 50000)
        with self.assertRaisesRegex(ValueError, 'revision_conflict'):
            self.rpc('read', 'file', offset=runtime.CHUNK, revision=rev)

    def test_paths_and_symlinks_cannot_escape(self):
        outside = self.base / 'outside'
        outside.mkdir()
        (outside / 'keep').write_text('safe')
        (self.root / 'link').symlink_to(outside, target_is_directory=True)
        for path in ('../outside/keep', str(outside / 'keep'), 'link/keep'):
            with self.subTest(path=path), self.assertRaises((ValueError, OSError)):
                self.rpc('read', path)
        with self.assertRaises(OSError):
            self.write('link/new', b'bad')
        self.assertFalse((outside / 'new').exists())

    def test_symlink_file_and_fifo_are_not_read(self):
        outside = self.base / 'secret'
        outside.write_bytes(b'private')
        (self.root / 'link').symlink_to(outside)
        os.mkfifo(self.root / 'pipe')
        for path in ('link', 'pipe'):
            with self.subTest(path=path), self.assertRaises((ValueError, OSError)):
                self.rpc('read', path)

    def test_root_cannot_be_deleted_or_replaced(self):
        for action in ('delete', 'move', 'begin'):
            with self.subTest(action=action), self.assertRaises(ValueError):
                self.rpc(action, target='a', transfer_id=str(uuid.uuid4()))
        self.assertTrue(self.root.is_dir())

    def test_create_move_delete_unicode_and_quotes(self):
        self.rpc('mkdir', "проект/it's okay")
        self.write("проект/it's okay/данные.txt", 'привет'.encode())
        self.rpc('move', "проект/it's okay/данные.txt", target='result.txt')
        self.assertEqual('привет', (self.root / 'result.txt').read_text())
        self.rpc('delete', 'проект', recursive=True)
        self.assertTrue((self.root / 'result.txt').exists())

    def test_delete_symlink_does_not_delete_target(self):
        outside = self.base / 'outside'
        outside.mkdir()
        (outside / 'keep').write_text('safe')
        (self.root / 'link').symlink_to(outside, target_is_directory=True)
        self.rpc('delete', 'link', recursive=True)
        self.assertTrue((outside / 'keep').exists())

    def test_import_never_silently_overwrites(self):
        self.write('file', b'first')
        with self.assertRaisesRegex(ValueError, 'already exists'):
            self.write('file', b'second')
        self.assertEqual(b'first', (self.root / 'file').read_bytes())

    def test_pagination_and_tree_do_not_follow_symlinks(self):
        for index in range(61):
            (self.root / ('file%03d' % index)).touch()
        (self.root / 'cycle').symlink_to(self.root)
        first = self.rpc('list')
        second = self.rpc('list', cursor=first['next_cursor'])
        self.assertEqual(62, len(first['entries']) + len(second['entries']))
        self.assertIsNone(second['next_cursor'])
        tree = self.rpc('tree')
        self.assertFalse(tree['truncated'])

    def test_aborted_transfer_does_not_change_project(self):
        token = str(uuid.uuid4())
        self.rpc('begin', 'file', transfer_id=token)
        self.rpc('chunk', transfer_id=token, offset=0, data='YWJj')
        self.rpc('abort', transfer_id=token)
        self.assertFalse((self.root / 'file').exists())
        self.assertFalse((self.state / (token + '.data')).exists())

    def test_gaps_oversized_chunks_and_incomplete_commit_rejected(self):
        token = str(uuid.uuid4())
        self.rpc('begin', 'file', transfer_id=token)
        for offset, data in ((1, b'a'), (0, b'a' * (runtime.CHUNK + 1))):
            with self.assertRaises(ValueError):
                self.rpc('chunk', transfer_id=token, offset=offset, data=base64.b64encode(data).decode())
        with self.assertRaisesRegex(ValueError, 'size mismatch'):
            self.rpc('commit', transfer_id=token, size=4)

    def test_concurrent_saves_from_same_revision_conflict(self):
        self.write('file', b'initial')
        first, second = str(uuid.uuid4()), str(uuid.uuid4())
        for token in (first, second):
            self.rpc('begin', 'file', transfer_id=token, overwrite=True)
            self.rpc('chunk', transfer_id=token, offset=0, data='YWJj')
        self.rpc('commit', transfer_id=first, size=3)
        with self.assertRaisesRegex(ValueError, 'revision_conflict'):
            self.rpc('commit', transfer_id=second, size=3)

    def test_parent_symlink_replaced_during_transfer(self):
        (self.root / 'sub').mkdir()
        outside = self.base / 'outside'
        outside.mkdir()
        token = str(uuid.uuid4())
        self.rpc('begin', 'sub/file', transfer_id=token)
        (self.root / 'sub').rmdir()
        (self.root / 'sub').symlink_to(outside, target_is_directory=True)
        with self.assertRaises(OSError):
            self.rpc('commit', transfer_id=token, size=0)
        self.assertFalse((outside / 'file').exists())

    def test_attach_resolves_selected_alias(self):
        alias = self.base / 'alias'
        alias.symlink_to(self.root, target_is_directory=True)
        result = runtime.handle(str(alias), dict(action='attach'), self.state)
        self.assertEqual(str(self.root), result['root'])

    def test_save_preserves_executable_permissions(self):
        self.write('script', b'old')
        (self.root / 'script').chmod(0o755)
        self.write('script', b'new', overwrite=True)
        self.assertEqual(0o755, (self.root / 'script').stat().st_mode & 0o777)


if __name__ == '__main__':
    unittest.main()
