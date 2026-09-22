"""Compile a reviewed case-local Frida project with the existing patched Compiler."""
import argparse
import json
import os
from pathlib import Path
import sys
from common import digest


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("entry")
    p.add_argument("--project-root", required=True)
    p.add_argument("--out", required=True)
    a = p.parse_args()
    os.umask(0o077)
    root = Path(a.project_root).resolve()
    entry = Path(a.entry)
    entry = entry.resolve() if entry.is_absolute() else (root / entry).resolve()
    if root not in entry.parents or not entry.is_file():
        p.error("Entry must be a file inside project-root")
    output = Path(a.out)
    if output.exists():
        p.error("Refusing to overwrite existing bundle")
    import frida
    compiler = frida.Compiler()
    compiler.on("diagnostics", lambda d: print(json.dumps({"diagnostics": d}, default=str), file=sys.stderr))
    bundle = compiler.build(str(entry), project_root=str(root))
    with output.open("x", encoding="utf-8") as f:
        f.write(bundle)
    print(json.dumps({"output": str(output.resolve()), "sha256": digest(output), "client_version": frida.__version__}))


if __name__ == "__main__":
    main()
