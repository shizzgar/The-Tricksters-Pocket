# API reference used by this pack

These are small original adaptation examples. Full references:
[Frida JavaScript API](https://frida.re/docs/javascript-api/);
[Python binding 17.2.14](https://github.com/frida/frida-python/blob/17.2.14/frida/core.py).

## Build and attach

```python
# Use the already installed binding.
import frida
device = frida.get_device_manager().add_remote_device("127.0.0.1:27044")
print(device.query_system_parameters())
print([(p.pid, p.name) for p in device.enumerate_processes()])
session = device.attach(actual_pid)
script = session.create_script(source_with_bridge_if_needed, runtime="qjs")
script.on("message", on_message)
script.load()
# At completion:
script.unload()
session.detach()
```

The pack wraps these operations with timeouts, Frida.Cancellable, logs and finally.
Cancellation remains best effort with a custom core.

## Native API without obsolete static Module calls

```js
const m = Process.getModuleByName('libc.so');
const address = m.findExportByName('openat'); // null when absent
const exports = m.enumerateExports();
const all = Process.enumerateModules();
const globalAddress = Module.findGlobalExportByName('dlopen');
// In openat's onEnter(args), the path string is args[1].
```

Use c.cstring(args[1]) inside callbacks: it checks the readable range and bounds
length. For custom memory operations, invoke read methods on NativePointer.
Prefer module-object and NativePointer APIs. Adapt older
Module.findExportByName(moduleName, symbol) / Memory.readUtf8String(pointer) calls.

## Late module loading

```js
const observer = Process.attachModuleObserver({
    onAdded(m) {
        if (m.name === 'libexample.so') {
            // Install the selected native hook here.
        }
    }
});
// At stop: observer.detach()
```

Inside the pack, use c.onModule; it retains cleanup and records hook_error.
Check observer availability as a capability, not by comparing version strings.

## Java overloads

```js
// Use only after including the pinned bridge in this same Script.
Rebro.module('my_agent', true, (o, c) => {
    c.hookJava(o.class_name, o.method,
        function (args, state) { state.begin = Date.now(); },
        function (args, result, state) {
            c.emit('result', {
                result: c.value(result),
                elapsed_ms: Date.now() - state.begin
            });
        },
        o.signature);
});
```

c.hookJava installs implementations for selected overloads, calls the original
through the saved overload, returns its result and rethrows its exception.
Before/after observers do not change arguments; their own errors are recorded
separately. Do not call `this[method](...)` inside an observer: it may reenter the
hook. Existing implementations are not overwritten. The implementation getter
may return a NativeCallback distinct from the original JS function; cleanup retains
the token read back after installation.

Use actual reflection/overload data for class names/signatures: e.g. int,
java.lang.String, [B. java_trace signature=[] selects a no-argument overload;
omitting it selects all overloads of the method.

## ClassLoader, memory, JNI and PAC

- java_loaders lists loaders; java_trace.loader_class selects exactly one instance of that class through Java.ClassFactory.get. Multiple instances need a custom exact selector.
- native_memory/native_scan permit a bounded module+offset range and check bounds; inspect native_modules first.
- jni_register uses JNI table slot 215 and stride 3 * pointerSize. See [upstream env.js](https://github.com/frida/frida-java-bridge/blob/b38a5b647d3e6b72aa19bcf8eb5e41c550a0e622/lib/env.js). It observes registrations after hook installation.
- Do not remove PAC bits with an arbitrary mask. Start with runtime/ELF-resolved addresses. arm64/PAC/BTI alone does not establish compatibility of a particular Stalker hook.
- For hot functions, reduce hook count and event frequency. max_per_second reduces output, not hook-entry cost.
