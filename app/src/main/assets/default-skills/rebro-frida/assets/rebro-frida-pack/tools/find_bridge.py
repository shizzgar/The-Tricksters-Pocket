#!/usr/bin/env python3
"""Find bridge-final.js under an explicit local work root. Does not modify it."""
import argparse
import hashlib
import json
import os
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__)
p.add_argument("root",type=Path)
a=p.parse_args(); found=[]
root=a.root.expanduser().resolve()
if not root.is_dir(): p.error("root must be an existing directory")
for directory,dirs,files in os.walk(root,followlinks=False):
    dirs[:]=[d for d in dirs if d not in (".git","node_modules","__pycache__")]
    if "bridge-final.js" in files:
        f=Path(directory)/"bridge-final.js"
        try:
            h=hashlib.sha256()
            with f.open("rb") as stream:
                for chunk in iter(lambda:stream.read(1024*1024),b""): h.update(chunk)
            found.append({"path":str(f.resolve()),"size":f.stat().st_size,"sha256":h.hexdigest()})
        except OSError as e: found.append({"path":str(f),"error":str(e)})
print(json.dumps(found,ensure_ascii=False,indent=2))

