#!/usr/bin/env python3
"""Read one run directory or events.jsonl; emit a compact JSON report."""
import argparse
from collections import Counter
import json
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument("path",type=Path)
a=p.parse_args()
source=a.path/"events.jsonl" if a.path.is_dir() else a.path
counts=Counter(); errors=[]; first=last=None; malformed=0
with source.open(encoding="utf-8") as f:
    for line in f:
        try:
            row=json.loads(line); msg=row.get("message",{}); payload=msg.get("payload",{})
            if not isinstance(payload,dict): payload={}
            key=payload.get("agent","host")+":"+payload.get("kind",msg.get("type","unknown"))
            counts[key]+=1
            stamp=payload.get("time")
            if isinstance(stamp,(int,float)):
                first=stamp if first is None else min(first,stamp)
                last=stamp if last is None else max(last,stamp)
            if msg.get("type")=="error" or payload.get("kind") in ("hook_error","observer_error","cleanup_error","jni_read_error"):
                if len(errors)<20: errors.append(payload or msg)
        except (ValueError,TypeError,AttributeError): malformed+=1
summary=source.parent/"summary.json"
result={"source":str(source.resolve()),"counts":dict(counts.most_common()),"first_ms":first,"last_ms":last,"malformed":malformed,"first_errors":errors}
if summary.is_file(): result["session_summary"]=json.loads(summary.read_text())
print(json.dumps(result,ensure_ascii=False,indent=2))

