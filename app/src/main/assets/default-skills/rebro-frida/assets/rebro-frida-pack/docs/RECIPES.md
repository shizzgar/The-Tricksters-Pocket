# Recipes

Standalone commands run from the unpacked pack root. PID 12345, com.example.app,
libexample.so and Example.calculate are placeholders. Put --config **before**
the subcommand. In the RikkaHub skill, use the outer adapter with external config/output.

## Inspect another config without changing the baseline

```sh
python rebro.py --config /absolute/path/local.json doctor --online
python rebro.py --config /absolute/path/local.json ps
```

## Modules and exports

```sh
python rebro.py run --pid 12345 --profile native-survey --duration 5
python rebro.py run --pid 12345 --profile exports --duration 5
python rebro.py run --pid 12345 --profile modules --duration 20
```

For another ELF, create options JSON:
```json
{"native_exports":{"module_pattern":"^libexample\\.so$","symbol_pattern":"JNI|decode|encode"}}
```

```sh
python rebro.py run --pid 12345 --agents native_exports --options /path/exports.options.json --duration 10
```

## Focused native hook

```sh
python rebro.py run --pid 12345 --agents native_trace --options examples/native-trace.options.json --duration 10
```

This example observes libc openat. For a different library, set module and exactly
one of symbol / hex offset. Return and four arguments are recorded as pointer-sized
values; semantics depend on the real signature. The script does not infer types
or read argument buffers.

## Java class, methods and overloads

```sh
python rebro.py run --pid 12345 --profile java-survey --duration 10
```

Create `methods.options.json`:
```json
{"java_methods":{"class_name":"com.example.app.Example","pattern":"calculate"}}
```

```sh
python rebro.py run --pid 12345 --agents java_methods --options methods.options.json --duration 5
python rebro.py run --pid 12345 --agents java_trace --options examples/java-trace.options.json --duration 15
```

Adapt the second example to the observed class first. For a nondefault loader,
add loader_class after java_loaders. If several instances match, the generic tracer
refuses to select one arbitrarily.

## Network, storage and crypto observations

```sh
python rebro.py run --pid 12345 --profile network --duration 20
python rebro.py run --pid 12345 --profile storage --duration 20
python rebro.py run --pid 12345 --profile crypto-metadata --duration 20
```

Reproduce the relevant app action during each sequential run. For string/SQL values,
create a custom profile with limits.capture_strings=true; max_string truncates
values. This does not enable key capture or full-memory reads.

## JNI and DEX

```sh
python rebro.py run --pid 12345 --profile jni --duration 20
python rebro.py run --pid 12345 --profile dynamic-code --duration 20
```

Attach does not recover earlier events. If startup matters, --spawn com.example.app
is explicitly available; verify ordinary attach first. Spawn starts a process,
not necessarily an Activity/UI action. The pack resumes its owned spawn after
script.load so Java.perform can obtain the application loader.

## Small memory ranges and pattern scan

```sh
python rebro.py run --pid 12345 --agents native_memory --options examples/memory.options.json --duration 3
python rebro.py run --pid 12345 --agents native_scan --options examples/scan.options.json --duration 3
```

Examples inspect the beginning of libc. A missing ELF magic match does not prove
corruption: check the current process's mapping/offset/permissions. Read/scan does
not become a full address-space dump.

## Custom profile and bundle

```sh
python rebro.py run --pid 12345 --profile examples/custom-profile.json --duration 10
python rebro.py build --profile examples/custom-profile.json --out "$TMPDIR/rebro-custom.js"
```

Replace Example.calculate in the custom profile first. --options replaces the
**entire options object for the named agent**, not a recursive field merge.
--agents replaces the profile's agent list while retaining its limits.

A completed bundle can use an existing verified loader. It has no external Python
watchdog: the loader owner must call RPC stop and unload/detach. Do not run several
copies of the same Java hook simultaneously.
