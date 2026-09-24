#!/usr/bin/env python3
"""Bounded file RPC for an explicitly linked Termux directory. No shell evaluation."""
import base64
import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
import time
import uuid

CHUNK = 24 * 1024
MAX_FILE = 256 * 1024 * 1024
MAX_ENTRIES = 20000
DIR_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW


def relative(root, value):
    if not isinstance(value, str) or '\x00' in value:
        raise ValueError('Invalid path')
    if value.startswith('/'):
        if value == root:
            value = ''
        elif value.startswith(root.rstrip('/') + '/'):
            value = value[len(root.rstrip('/')) + 1:]
        else:
            raise ValueError('Path is outside the linked directory')
    parts = value.split('/') if value else []
    if any(p in ('', '.', '..') for p in parts):
        raise ValueError('Empty, dot and parent path components are not allowed')
    return parts


@contextlib.contextmanager
def directory(root_fd, parts, create=False):
    fd = os.dup(root_fd)
    try:
        for part in parts:
            if create:
                try:
                    os.mkdir(part, 0o700, dir_fd=fd)
                except FileExistsError:
                    pass
            child = os.open(part, DIR_FLAGS, dir_fd=fd)
            os.close(fd)
            fd = child
        yield fd
    finally:
        os.close(fd)


def revision(info):
    return hashlib.sha256(('%s:%s:%s:%s:%s' % (
        info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)).encode()).hexdigest()


def entry(path, info):
    return dict(path=path, name=path.rsplit('/', 1)[-1], isDirectory=stat.S_ISDIR(info.st_mode),
                sizeBytes=info.st_size, updatedAt=info.st_mtime_ns // 1000000,
                revision=revision(info), symlink=stat.S_ISLNK(info.st_mode))


def regular_info(fd, name):
    try:
        info = os.stat(name, dir_fd=fd, follow_symlinks=False)
    except FileNotFoundError:
        return None
    if not stat.S_ISREG(info.st_mode):
        raise ValueError('Expected a regular file; symbolic links are not followed')
    return info


def require_revision(info, expected):
    actual = revision(info) if info else ''
    if expected is not None and actual != expected:
        raise ValueError('File changed outside the editor. Reload it before saving (revision_conflict).')


def remove_at(fd, name, recursive):
    info = os.stat(name, dir_fd=fd, follow_symlinks=False)
    if stat.S_ISDIR(info.st_mode):
        if recursive:
            with directory(fd, [name]) as child:
                for sub in os.listdir(child):
                    remove_at(child, sub, True)
        os.rmdir(name, dir_fd=fd)
    else:
        os.unlink(name, dir_fd=fd)


def handle(root, request, state_dir):
    if not isinstance(root, str) or not os.path.isabs(root) or '\x00' in root:
        raise ValueError('Choose an absolute Termux directory')
    root = root.rstrip('/') or '/'
    if request.get('action') == 'attach':
        root = os.path.realpath(root)
    # The attachment step resolves the root explicitly. Subsequent requests must keep it canonical.
    if os.path.realpath(root) != root:
        raise ValueError('Directory path changed or contains a symbolic link; attach its real path')
    state_dir = Path(state_dir)
    state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    action = request['action']
    parts = relative(root, request.get('path', ''))
    fd = os.open(root, DIR_FLAGS)
    try:
        with (state_dir / 'lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            # Interrupted imports are private scratch, never project files.
            for item in state_dir.glob('*.json'):
                if time.time() - item.stat().st_mtime > 86400:
                    item.with_suffix('.data').unlink(missing_ok=True)
                    item.unlink(missing_ok=True)
            if action in ('probe', 'attach'):
                with directory(fd, parts) as target:
                    return dict(success=True, root=root, entry=entry('/'.join(parts), os.fstat(target)))
            if action in ('list', 'tree'):
                rows = []
                truncated = False
                def walk(parent, components, depth):
                    nonlocal truncated
                    names = os.listdir(parent)
                    if len(names) > MAX_ENTRIES:
                        raise ValueError('Directory has too many entries; select a smaller directory')
                    for name in sorted(names):
                        info = os.stat(name, dir_fd=parent, follow_symlinks=False)
                        row = entry('/'.join(components + [name]), info)
                        row['depth'] = depth
                        rows.append(row)
                        if action == 'tree' and len(rows) >= 5000:
                            truncated = True
                            return
                        if action == 'tree' and row['isDirectory'] and depth < 10:
                            with directory(parent, [name]) as child:
                                walk(child, components + [name], depth + 1)
                            if truncated:
                                return
                with directory(fd, parts) as target:
                    walk(target, parts, 1)
                if action == 'tree':
                    # Tree pages keep RPC responses below Android's result-bundle limit too.
                    pass
                cursor = max(0, int(request.get('cursor', 0)))
                page = rows[cursor:cursor + 48]
                return dict(success=True, entries=page, truncated=truncated,
                            next_cursor=cursor + len(page) if cursor + len(page) < len(rows) else None)
            if action == 'mkdir':
                with directory(fd, parts, create=True) as target:
                    return dict(success=True, entry=entry('/'.join(parts), os.fstat(target)))
            if not parts and action not in ('begin', 'chunk', 'commit', 'abort'):
                raise ValueError('The linked directory itself cannot be changed or removed')
            if action in ('stat', 'read'):
                with directory(fd, parts[:-1]) as parent:
                    file_fd = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
                    with os.fdopen(file_fd, 'rb') as stream:
                        info = os.fstat(stream.fileno())
                        if not stat.S_ISREG(info.st_mode):
                            raise ValueError('Expected a regular file')
                        require_revision(info, request.get('revision'))
                        result = dict(success=True, entry=entry('/'.join(parts), info))
                        if action == 'read':
                            offset = int(request.get('offset', 0))
                            if offset < 0 or offset > info.st_size:
                                raise ValueError('Invalid read offset')
                            stream.seek(offset)
                            data = stream.read(CHUNK)
                            require_revision(os.fstat(stream.fileno()), revision(info))
                            result.update(data=base64.b64encode(data).decode(), next_offset=offset + len(data),
                                          eof=offset + len(data) >= info.st_size)
                        return result
            if action == 'delete':
                with directory(fd, parts[:-1]) as parent:
                    remove_at(parent, parts[-1], bool(request.get('recursive', False)))
                return dict(success=True)
            if action == 'move':
                dest = relative(root, request['target'])
                if not dest:
                    raise ValueError('Cannot replace the linked directory')
                with directory(fd, parts[:-1]) as parent, directory(fd, dest[:-1]) as target:
                    source = os.stat(parts[-1], dir_fd=parent, follow_symlinks=False)
                    if stat.S_ISLNK(source.st_mode):
                        raise ValueError('Symbolic links cannot be moved through the workspace')
                    try:
                        os.stat(dest[-1], dir_fd=target, follow_symlinks=False)
                    except FileNotFoundError:
                        pass
                    else:
                        if not request.get('overwrite', False):
                            raise ValueError('Destination already exists')
                        regular_info(target, dest[-1])
                    os.rename(parts[-1], dest[-1], src_dir_fd=parent, dst_dir_fd=target)
                    return dict(success=True, entry=entry('/'.join(dest), os.stat(dest[-1], dir_fd=target, follow_symlinks=False)))
            if action not in ('begin', 'chunk', 'commit', 'abort'):
                raise ValueError('Unknown workspace action')
            transfer = str(uuid.UUID(request['transfer_id']))
            meta_file = state_dir / (transfer + '.json')
            data_file = state_dir / (transfer + '.data')
            if action == 'begin':
                if not parts:
                    raise ValueError('A destination file is required')
                if meta_file.exists():
                    saved = json.loads(meta_file.read_text())
                    if saved['root'] != root or saved['path'] != '/'.join(parts):
                        raise ValueError('Transfer ID belongs to a different destination')
                    return dict(success=True)
                if sum(p.stat().st_size for p in state_dir.glob('*.data')) > 512 * 1024 * 1024:
                    raise ValueError('Workspace transfer quota exceeded; abort unfinished imports')
                with directory(fd, parts[:-1], create=bool(request.get('parents', False))) as parent:
                    info = regular_info(parent, parts[-1])
                    if info and not request.get('overwrite', False):
                        raise ValueError('Destination already exists')
                    require_revision(info, request.get('revision'))
                    meta = dict(root=root, path='/'.join(parts), revision=revision(info) if info else '',
                                mode=stat.S_IMODE(info.st_mode) if info else 0o600)
                data_file.touch(mode=0o600, exist_ok=False)
                meta_file.write_text(json.dumps(meta))
                return dict(success=True)
            meta = json.loads(meta_file.read_text())
            if meta['root'] != root:
                raise ValueError('Transfer belongs to another workspace')
            if action == 'abort':
                data_file.unlink(missing_ok=True)
                meta_file.unlink(missing_ok=True)
                return dict(success=True)
            if 'committed' in meta:
                return dict(success=True, entry=meta['committed'])
            if action == 'chunk':
                data = base64.b64decode(request['data'], validate=True)
                offset = int(request['offset'])
                if len(data) > CHUNK or offset < 0 or offset + len(data) > MAX_FILE:
                    raise ValueError('Transfer exceeds chunk or file limit')
                with data_file.open('r+b') as stream:
                    size = os.fstat(stream.fileno()).st_size
                    if offset > size:
                        raise ValueError('Transfer has a gap')
                    stream.seek(offset)
                    if offset < size:
                        if offset + len(data) > size or stream.read(len(data)) != data:
                            raise ValueError('Retry data does not match the accepted chunk')
                    else:
                        stream.write(data)
                return dict(success=True)
            parts = relative(root, meta['path'])
            if data_file.stat().st_size != int(request['size']):
                raise ValueError('Transfer size mismatch')
            with directory(fd, parts[:-1]) as parent:
                require_revision(regular_info(parent, parts[-1]), meta['revision'])
                temp = '.rebro-save-' + transfer
                temp_fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, meta['mode'], dir_fd=parent)
                try:
                    with os.fdopen(temp_fd, 'wb') as output, data_file.open('rb') as source:
                        while True:
                            block = source.read(128 * 1024)
                            if not block:
                                break
                            output.write(block)
                        output.flush()
                        os.fsync(output.fileno())
                    require_revision(regular_info(parent, parts[-1]), meta['revision'])
                    os.replace(temp, parts[-1], src_dir_fd=parent, dst_dir_fd=parent)
                    os.fsync(parent)
                    result = entry(meta['path'], os.stat(parts[-1], dir_fd=parent, follow_symlinks=False))
                finally:
                    try:
                        os.unlink(temp, dir_fd=parent)
                    except FileNotFoundError:
                        pass
            meta['committed'] = result
            meta_file.write_text(json.dumps(meta))
            data_file.unlink(missing_ok=True)
            return dict(success=True, entry=result)
    finally:
        os.close(fd)


def main():
    try:
        request = json.loads(base64.b64decode(sys.argv[1], validate=True))
        result = handle(request['root'], request, sys.argv[2])
    except Exception as error:
        result = dict(success=False, error=type(error).__name__, detail=str(error))
    print(json.dumps(result, ensure_ascii=True, separators=(',', ':')))


if __name__ == '__main__':
    main()
