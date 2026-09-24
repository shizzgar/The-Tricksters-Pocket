# Connecting the existing Java bridge

The archive does not bundle frida-java-bridge. It uses the existing patched
bridge-final.js so a dependency update does not replace compatibility with the
user's Samsung ART.

The original standalone pack did not know the user's file format. Its tested
adapters support these **plain JavaScript** forms:

| File contents | Mode |
|---|---|
| var bridge = …; object has perform | auto |
| var Java = …; or globalThis.Java = … | auto / global |
| module.exports = …; or module.exports.default = … | auto |
| var bridge = {default: …}; | auto |
| One expression returning the bridge | expression |

`auto` and `global` use one mechanism: run the file in an IIFE, then extract
Java / bridge / CommonJS export. `global` is an explicit selection label, not a
separate JS sandbox. Bridge and runtime execute **in the same Frida Script**.
The runtime subsequently checks Java.available.

Example configuration; replace the path:

```sh
python rebro.py configure --bridge /absolute/path/bridge-final.js --bridge-mode auto
python rebro.py configure --bridge /absolute/path/bridge-final.js --bridge-mode expression
```

Choose one appropriate command. Standalone configure records a new baseline and
replaces local.json; do not rerun it casually. The outer RikkaHub adapter instead
uses external config and refuses overwrite. expression accepts one JS expression,
not a file containing declarations.

**Not automatically supported:** raw ESM import/export, Frida Compiler bundles
with a 📦 header, external require(), private loading protocols or unknown external
globals. The file must be self-contained. import/export detection is heuristic;
live smoke establishes actual compatibility.

For a private launcher:

1. Read local source/config and establish the exact loading method/runtime.
2. Preserve the original pinned bridge.
3. If sources/toolchain permit, create a separate self-contained plain JS adapter from the same local version. Do not download a replacement bridge as a "fix".
4. Record the adapter's separate path/SHA-256 and verify Java smoke.
5. If adaptation would change the protocol, use the existing launcher and transfer modules under its contract. Controller integration remains unverified.

The outer kit's rebro-flat mode handles the subsequently supplied
frida_java_bridge_default export; see the skill's main procedure for that route.
Do not load a bridge in a separate session.create_script expecting shared globals.
ESM compilation is a distinct build mode, not concatenation. frida.Compiler() can
support extensions, but the base pack needs no Compiler/npm/TypeScript.

References: [official bridges](https://frida.re/docs/bridges/),
[Java bridge source](https://github.com/frida/frida-java-bridge),
[Python API baseline](https://github.com/frida/frida-python/blob/17.2.14/frida/core.py).
