#!/usr/bin/env python3
"""Bounded Termux inventory. Read-only probes; writes only a new report directory/ZIP."""
from __future__ import annotations
import argparse
import ast
from collections import Counter, deque
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import shlex
import shutil
import signal
import stat
import subprocess
import sys
import time
import uuid
import zipfile

VERSION = '2.2.0'
MAX_OUTPUT = 96 * 1024
PROPERTIES = ['ro.product.model', 'ro.product.device', 'ro.product.cpu.abilist',
              'ro.soc.manufacturer', 'ro.soc.model', 'ro.board.platform',
              'ro.build.fingerprint', 'ro.build.version.release', 'ro.build.version.sdk',
              'ro.build.version.security_patch', 'ro.boot.verifiedbootstate',
              'ro.debuggable', 'init.svc.adbd']
TOOLS = {'java':['-version'], 'javac':['-version'], 'jadx':['--version'], 'apktool':['--version'],
         'aapt2':['version'], 'aapt':['version'], 'apksigner':['version'], 'zipalign':[],
         'clang':['--version'], 'llvm-readelf':['--version'], 'r2':['-v'], 'rabin2':['-v'],
         'python3':['--version'], 'node':['--version'], 'npm':['--version'], 'go':['version'],
         'git':['--version'], 'curl':['--version'], 'rg':['--version'], 'jq':['--version'],
         'sqlite3':['--version'], 'tmux':['-V'], 'adb':['version'], 'strace':['--version']}
SKIP_DIRS = {'.git', 'node_modules', '__pycache__', '.gradle', '.cache', 'sources',
             'smali', 'smali_classes2', 'smali_classes3', 'resources', 'res'}
KIND_LIMITS = {'bridge': 32, 'baseline_candidate': 40, 'loader_candidate': 120, 'tool_file': 16}


def tool_capabilities(name, result):
    """A usage banner with exit 2 is capability evidence, never an alignment test."""
    text = result.get('output', '')
    if name == 'zipalign':
        recognized = (result.get('status') in ('ok', 'nonzero') and
                      'Zip alignment utility' in text and 'Usage: zipalign' in text)
        return {'help_recognized': recognized,
                'page_alignment_flag_advertised': recognized and '-P <pagesize_kb>' in text,
                'operation_tested': False}
    return {}


def candidate_kind(name):
    low = name.lower()
    if low == 'bridge-final.js': return 'bridge'
    if (low in ('pins.json', 'baseline.json', 'frida-baseline.json', 'local.json') or
            (low.endswith('.json') and any(x in low for x in ('baseline', 'pins', 'pin-manifest')))):
        return 'baseline_candidate'
    if low in ('rebro-frida', 'frida-server', 'apksigner.jar'): return 'tool_file'
    if Path(low).suffix in ('.py', '.sh') and any(x in low for x in
            ('frida', 'rebro', 'loader', 'launch', 'attach', 'probe', 'smoke', 'agent', 'run')):
        return 'loader_candidate'
    return None


def discover_paths(roots, max_entries, deadline, max_depth=12, per_directory=1024):
    """Breadth-first, capped per directory/kind: one build tree cannot monopolize discovery."""
    pending = deque((Path(root), 0) for root in roots)
    candidates, seen, limits, errors = [], set(), Counter(), []
    counts = Counter()
    visited = directories = skipped = 0
    while pending:
        if visited >= max_entries or time.monotonic() > deadline:
            limits['total_entries' if visited >= max_entries else 'time'] += 1
            break
        directory, depth = pending.popleft()
        if directory in seen or directory.is_symlink(): continue
        seen.add(directory)
        children = []
        try:
            with os.scandir(directory) as entries:
                directories += 1
                for index, entry in enumerate(entries):
                    if index >= per_directory:
                        limits['directory_entries'] += 1
                        break
                    if visited >= max_entries or time.monotonic() > deadline:
                        limits['total_entries' if visited >= max_entries else 'time'] += 1
                        break
                    visited += 1
                    try:
                        if entry.is_symlink(): continue
                        if entry.is_dir(follow_symlinks=False):
                            if entry.name in SKIP_DIRS or entry.name.startswith('smali'):
                                skipped += 1
                            elif depth < max_depth:
                                children.append(Path(entry.path))
                            else: limits['depth'] += 1
                            continue
                        if not entry.is_file(follow_symlinks=False): continue
                        kind = candidate_kind(entry.name)
                        if not kind: continue
                        if counts[kind] >= KIND_LIMITS[kind]:
                            limits[kind] += 1
                            continue
                        info = entry.stat(follow_symlinks=False)
                        candidates.append({'path':entry.path,'bytes':info.st_size,'kind':kind})
                        counts[kind] += 1
                    except OSError:
                        limits['entry_access'] += 1
        except OSError as e:
            if len(errors) < 12: errors.append({'path':str(directory),'error':type(e).__name__})
        # Deterministic within a level, preserving breadth-first traversal.
        children.sort(key=lambda p: (not any(x in p.name.lower() for x in
                       ('frida', 'rebro', 'cases', 'config', 'scripts')), p.name))
        pending.extend((path, depth + 1) for path in children)
    return {'roots':list(map(str,roots)), 'strategy':'breadth_first_per_directory_and_kind_bounds',
            'visited_entries':visited, 'visited_directories':directories,
            'limited':bool(limits), 'limit_reasons':dict(limits), 'errors':errors,
            'excluded_directories':skipped, 'exclusion_policy':sorted(SKIP_DIRS),
            'candidates':candidates, 'automatic_selection':False, 'max_depth':max_depth,
            'max_directory_entries':per_directory, 'candidate_limits':KIND_LIMITS}


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def file_text(path, cap=MAX_OUTPUT):
    try:
        with open(path, 'rb') as stream:
            raw = stream.read(cap + 1)
        return {'status': 'ok', 'text': raw[:cap].decode(errors='replace'), 'truncated': len(raw) > cap}
    except OSError as e:
        return {'status': 'unavailable', 'error': type(e).__name__ + ': ' + str(e)}


def digest(path, deadline=None):
    h = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            if deadline is not None and time.monotonic() > deadline:
                raise TimeoutError('Hash budget exhausted')
            h.update(block)
    return h.hexdigest()


def scrub(text):
    text = re.sub(r'(https?://)[^\s/@]+:[^\s/@]+@', r'\1[credentials-omitted]@', text)
    return re.sub(r'(https?://[^\s?#]+)[?#][^\s]*', r'\1[query-omitted]', text)


def bounded(argv, seconds=12, cap=MAX_OUTPUT, env=None):
    """Bound memory, output and wall time; signal only this invocation's process group."""
    start = time.monotonic()
    try:
        process = subprocess.Popen(list(map(str, argv)), stdin=subprocess.DEVNULL,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   start_new_session=True, env=env)
    except OSError as e:
        return {'status': 'unavailable', 'error': str(e), 'returncode': None, 'output': ''}
    data = bytearray()
    status = 'ok'
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while selector.get_map():
                left = seconds - (time.monotonic() - start)
                if left <= 0:
                    status = 'timeout'
                    break
                for key, _ in selector.select(min(left, .2)):
                    chunk = os.read(key.fd, min(8192, cap + 1 - len(data)))
                    if not chunk:
                        selector.unregister(key.fileobj)
                    else:
                        data.extend(chunk)
                        if len(data) > cap:
                            status = 'output_limit'
                            break
                if status != 'ok':
                    break
            if status == 'ok':
                try:
                    process.wait(timeout=max(.01, seconds - (time.monotonic() - start)))
                except subprocess.TimeoutExpired:
                    status = 'timeout'
    finally:
        if process.poll() is None or status != 'ok':
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except (ProcessLookupError, PermissionError):
                pass
            try:
                process.wait(timeout=.5)
            except subprocess.TimeoutExpired:
                pass
        process.stdout.close()
    if status == 'ok' and process.returncode:
        status = 'nonzero'
    return {'status': status, 'returncode': process.returncode,
            'seconds': round(time.monotonic() - start, 3), 'output_bytes': len(data),
            'output': scrub(bytes(data[:cap]).decode(errors='replace')),
            'truncated': status == 'output_limit'}


def bridge_info(path, deadline=None):
    path = Path(path).expanduser().resolve(strict=True)
    before = path.stat()
    if not stat.S_ISREG(before.st_mode) or before.st_size > 64 * 1024**2:
        raise ValueError('Bridge is not a regular file within the 64 MiB scan bound')
    with path.open('rb') as stream:
        sample = stream.read(64 * 1024).decode('utf-8-sig', errors='replace')
        if before.st_size > 4096:
            stream.seek(-4096, os.SEEK_END)
            tail = stream.read().decode(errors='replace')
        else:
            tail = sample
    marker = sample + '\n' + tail
    fmt = 'frida_bundle' if sample.startswith('📦') else (
        'esm_candidate' if re.search(r'^\s*(?:import\s|export\s)', marker, re.M) else 'plain_or_private_loader')
    result = {'path': str(path), 'bytes': before.st_size, 'sha256': digest(path, deadline),
              'format_hint': fmt, 'classification_is_heuristic': True,
              'markers': {key: bool(re.search(pattern, marker)) for key, pattern in {
                  'global_Java': r'globalThis\.Java', 'declared_Java': r'\b(?:var|let|const)\s+Java\b',
                  'declared_bridge': r'\b(?:var|let|const)\s+bridge\b',
                  'commonjs': r'module\.exports', 'perform': r'\bperform\b', 'require': r'\brequire\s*\('
              }.items()}, 'source_included': False}
    after = path.stat()
    if (before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_ino, after.st_size, after.st_mtime_ns):
        raise ValueError('Bridge changed during collection')
    return result


def loader_info(path, deadline=None):
    path = Path(path).expanduser().resolve(strict=True)
    if not path.is_file() or path.stat().st_size > 2*1024**2:
        raise ValueError('Loader exceeds 2 MiB inspection bound')
    data = file_text(path, 512 * 1024)
    result = {'path': str(path), 'sha256': digest(path, deadline), 'source_included': False,
              'truncated_scan': data.get('truncated', False), 'call_names': [], 'runtime_literals': []}
    if data['status'] != 'ok':
        return {**result, 'error': data.get('error')}
    text = data['text']
    if path.suffix == '.py' and not data['truncated']:
        try:
            tree = ast.parse(text)
            allowed = {'create_script', 'compile_script', 'build', 'load', 'attach', 'add_remote_device'}
            for node in ast.walk(tree):
                if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and node.func.attr in allowed:
                    result['call_names'].append(node.func.attr)
                    for arg in node.keywords:
                        if arg.arg == 'runtime' and isinstance(arg.value, ast.Constant) and arg.value.value in ('qjs', 'v8'):
                            result['runtime_literals'].append(arg.value.value)
        except SyntaxError:
            result['python_parse'] = 'failed'
    result['markers'] = {x: x in text for x in ('bridge-final.js', 'frida.Compiler', 'create_script',
                                              'globalThis.Java', 'frida-java-bridge', 'exports_sync')}
    result['call_names'] = sorted(set(result['call_names']))
    result['runtime_literals'] = sorted(set(result['runtime_literals']))
    result['java_loader_candidate'] = bool(
        ('create_script' in result['call_names'] or result['markers']['create_script']) and
        any(result['markers'][key] for key in ('bridge-final.js', 'globalThis.Java', 'frida-java-bridge')))
    result['contract_verified'] = False  # Static markers never demonstrate a working injection.
    return result


def baseline_info(path, deadline=None, explicit=False):
    selected = Path(path).expanduser().resolve(strict=True)
    if not selected.is_file() or selected.stat().st_size > 1024**2:
        raise ValueError('Baseline JSON must be a regular file within 1 MiB')
    data = json.loads(selected.read_text())
    if not isinstance(data, dict): raise ValueError('Baseline JSON must be an object')
    artifacts = []
    rows = data.get('artifacts', [])
    if isinstance(rows, list):
        for row in rows:
            if isinstance(row, dict) and row.get('id') in ('frida_service', 'frida_python_extension', 'java_bridge'):
                artifacts.append({k:row.get(k) for k in ('id','path','sha256')})
    aliases = {'binding':'frida_python_extension', 'bridge':'java_bridge', 'server_binary':'frida_service'}
    pins = data.get('pins', {})
    if isinstance(pins, dict):
        for key, sha in pins.items():
            if key in aliases: artifacts.append({'id':aliases[key], 'path':data.get(key), 'sha256':sha})
    clean = []
    for row in artifacts:
        if not isinstance(row.get('path'), str) or not isinstance(row.get('sha256'), str): continue
        if not re.fullmatch(r'[0-9a-fA-F]{64}', row['sha256']): continue
        item = Path(row['path']).expanduser()
        if not item.is_absolute(): item = selected.parent / item
        clean.append({'id':row['id'], 'path':str(item), 'sha256':row['sha256'].lower()})
    return {'path':str(selected), 'sha256':digest(selected, deadline), 'artifacts':clean,
            'complete_reference':{x['id'] for x in clean} == set(aliases.values()),
            'explicit_reference':explicit, 'trust':'reference candidate; provenance not authenticated by collector'}


FRIDA_PROBE = r'''
import importlib, json, sys, time, hashlib
result = {'attach_performed': False, 'compiler_build_performed': False}
try:
 import frida
 native = importlib.import_module('frida._frida')
 h = hashlib.sha256()
 with open(native.__file__, 'rb') as f:
  for block in iter(lambda: f.read(1024*1024), b''): h.update(block)
 result.update(python=sys.executable, binding_version=getattr(frida, '__version__', None),
               binding_path=native.__file__, binding_sha256=h.hexdigest(),
               compiler_class_present=hasattr(frida, 'Compiler'))
 if hasattr(frida, 'Compiler'):
  result['compiler_methods_present']=[x for x in ('build','watch') if hasattr(frida.Compiler,x)]
 if sys.argv[1] == 'online':
  start=time.monotonic()
  device=frida.get_device_manager().add_remote_device('127.0.0.1:27044')
  info=device.query_system_parameters()
  result['handshake_ms']=round((time.monotonic()-start)*1000,2)
  result['server_parameters']={k:info[k] for k in ('arch','platform','os','access','page-size') if k in info}
  processes=device.enumerate_processes()
  result['process_count']=len(processes)
  result['termux_processes']=[{'pid':p.pid,'name':p.name} for p in processes if p.name.lower() in ('termux','termux:api')]
  result['online_status']='connected'
except Exception as e:
 result['error']=type(e).__name__+': '+str(e)
print(json.dumps(result, ensure_ascii=False, default=str))
'''


class Collector:
    def __init__(self, args):
        self.args = args
        self.deadline = time.monotonic() + args.budget_seconds
        self.root_kind = None
        self.android = Path('/system/bin/getprop').is_file()
        self.report = {'schema': 2, 'collector_version': VERSION, 'started_at': utc(),
                       'host_is_android': self.android, 'collector_uid': os.getuid(),
                       'python': sys.executable, 'endpoint': '127.0.0.1:27044',
                       'probes': {}, 'observations': {}, 'missing': [],
                       'collection_scope': 'environment metadata, scoped root reads, no attach/spawn/install',
                       'sensitive_sources_excluded': ['environment variables', 'shell history', 'app databases',
                           'preferences contents', 'keystores', 'password files', 'tokens', 'bridge/loader source text']}

    def probe(self, name, argv, seconds=12, cap=MAX_OUTPUT, root=False, save_output=True):
        left = self.deadline - time.monotonic()
        if left <= 0:
            result = {'status': 'budget_exhausted', 'output': ''}
        elif root and not self.root_kind:
            result = {'status': 'root_unavailable', 'output': ''}
        else:
            if root:
                argv = (['su', '-c', shlex.join(list(map(str, argv)))] if self.root_kind == 'su'
                        else ['sudo', '-n', '--', *map(str, argv)])
            env = {**os.environ, 'LC_ALL': 'C', 'LANG': 'C', 'PYTHONDONTWRITEBYTECODE': '1'}
            env.update(_JAVA_OPTIONS='-Xmx256m -XX:ActiveProcessorCount=2', JAVA_TOOL_OPTIONS='', JDK_JAVA_OPTIONS='')
            result = bounded(argv, min(seconds, max(.01, left)), cap, env)
        self.report['probes'][name] = result if save_output else {k:v for k,v in result.items() if k != 'output'}
        return result

    def root(self):
        if self.args.root == 'none' or not self.android:
            self.report['observations']['root'] = {'status': 'skipped', 'reason': 'disabled or host is not Android'}
            return
        choices = ['su', 'sudo'] if self.args.root == 'auto' else [self.args.root]
        for choice in choices:
            argv = ['su', '-c', '/system/bin/id'] if choice == 'su' else ['sudo', '-n', '--', '/system/bin/id']
            result = self.probe('root_' + choice, argv, seconds=10)
            if result.get('returncode') == 0 and re.search(r'\buid=0\b', result.get('output', '')):
                self.root_kind = choice
                break
        self.report['observations']['root'] = {'status': 'available' if self.root_kind else 'unavailable', 'method': self.root_kind}

    def device(self):
        facts = {key: self.probe('property_' + key, ['/system/bin/getprop', key], seconds=3).get('output','').strip()
                 for key in PROPERTIES} if self.android else {}
        facts.update(kernel=os.uname().release, machine=os.uname().machine, page_size=os.sysconf('SC_PAGE_SIZE'),
                     boot_id=file_text('/proc/sys/kernel/random/boot_id', 100),
                     memory=file_text('/proc/meminfo', 16384), load=file_text('/proc/loadavg', 256),
                     cpuinfo=file_text('/proc/cpuinfo', 32768))
        if 'text' in facts['cpuinfo']:
            facts['cpuinfo']['text']='\n'.join(x for x in facts['cpuinfo']['text'].splitlines()
                                               if not re.match(r'^Serial\s*:',x,re.I))
        disks = {}
        for path in [str(Path.home()), '/data', self.args.out_dir]:
            target = Path(path).expanduser()
            while not target.exists() and target != target.parent:
                target = target.parent
            try:
                usage = shutil.disk_usage(target)
                disks[path] = dict(zip(('total','used','free'), usage))
            except OSError as e:
                disks[path] = {'error': str(e)}
        facts['disk_bytes'] = disks
        facts['cpu_policies'] = [{'name':folder.name, **{n:file_text(folder/n,512) for n in
                             ('affected_cpus','cpuinfo_min_freq','cpuinfo_max_freq','scaling_governor')}}
                             for folder in sorted(Path('/sys/devices/system/cpu/cpufreq').glob('policy*'))[:16]]
        self.report['observations']['device'] = facts
        if self.android:
            self.probe('selinux', ['/system/bin/getenforce'], root=bool(self.root_kind))
            for name, command in [('battery',['/system/bin/dumpsys','battery']),
                                  ('thermal',['/system/bin/dumpsys','thermalservice'])]:
                result=self.probe(name,command,root=True,save_output=False)
                if name=='battery':
                    result['output']='\n'.join(x for x in result.get('output','').splitlines()
                        if re.match(r'^\s*(AC powered|USB powered|Wireless powered|status|health|level|scale|voltage|temperature):',x))
                self.report['observations'][name]=result
            self.probe('pm_help',['/system/bin/pm','help'],root=True,cap=128*1024)
            users=self.probe('android_users',['/system/bin/pm','list','users'],root=True,save_output=False)
            self.report['observations']['android_users']={'ids': sorted(set(map(int,re.findall(r'UserInfo\{(\d+):',users.get('output',''))))),
                                                          'probe_status':users['status'], 'names_omitted':True}
        group=file_text('/proc/self/cgroup',8192)
        self.report['observations']['cgroup']=group
        for line in group.get('text','').splitlines():
            if line.startswith('0::'):
                relative=line[3:].lstrip('/')
                if '..' not in Path(relative).parts:
                    candidate=Path('/sys/fs/cgroup')/relative/'cgroup.freeze'
                    self.report['observations']['cgroup_freeze']=self.probe('cgroup_freeze',['/system/bin/cat',candidate],root=True)
                break

    def tools(self):
        result={}
        for name,args in TOOLS.items():
            found=shutil.which(name)
            row={'path':found,'available':bool(found)}
            if found:
                row['resolved_path']=str(Path(found).resolve())
                if not self.args.no_tools:
                    row['version_probe']=self.probe('tool_'+name,[found,*args],seconds=10,cap=8192)
                    capability = tool_capabilities(name, row['version_probe'])
                    if capability: row['capabilities'] = capability
            result[name]=row
        self.report['observations']['tools']=result
        if not self.args.no_tools:
            self.probe('termux_packages',['dpkg-query','-W','-f=${Package}\t${Version}\t${Architecture}\t${Status}\n'],cap=128*1024)
            self.probe('aapt_signer_files',['dpkg-query','-L','aapt','apksigner'],cap=48*1024)
            policy=self.probe('package_candidates',['apt-cache','policy','aapt','apksigner','ripgrep','jq','sqlite'],save_output=False)
            self.report['observations']['package_candidates']={'status':policy['status'],
                'output':'\n'.join(x for x in policy.get('output','').splitlines() if re.match(r'^[\w.+-]+:$|^\s+(Installed|Candidate):',x))}
        props=file_text(Path.home()/'.termux/termux.properties',32768)
        self.report['observations']['termux_external_apps']={'read_status':props['status'],
            'active_values':re.findall(r'^\s*allow-external-apps\s*=\s*(true|false)\s*$',props.get('text',''),re.M)}
        for key in ('java','javac','aapt2','apksigner','zipalign'):
            if not result[key]['available']:
                self.report['missing'].append('tool:'+key)

    def python_and_frida(self):
        mode='offline' if self.args.no_online else 'online'
        probe=self.probe('frida_binding_and_transport',[sys.executable,'-c',FRIDA_PROBE,mode],seconds=25,cap=32768,save_output=False)
        try:value=json.loads(probe.get('output',''))
        except ValueError:value={'error':probe.get('output','')[:2000], 'probe_status':probe['status']}
        self.report['observations']['frida']=value
        if not value.get('binding_sha256'):
            self.report['missing'].append('frida:loaded_binding_hash')
        if not self.args.no_online and value.get('online_status')!='connected':
            self.report['missing'].append('frida:online_handshake')
        if not self.args.no_tools:
            self.probe('python_packages',[sys.executable,'-c',
                'import importlib.metadata as m,json; print(json.dumps(sorted({(d.metadata.get("Name","?"),d.version) for d in m.distributions()})))'],cap=64*1024)
        if self.android:
            sessions=self.probe('tmux_sessions',['tmux','list-sessions','-F','#{session_name}\t#{session_attached}\t#{session_windows}'],save_output=False)
            self.report['observations']['rebro_tmux_sessions']=[x for x in sessions.get('output','').splitlines()
                                                               if x.startswith('rk_') or 'rebro' in x.split('\t')[0].lower()]

    def services(self):
        script='''for item in /proc/[0-9]*; do
  IFS= read -r comm < "$item/comm" 2>/dev/null || continue
  case "$comm" in *frida*|rebro-*)
    exe=$(/system/bin/readlink "$item/exe" 2>/dev/null)
    printf '%s\\t%s\\t%s\\n' "${item##*/}" "$comm" "$exe" ;;
  esac
done'''
        result=self.probe('frida_services',['/system/bin/sh','-c',script],root=True,save_output=False)
        rows=[]
        for line in result.get('output','').splitlines()[:16]:
            parts=line.split('\t')
            if len(parts)!=3 or not parts[0].isdigit():continue
            pid=int(parts[0]); row={'pid':pid,'comm':parts[1],'exe_path':parts[2]}
            for name,argv in [('live_exe_sha256',['/system/bin/toybox','sha256sum',f'/proc/{pid}/exe']),
                              ('selinux_context',['/system/bin/cat',f'/proc/{pid}/attr/current']),
                              ('cgroup',['/system/bin/cat',f'/proc/{pid}/cgroup'])]:
                row[name]=self.probe(f'service_{pid}_{name}',argv,root=True,seconds=15,cap=4096)
            rows.append(row)
        self.report['observations']['service_candidates']={'status':result['status'],'entries':rows,
            'selection_basis':'process comm; no cmdline/environment copied; identity requires baseline comparison'}
        for suffix in ('tcp','tcp6'):
            net=self.probe('frida_listener_'+suffix,['/system/bin/cat','/proc/net/'+suffix],
                           root=True,save_output=False)
            listeners=[]
            for line in net.get('output','').splitlines():
                fields=line.split()
                if len(fields)>9 and fields[3]=='0A' and fields[1].endswith(':69A4'):
                    listeners.append({'local_hex':fields[1],'state':fields[3],'uid':fields[7],'inode':fields[9]})
            self.report['observations']['frida_listener_'+suffix]={'probe_status':net['status'],
                'listeners':listeners,'other_connections_omitted':True,'truncated_input':net.get('truncated',False)}
        if not rows:self.report['missing'].append('frida:live_service_executable')

    def packages(self):
        if not self.android:return
        for hint in ('com.termux','rikkahub'):
            query=self.probe('app_lookup_'+hint,['/system/bin/pm','list','packages',hint],root=True,save_output=False)
            for package in re.findall(r'^package:([A-Za-z0-9_.]+)$',query.get('output',''),re.M)[:12]:
                dump=self.probe('package_'+package,['/system/bin/dumpsys','package',package],root=True,cap=128*1024,save_output=False)
                self.report['observations'].setdefault('integration_apps',{})[package]={
                    'status':dump['status'],'metadata_lines':[x.strip() for x in dump.get('output','').splitlines()
                    if re.match(r'^\s*(versionCode=|versionName=|targetSdk=|minSdk=|pkgFlags=|firstInstallTime=|lastUpdateTime=)',x)][:24]}

    def discover(self):
        roots=[Path(x).expanduser().resolve() for x in self.args.search_root] or [Path.home()/'rebro']
        discovery = (discover_paths(roots, self.args.max_entries, self.deadline - 12)
                     if not self.args.no_discover else
                     {'roots':list(map(str,roots)), 'candidates':[], 'limited':False,
                      'visited_entries':0, 'automatic_selection':False, 'status':'disabled'})
        self.report['observations']['discovery'] = discovery
        candidates = discovery['candidates']
        baselines=[]
        references=list(dict.fromkeys(self.args.baseline+[x['path'] for x in candidates if x['kind']=='baseline_candidate']))
        for file in references[:40]:
            try:value=baseline_info(file, self.deadline-5, file in self.args.baseline)
            except Exception as e:value={'path':file,'error':str(e)}
            baselines.append(value)
        self.report['observations']['baseline_references']=baselines
        if not self.args.baseline:
            self.report['missing'].append('frida:trusted_baseline_reference_not_explicitly_selected')
        elif not any(x.get('explicit_reference') and x.get('complete_reference') for x in baselines):
            self.report['missing'].append('frida:baseline_reference_invalid_or_incomplete')

        # Resolve candidate references only inside the selected RE roots; never execute them.
        referenced=[]
        for ref in baselines:
            for row in ref.get('artifacts',[]):
                if row['id'] != 'java_bridge': continue
                candidate=Path(row['path']).resolve()
                if candidate.suffix == '.js' and any(candidate.is_relative_to(root) for root in roots):
                    referenced.append(str(candidate))
        bridges=list(dict.fromkeys(self.args.bridge+referenced+[x['path'] for x in candidates if x['kind']=='bridge']))
        self.report['observations']['bridges']=[]
        for file in bridges[:32]:
            try:value=bridge_info(file,self.deadline-5)
            except Exception as e:value={'path':file,'error':str(e)}
            if value.get('sha256'):
                value['matching_reference_paths'] = [ref['path'] for ref in baselines
                    if any(row['id']=='java_bridge' and Path(row['path']).resolve()==Path(value['path']) and
                           row['sha256']==value['sha256'] for row in ref.get('artifacts',[]))]
            self.report['observations']['bridges'].append(value)
        if not any(x.get('sha256') for x in self.report['observations']['bridges']):
            self.report['missing'].append('frida:bridge_path_and_format')

        # Inspect small neighboring launchers even when their names are merely run.py.
        neighbors=[]; neighbor_limit=False
        if not self.args.no_discover:
            parents=list(dict.fromkeys(Path(x['path']).parent for x in self.report['observations']['bridges'] if x.get('sha256')))
            for directory in parents[:12]:
                try:
                    with os.scandir(directory) as entries:
                        for index,entry in enumerate(entries):
                            if index>=256 or len(neighbors)>=80 or time.monotonic()>self.deadline-8:
                                neighbor_limit=True; break
                            if (not entry.is_symlink() and entry.is_file(follow_symlinks=False) and
                                    Path(entry.name).suffix in ('.py','.sh')):
                                neighbors.append(entry.path)
                except OSError:pass
        loaders=list(dict.fromkeys(self.args.loader+neighbors+[x['path'] for x in candidates if x['kind']=='loader_candidate']))
        records=[]
        for file in loaders[:120]:
            if time.monotonic()>self.deadline-5:
                discovery.setdefault('limit_reasons',{})['loader_time']=1; discovery['limited']=True
                break
            try:value=loader_info(file,self.deadline-5)
            except Exception as e:value={'path':file,'error':str(e)}
            value['explicit_reference']=file in self.args.loader
            records.append(value)
        # Keep real API/bridge candidates before similarly named build utilities.
        records.sort(key=lambda x:(not x.get('explicit_reference'),not x.get('java_loader_candidate'),
                                   not bool(x.get('call_names')),x['path']))
        self.report['observations']['loaders']=records[:32]
        discovery['loader_candidates_inspected']=len(records)
        discovery['loader_records_omitted']=max(0,len(records)-32)
        discovery['neighbor_search_limited']=neighbor_limit
        if neighbor_limit or len(loaders)>120: discovery['limited']=True
        if not any(x.get('java_loader_candidate') for x in records):
            self.report['missing'].append('frida:loader_contract')
        self.report['missing'].append('device:native_and_java_smoke_on_lab_target')
        if getattr(self.args,'focus','full') == 'full':
            self.report['missing'].append('device:align_sign_install_lab_acceptance')

    def collect(self):
        focus=getattr(self.args,'focus','full')
        self.report['focus']=focus
        phases=([('Root access',self.root),('Python and Frida transport',self.python_and_frida),
                 ('Live Frida service',self.services),('Bridge and baseline discovery',self.discover)]
                if focus=='frida' else
                [('Root access',self.root),('Device and resources',self.device),('Toolchain',self.tools),
                 ('Python and Frida transport',self.python_and_frida),('Live Frida service',self.services),
                 ("The Trickster's Pocket / Termux packages",self.packages),('Bridge and baseline discovery',self.discover)])
        for index,(label,fn) in enumerate(phases,1):
            print(f'[{index}/{len(phases)}] {label}',file=sys.stderr,flush=True)
            try:fn()
            except Exception as e:
                self.report['probes']['phase_'+str(index)]={'status':'error','error':type(e).__name__+': '+str(e)}
        self.report['finished_at']=utc()
        self.report['budget_exhausted']=time.monotonic()>self.deadline
        return self.report


def write_report(out, report):
    out=Path(out).expanduser().resolve()
    (out/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    observed={'kind':'observed-artifacts','trust':'observed_only','source_report':'report.json',
              'binding':report['observations'].get('frida',{}),
              'bridges':report['observations'].get('bridges',[]),
              'service_candidates':report['observations'].get('service_candidates',{})}
    (out/'observed-artifacts.json').write_text(json.dumps(observed,ensure_ascii=False,indent=2)+'\n')
    summary=['# Rebro: kit environment report','',f"Collector {VERSION}; time: {report.get('finished_at',utc())}.",
             '', 'The archive contains environment metadata, bounded probe results and observed hashes.',
             'Bridge/loader source, keys, passwords, app data and shell history are not copied.',
             'Observed hashes do not automatically become a trusted baseline.',
             '', '## Not yet established','']
    summary += ['- '+x for x in sorted(set(report.get('missing',[])))]
    summary += ['', '## Next step','',
                'Provide this ZIP for kit configuration. Focused follow-up: `python3 rebro_collect.py --focus frida`.',
                'If paths are already known, narrow search with --search-root or supply them explicitly:',
                '`--bridge /absolute/path/bridge-final.js --loader /absolute/path/existing-launcher.py`.',
                'Supply a trusted pin manifest with `--baseline /absolute/path/pins.json`.',
                'No search candidate is selected automatically. Live attach/Java hooks and',
                'actual lab-APK signing/installation remain separate acceptance checks.','']
    (out/'SUMMARY.ru.md').write_text('\n'.join(summary))
    files=sorted(p for p in out.iterdir() if p.is_file())
    (out/'SHA256SUMS').write_text(''.join(digest(p)+'  '+p.name+'\n' for p in files))
    archive=Path(str(out)+'.zip')
    with zipfile.ZipFile(archive,'x',zipfile.ZIP_DEFLATED) as z:
        for file in sorted(out.iterdir()):z.write(file,file.name)
    return archive


def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__)
    default=Path.home()/'rebro/reports'/('inventory-'+time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:6])
    p.add_argument('--out-dir',default=str(default),help='NEW private directory; a ZIP is written next to it')
    p.add_argument('--root',choices=['auto','su','sudo','none'],default='auto')
    p.add_argument('--focus',choices=['full','frida'],default='full',
                   help='frida: targeted follow-up, skip hardware/tool/package inventories')
    p.add_argument('--no-online',action='store_true',help='Skip the localhost Frida handshake/process count')
    p.add_argument('--no-tools',action='store_true',help='Locate tools but skip versions/package inventory')
    p.add_argument('--no-discover',action='store_true')
    p.add_argument('--search-root',action='append',default=[],help='Bounded search root; default HOME/rebro')
    p.add_argument('--bridge',action='append',default=[])
    p.add_argument('--loader',action='append',default=[])
    p.add_argument('--baseline',action='append',default=[])
    p.add_argument('--budget-seconds',type=int,default=240)
    p.add_argument('--max-entries',type=int,default=None)
    a=p.parse_args(argv)
    if a.max_entries is None: a.max_entries=100000 if a.focus=='frida' else 25000
    if a.focus=='frida': a.no_tools=True
    if not 15<=a.budget_seconds<=900 or not 100<=a.max_entries<=100000:
        p.error('budget-seconds must be 15..900; max-entries 100..100000')
    if Path('/system/bin/getprop').is_file() and os.getuid()==0:
        p.error('Run with Termux Python as the Termux user; scoped su/sudo reads are invoked by this script')
    os.umask(0o077)
    out=Path(a.out_dir).expanduser().resolve()
    if out.exists() or Path(str(out)+'.zip').exists():
        p.error('Report directory/ZIP already exists; choose a new path')
    out.mkdir(parents=True,exist_ok=False)
    a.out_dir=str(out)
    collector=Collector(a)
    code=0
    try:report=collector.collect()
    except KeyboardInterrupt:
        report=collector.report
        report.update(interrupted=True,finished_at=utc())
        code=130
    report['collector_sha256']=digest(__file__)
    archive=write_report(out,report)
    print(json.dumps({'report_zip':str(archive),'report_directory':str(out),
                      'missing':sorted(set(report['missing']))},ensure_ascii=False,indent=2))
    return code


if __name__=='__main__':
    raise SystemExit(main())
