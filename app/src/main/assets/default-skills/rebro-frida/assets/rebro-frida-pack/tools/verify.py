#!/usr/bin/env python3
"""Verify distributed file hashes. --tests also runs offline contracts; Node required."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument("--tests",action="store_true")
a=p.parse_args()
manifest=ROOT/"SHA256SUMS"
if not manifest.is_file(): p.error("SHA256SUMS missing")
bad=[]; count=0
for line in manifest.read_text().splitlines():
    digest,name=line.split("  ",1)
    target=(ROOT/name).resolve()
    if not target.is_relative_to(ROOT): bad.append(name+": outside pack");continue
    if not target.is_file(): bad.append(name+": missing");continue
    count+=1
    if hashlib.sha256(target.read_bytes()).hexdigest()!=digest: bad.append(name+": changed")
print(json.dumps({"files":count,"changed_or_missing":bad,"device_tested":False},indent=2))
if bad: raise SystemExit(2)
if a.tests:
    node=shutil.which("node")
    if not node: p.error("Node required for offline tests; runtime use does not need Node")
    commands=[[sys.executable,"-m","unittest","discover","-s",str(ROOT/"tests"),"-p","test_*.py","-v"],[node,str(ROOT/"tests/runtime.test.js")]]
    for command in commands:
        result=subprocess.run(command,cwd=ROOT)
        if result.returncode: raise SystemExit(result.returncode)

