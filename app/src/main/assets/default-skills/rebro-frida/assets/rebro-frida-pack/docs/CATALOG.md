# Agent catalog

38 modules: 16 native and 22 Java/Android/JNI. All have status **offline validated;
device smoke pending**. Complete options are in options.json. pattern / module_pattern /
symbol_pattern / path_pattern are JavaScript RegExp strings; offset is a hex string.
The module option matches module names exactly.

| ID | Bridge | Purpose | Options |
|---|---|---|---|
| `native_probe` | — | GumJS environment, ABI and available APIs | — |
| `native_modules` | — | Loaded ELF modules and addresses | `pattern`=".*" |
| `native_module_watch` | — | New ELF module loads | `pattern`=".*" |
| `native_threads` | — | Thread snapshot | — |
| `native_thread_watch` | — | Thread creation and exit events | — |
| `native_exports` | — | Exports from selected modules | `module_pattern`="^libc\\.so$"; `symbol_pattern`=".*" |
| `native_imports` | — | Imports from selected modules | `module_pattern`="^libc\\.so$"; `symbol_pattern`=".*" |
| `native_symbols` | — | ELF symbol table when available | `module_pattern`="^libc\\.so$"; `symbol_pattern`=".*" |
| `native_trace` | — | Focused export/offset hook with arguments and return | `module*`; `symbol`; `offset` |
| `native_dlopen` | — | dlopen/android_dlopen_ext paths and result | — |
| `native_files` | — | open/openat filenames and flags, without content | `path_pattern`=".*" |
| `native_network` | — | connect/sendto destination without payload | — |
| `native_pthreads` | — | pthread_create entry function address | — |
| `native_memory` | — | Bounded memory read from one module | `module*`; `offset*`; `length`=64 |
| `native_scan` | — | Byte-pattern scan in a bounded readable range | `module*`; `offset*`; `length`=65536; `pattern*` |
| ⚗ `native_stalker_calls` | — | Experimental call summaries for one invocation | `module*`; `symbol`; `offset`; `max_ms`=1000 |
| `java_probe` | Java | Java/Android runtime and basic bridge readiness | — |
| `java_classes` | Java | Loaded classes with a regex filter | `pattern`="^com\\." |
| `java_methods` | Java | Declared methods of a selected class | `class_name*`; `pattern`=".*" |
| `java_loaders` | Java | ClassLoader inventory without changing the default loader | — |
| `java_classload` | Java | Observe ClassLoader.loadClass | `pattern`="^com\\." |
| `java_trace` | Java | Exact Java method tracer, overloads and custom ClassLoader | `class_name*`; `method*`; `signature`; `loader_class` |
| `java_reflection` | Java | Method.invoke for a selected package | `pattern`="^com\\." |
| `java_dex` | Java | DexClassLoader/InMemoryDexClassLoader constructor metadata | — |
| `java_threads` | Java | Thread.start names of created Java threads | — |
| `android_lifecycle` | Java | Activity onResume/onPause/onDestroy | — |
| `android_intents` | Java | Activity/Service/Broadcast launches without extra values | — |
| `android_webview` | Java | WebView URLs and JS-interface inventory | — |
| `android_prefs` | Java | SharedPreferences keys and operation types | — |
| `android_sqlite` | Java | SQLite SQL metadata; text only with capture_strings | — |
| `android_files` | Java | Java FileInputStream/FileOutputStream constructors | — |
| `android_url` | Java | java.net.URL.openConnection metadata | — |
| `android_okhttp` | Java | OkHttpClient.newCall request metadata | `class_name`="okhttp3.OkHttpClient" |
| `android_crypto` | Java | Cipher transformation, mode and sizes; no keys | — |
| `android_digest` | Java | MessageDigest/Mac algorithms and sizes | — |
| `android_binder` | Java | BinderProxy.transact codes/flags without Parcel content | — |
| `android_assets` | Java | AssetManager.open asset names | — |
| `jni_register` | Java | RegisterNatives names, JNI signatures and addresses | — |

\* Required option. native_trace / native_stalker_calls additionally require exactly
one of symbol or offset. For java_trace, signature=[] selects a no-argument overload;
omitting signature selects all overloads. Experimental Stalker belongs to no preset profile.

Numeric bounds: native_memory.length 1..4096; native_scan.length 1..1048576;
native_stalker_calls.max_ms 100..5000. Most other options are optional. The controller
rejects unknown options and invalid basic types before connecting an agent.

Event fields: schema, time (epoch ms), pid, agent, kind, data; ordinary observations
also include tid. events.jsonl additionally wraps the Frida message and host_time.
Control events: agent_status, ready, quota. Installation evidence: hook_installed,
java_hooks. Coverage evidence: java_call, enter/leave, socket_destination and other
specific observed events.
