"""RikkaHub adapter for the unchanged Frida Pack: external state and explicit baseline."""
import argparse
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys

sys.dont_write_bytecode = True
KIT = Path(__file__).resolve().parents[1]
PACK = KIT / 'assets/rebro-frida-pack'
if not PACK.is_dir():
    PACK = KIT / 'vendor/rebro-frida-pack'
REQUIRED = {'frida_service', 'frida_python_extension', 'java_bridge'}
FLAT_MODE = 'rebro-flat'
FLAT_EXPORT = 'frida_java_bridge_default'


def sha(path):
    h = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def external(path):
    result = Path(path).expanduser().resolve()
    if result == KIT or KIT in result.parents:
        raise ValueError('State/output must be outside the imported skill/source tree')
    return result


def flat_bridge_source(config):
    """Preserve the pinned flat source and expose its declared lexical export in this Script."""
    expected = config.get('pins', {}).get('bridge', '')
    if not re.fullmatch('[0-9a-fA-F]{64}', expected):
        raise ValueError('rebro-flat needs a full bridge pin')
    path = Path(config.get('bridge', '')).expanduser()
    if not path.is_file() or path.stat().st_size > 64 * 1024**2:
        raise ValueError('Expected a flat bridge file within 64 MiB')
    raw = path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != expected.lower():
        raise ValueError('Baseline mismatch while reading flat bridge')
    source = raw.decode('utf-8')
    if ('\x00' in source or source.lstrip('\ufeff \t\r\n').startswith('📦') or
            re.search(r'^\s*(?:import\s|export\s)', source, re.M)):
        raise ValueError('rebro-flat accepts flat UTF-8 JS, not ESM or a Compiler bundle')
    # The bridge stays at top level. The alias is resolved after its source in the same Script.
    # No second create_script, compiler header editing, stock import, or source-file rewrite.
    return source + """
;
(function () {
  'use strict';
  if (typeof frida_java_bridge_default === 'undefined' ||
      !frida_java_bridge_default || typeof frida_java_bridge_default.perform !== 'function')
    throw new Error('Pinned flat bridge did not expose frida_java_bridge_default.perform');
  globalThis.Java = frida_java_bridge_default;
})();
"""


def load_pack():
    for line in (PACK / 'SHA256SUMS').read_text().splitlines():
        expected, name = line.split('  ', 1)
        target = (PACK / name).resolve()
        if not target.is_relative_to(PACK.resolve()) or sha(target) != expected:
            raise ValueError('Vendor distribution changed: ' + name)
    spec = importlib.util.spec_from_file_location('rebro_vendor_controller', PACK / 'rebro.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    vendor_bridge_source = module.bridge_source
    def selected_bridge_source(config):
        return (flat_bridge_source(config) if config.get('bridge_mode') == FLAT_MODE
                else vendor_bridge_source(config))
    module.bridge_source = selected_bridge_source
    return module


def baseline(path, root_read=False):
    path = Path(path).expanduser().resolve(strict=True)
    value = json.loads(path.read_text())
    if value.get('trust') not in (None, 'trusted') or value.get('kind') == 'observed-artifacts':
        raise ValueError('Observed inventory is not a trusted baseline')
    records = value.get('artifacts', [])
    ids = [x.get('id') for x in records]
    if not REQUIRED <= set(ids) or len(ids) != len(set(ids)):
        raise ValueError('Need unique service, Python extension and Java bridge pins')
    result = {}
    for item in records:
        if item['id'] not in REQUIRED:
            continue
        file = Path(item.get('path', '')).expanduser()
        expected = item.get('sha256', '')
        if not file.is_absolute() or not re.fullmatch('[0-9a-fA-F]{64}', expected):
            raise ValueError('Expected absolute path and full SHA-256: ' + item['id'])
        try:
            actual = sha(file)
        except PermissionError:
            if item['id'] != 'frida_service' or not root_read:
                raise
            r = subprocess.run(['su', '-c', shlex.join(['/system/bin/toybox', 'sha256sum', str(file)])],
                               stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=10, check=True)
            actual = r.stdout.split()[0]
        if actual != expected.lower():
            raise ValueError('Baseline mismatch: ' + item['id'])
        result[item['id']] = {'path': str(file), 'sha256': actual}
    return result


def check_config(path):
    path = external(path)
    value = json.loads(path.read_text())
    link = value.get('rebro_kit_baseline', {})
    if not link.get('path') or sha(link['path']) != link.get('sha256'):
        raise ValueError('Configuration needs its unchanged trusted baseline manifest')
    records = baseline(link['path'], link.get('root_read', False))
    expected = {'binding': records['frida_python_extension']['sha256']}
    if not value.get('rebro_native_only'):
        expected['bridge'] = records['java_bridge']['sha256']
        if value.get('bridge') != records['java_bridge']['path']:
            raise ValueError('Configured bridge path differs from baseline')
    if value.get('pins') != expected or value.get('endpoint') != '127.0.0.1:27044':
        raise ValueError('Configuration no longer matches the selected baseline/endpoint')
    source_manifest = json.loads(Path(link['path']).read_text())
    declared_mode = source_manifest.get('java_bridge_contract', {}).get('adapter_mode')
    if (not value.get('rebro_native_only') and declared_mode == FLAT_MODE and
            value.get('bridge_mode') != declared_mode):
        raise ValueError('Bridge mode differs from the user-supplied loader contract')
    return value


def configure(pack, args):
    output = external(args.config)
    records = baseline(args.pins, args.root_read)
    manifest = json.loads(Path(args.pins).expanduser().read_text())
    contract = manifest.get('java_bridge_contract', {})
    mode = args.bridge_mode
    if contract.get('adapter_mode') == FLAT_MODE and not args.native_only:
        if contract.get('export') != FLAT_EXPORT:
            raise ValueError('Unsupported declared flat bridge export')
        if mode == 'auto': mode = FLAT_MODE
        if mode != FLAT_MODE:
            raise ValueError('Selected bridge mode contradicts the user-supplied loader contract')
    value = {'endpoint': '127.0.0.1:27044', 'bridge_mode': mode,
             'pack_version': pack.VERSION, 'rebro_native_only': args.native_only,
             'pins': {'binding': records['frida_python_extension']['sha256']},
             'rebro_kit_baseline': {'path': str(Path(args.pins).expanduser().resolve()),
                                    'sha256': sha(args.pins), 'root_read': args.root_read},
             'service_artifact': records['frida_service']}
    if not args.native_only:
        value['bridge'] = records['java_bridge']['path']
        value['pins']['bridge'] = records['java_bridge']['sha256']
        pack.bridge_source(value)
    pack.check_pins(value, pack.import_frida())  # Verify the actually loaded extension, not only a file.
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('x', encoding='utf-8') as stream:
        os.chmod(output, 0o600)
        json.dump(value, stream, ensure_ascii=False, indent=2)
    print(json.dumps({'config': str(output), 'status': 'configured', 'native_only': args.native_only,
                      'bridge_mode': mode, 'live_injection_tested': False}))
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config', help='External state file; required except catalog/build-native')
    sub = p.add_subparsers(dest='command', required=True)
    sub.add_parser('catalog')
    c = sub.add_parser('configure', help='Create a NEW config from existing trusted pins; never re-pin')
    c.add_argument('--pins', required=True)
    c.add_argument('--bridge-mode', choices=['auto', 'global', 'expression', FLAT_MODE], default='auto',
                   help='auto honors a declared rebro-flat baseline contract; rebro-flat uses frida_java_bridge_default')
    c.add_argument('--native-only', action='store_true', help='Preserve an unsupported private Java loader; enable native profiles only')
    c.add_argument('--root-read', action='store_true', help='Allow scoped su sha256sum if the service file is root-readable only')
    d = sub.add_parser('doctor')
    d.add_argument('--online', action='store_true')
    sub.add_parser('ps')
    for name in ('run', 'smoke', 'build-native', 'build'):
        q = sub.add_parser(name)
        q.add_argument('--profile', default='native-survey')
        q.add_argument('--agents')
        q.add_argument('--options')
        if name.startswith('build'):
            q.add_argument('--out', required=True)
        else:
            group = q.add_mutually_exclusive_group(required=True)
            group.add_argument('--pid', type=int)
            group.add_argument('--name')
            if name == 'run':
                group.add_argument('--spawn')
            q.add_argument('--duration', type=float, default=5 if name == 'smoke' else 15)
            q.add_argument('--startup-timeout', type=float, default=10)
            q.add_argument('--output', required=True, help='External case evidence directory')
    a = p.parse_args(argv)
    os.umask(0o077)
    if a.command not in ('catalog', 'build-native') and not a.config:
        p.error('Explicit --config outside skill_root required')
    pack = load_pack()
    if a.command == 'catalog':
        return pack.main(['catalog'])
    if a.command == 'configure':
        return configure(pack, a)
    if a.command == 'build-native':
        names, opts, limits = pack.selection(a.profile, a.agents, a.options)
        by_id = {x['id']: x for x in pack.catalog()}
        if any(by_id[n]['java'] for n in names):
            raise ValueError('build-native does not load Java; use build with a verified config')
        path = external(a.out)
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open('x') as stream:
            stream.write(pack.build({}, names, opts, limits))
        print(json.dumps({'out': str(path), 'sha256': sha(path), 'live_tested': False}))
        return 0
    config_path = external(a.config)
    check_config(config_path)
    forwarded = ['--config', str(config_path), a.command]
    if a.command == 'doctor' and a.online:
        forwarded.append('--online')
    if a.command in ('run', 'smoke', 'build'):
        if a.command == 'build':
            forwarded += ['--out', str(external(a.out))]
        else:
            forwarded += ['--output', str(external(a.output)), '--duration', str(a.duration),
                          '--startup-timeout', str(a.startup_timeout)]
            for key in ('pid', 'name', 'spawn'):
                if getattr(a, key, None) is not None:
                    forwarded += ['--' + key, str(getattr(a, key))]
        for key in ('profile', 'agents', 'options'):
            if getattr(a, key, None):
                forwarded += ['--' + key, str(getattr(a, key))]
    if a.command in ('run', 'smoke'):
        lock = Path.home() / 'rebro/.locks/frida.lock'
        lock.parent.mkdir(parents=True, exist_ok=True)
        with lock.open('a+') as stream:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return pack.main(forwarded)
    return pack.main(forwarded)


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        print(type(error).__name__ + ': ' + str(error), file=sys.stderr)
        raise SystemExit(2)
