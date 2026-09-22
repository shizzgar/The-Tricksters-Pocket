"""Private, content-addressed skill packages. RPC transfers data; it never runs a skill.

The Android bridge sends bounded chunks through RUN_COMMAND, so no shared-storage
permission, localhost server, shell interpolation of filenames or Binder-sized ZIP is needed.
"""
import base64
import fcntl
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys
import tempfile
import time
import zipfile

MAX_BYTES = 20 * 1024 * 1024
MAX_ARCHIVE = 22 * 1024 * 1024
MAX_FILES = 200
MAX_STORE = 256 * 1024 * 1024
HASH = re.compile(r"[a-f0-9]{64}\Z")


def digest_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            h.update(block)
    return h.hexdigest()


def safe_path(value):
    path = PurePosixPath(value)
    if (not value or "\\" in value or "\x00" in value or path.is_absolute()
            or any(p in ("", ".", "..") for p in value.split("/"))):
        raise ValueError("unsafe_package_path")
    return path


def child(root, name):
    path = root / name
    if path.is_symlink():
        raise ValueError("symlink_in_managed_store")
    return path


def usage(root):
    return sum(p.stat().st_size for p in root.rglob("*") if p.is_file() and not p.is_symlink())


def extract(archive, target):
    total = 0
    manifest = []
    seen = set()
    with zipfile.ZipFile(archive) as z:
        if len(z.infolist()) > MAX_FILES:
            raise ValueError("too_many_files")
        for item in z.infolist():
            rel = safe_path(item.filename.rstrip("/") if item.is_dir() else item.filename)
            name = str(rel)
            mode = item.external_attr >> 16
            if stat.S_ISLNK(mode) or (stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR)):
                raise ValueError("unsupported_file_type")
            if name in seen or name == ".rikkahub-manifest.json":
                raise ValueError("duplicate_or_reserved_path")
            seen.add(name)
            dest = target / rel
            if item.is_dir():
                dest.mkdir(parents=True, exist_ok=True, mode=0o700)
                continue
            total += item.file_size
            if total > MAX_BYTES:
                raise ValueError("package_too_large")
            dest.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            written = 0
            with z.open(item) as src, open(dest, "xb") as out:
                for block in iter(lambda: src.read(65536), b""):
                    written += len(block)
                    if written > item.file_size or written > MAX_BYTES:
                        raise ValueError("package_too_large")
                    out.write(block)
            with open(dest, "rb") as stream:
                shebang = stream.read(2) == b"#!"
            # ZIP imports do not reliably retain Unix mode bits. Scripts stay runnable;
            # interpreter dependencies remain the user's Termux environment.
            executable = bool(mode & 0o111) or shebang or dest.suffix in (".sh", ".py", ".js", ".rb", ".pl")
            os.chmod(dest, 0o700 if executable else 0o600)
            manifest.append({"path": name, "bytes": written, "sha256": digest_file(dest)})
    if not (target / "SKILL.md").is_file():
        raise ValueError("missing_skill_md")
    return manifest


def dispatch(root, request):
    root = Path(root)
    # Only this application's explicit namespace is ever mutated.
    if root.is_symlink() or root.resolve() != root.absolute():
        raise ValueError("unsafe_store_root")
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    lock = child(root, ".lock")
    with open(lock, "a") as stream:
        fcntl.flock(stream, fcntl.LOCK_EX)
        action = request["action"]
        if action == "status":
            manifests = list(root.glob("packages/*/*/.rikkahub-manifest.json"))
            return {"success": True, "packages": len({p.parent.parent.name for p in manifests}),
                    "revisions": len(manifests), "bytes": usage(root), "root": str(root)}
        if action == "clear":
            for name in ("packages", "incoming"):
                directory = child(root, name)
                if directory.exists():
                    shutil.rmtree(directory)
            return {"success": True, "packages": 0, "bytes": 0, "root": str(root)}
        key, revision = request["key"], request["revision"]
        if not HASH.fullmatch(key) or not HASH.fullmatch(revision):
            raise ValueError("invalid_package_identity")
        packages = child(root, "packages")
        package = child(packages, key)
        target = child(package, revision)
        manifest_path = child(target, ".rikkahub-manifest.json")
        if action == "probe":
            ready = False
            if manifest_path.is_file():
                manifest = json.loads(manifest_path.read_text())
                ready = bool(manifest) and any(f["path"] == "SKILL.md" for f in manifest) and all((target / safe_path(f["path"])).resolve() == (target / f["path"]).absolute()
                            and (target / f["path"]).is_file()
                            and digest_file(target / f["path"]) == f["sha256"] for f in manifest)
            return {"success": True, "ready": ready, "skill_root": str(target), "revision": revision}
        incoming = child(root, "incoming")
        incoming.mkdir(exist_ok=True, mode=0o700)
        upload = child(incoming, key + "-" + revision + ".zip")
        if action == "begin":
            # Abandoned partial uploads are disposable. Installed revisions are never
            # collected implicitly: an existing job may still be using one.
            for old in incoming.glob("*.zip"):
                if not old.is_symlink() and time.time() - old.stat().st_mtime > 86400:
                    old.unlink()
            if usage(root) + int(request["archive_bytes"]) * 2 > MAX_STORE:
                raise ValueError("store_quota_exceeded")
            if not 0 < int(request["archive_bytes"]) <= MAX_ARCHIVE:
                raise ValueError("archive_too_large")
            with open(upload, "wb"):
                pass
            return {"success": True}
        if action == "chunk":
            data = base64.b64decode(request["data"], validate=True)
            offset = int(request["offset"])
            if len(data) > 48 * 1024 or offset < 0 or offset + len(data) > MAX_ARCHIVE:
                raise ValueError("invalid_chunk")
            with open(upload, "r+b") as out:
                out.seek(0, os.SEEK_END)
                if out.tell() < offset:
                    raise ValueError("missing_chunk")
                out.seek(offset)
                out.write(data)
                out.truncate()
            return {"success": True, "next_offset": offset + len(data)}
        if action != "commit":
            raise ValueError("unknown_action")
        if digest_file(upload) != revision:
            raise ValueError("archive_hash_mismatch")
        package.mkdir(parents=True, exist_ok=True, mode=0o700)
        staging = Path(tempfile.mkdtemp(prefix=".staging-", dir=package))
        try:
            manifest = extract(upload, staging)
            if usage(root) > MAX_STORE:
                raise ValueError("store_quota_exceeded")
            (staging / ".rikkahub-manifest.json").write_text(json.dumps(manifest))
            if target.exists():
                # The same content hash is immutable. A locally edited revision is not
                # overwritten underneath running scripts; ask for explicit cleanup.
                raise ValueError("revision_exists_modified_or_incomplete")
            os.rename(staging, target)
            upload.unlink()
            return {"success": True, "ready": True, "skill_root": str(target),
                    "revision": revision, "files": len(manifest), "bytes": sum(f["bytes"] for f in manifest)}
        finally:
            if staging.exists():
                shutil.rmtree(staging)


if __name__ == "__main__":
    os.umask(0o077)
    try:
        result = dispatch(sys.argv[1], json.loads(base64.b64decode(sys.argv[2])))
    except Exception as error:
        result = {"success": False, "error": str(error)}
    print(json.dumps(result, ensure_ascii=True))
