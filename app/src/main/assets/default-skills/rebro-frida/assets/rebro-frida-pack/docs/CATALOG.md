# Каталог агентов

38 модулей: 16 native и 22 Java/Android/JNI. Состояние всех: **offline validated; device smoke pending**. Полный набор параметров находится в options.json. Строки pattern / module_pattern / symbol_pattern / path_pattern — JavaScript RegExp; offset — hex string. Имена модулей в module сравниваются точно.

| ID | Bridge | Назначение | Параметры |
|---|---|---|---|
| `native_probe` | — | Среда GumJS, ABI и доступные API | — |
| `native_modules` | — | Загруженные ELF-модули и адреса | `pattern`=".*" |
| `native_module_watch` | — | Загрузка новых ELF-модулей | `pattern`=".*" |
| `native_threads` | — | Снимок потоков | — |
| `native_thread_watch` | — | События новых и завершающихся потоков | — |
| `native_exports` | — | Экспортированные символы выбранных модулей | `module_pattern`="^libc\\.so$"; `symbol_pattern`=".*" |
| `native_imports` | — | Импорты выбранных модулей | `module_pattern`="^libc\\.so$"; `symbol_pattern`=".*" |
| `native_symbols` | — | ELF symbol table, если она доступна | `module_pattern`="^libc\\.so$"; `symbol_pattern`=".*" |
| `native_trace` | — | Точечный hook export/offset с аргументами и возвратом | `module*`; `symbol`; `offset` |
| `native_dlopen` | — | dlopen/android_dlopen_ext: пути и результат | — |
| `native_files` | — | open/openat: имена файлов и флаги, без содержимого | `path_pattern`=".*" |
| `native_network` | — | connect/sendto: адрес назначения без payload | — |
| `native_pthreads` | — | pthread_create: адрес стартовой функции | — |
| `native_memory` | — | Ограниченное чтение памяти одного модуля | `module*`; `offset*`; `length`=64 |
| `native_scan` | — | Поиск byte pattern в ограниченном читаемом диапазоне | `module*`; `offset*`; `length`=65536; `pattern*` |
| ⚗ `native_stalker_calls` | — | Эксперимент: call summaries одного вызова | `module*`; `symbol`; `offset`; `max_ms`=1000 |
| `java_probe` | Java | Java/Android runtime и базовая работоспособность bridge | — |
| `java_classes` | Java | Загруженные классы с regex-фильтром | `pattern`="^com\\." |
| `java_methods` | Java | Declared methods выбранного класса | `class_name*`; `pattern`=".*" |
| `java_loaders` | Java | Инвентаризация ClassLoader без смены default loader | — |
| `java_classload` | Java | Наблюдение ClassLoader.loadClass | `pattern`="^com\\." |
| `java_trace` | Java | Точный Java method tracer, overloads и custom ClassLoader | `class_name*`; `method*`; `signature`; `loader_class` |
| `java_reflection` | Java | Method.invoke для выбранного package | `pattern`="^com\\." |
| `java_dex` | Java | DexClassLoader/InMemoryDexClassLoader constructor metadata | — |
| `java_threads` | Java | Thread.start: имена создаваемых Java-потоков | — |
| `android_lifecycle` | Java | Activity onResume/onPause/onDestroy | — |
| `android_intents` | Java | Запуски Activity/Service/Broadcast, без extras values | — |
| `android_webview` | Java | WebView URLs и JS-interface inventory | — |
| `android_prefs` | Java | SharedPreferences keys и типы операций | — |
| `android_sqlite` | Java | SQLite SQL metadata; текст только при capture_strings | — |
| `android_files` | Java | Java FileInputStream/FileOutputStream constructors | — |
| `android_url` | Java | java.net.URL.openConnection metadata | — |
| `android_okhttp` | Java | OkHttpClient.newCall request metadata | `class_name`="okhttp3.OkHttpClient" |
| `android_crypto` | Java | Cipher transformation, режим и размеры; без ключей | — |
| `android_digest` | Java | MessageDigest/Mac: алгоритмы и размеры | — |
| `android_binder` | Java | BinderProxy.transact codes/flags, без Parcel content | — |
| `android_assets` | Java | AssetManager.open имена assets | — |
| `jni_register` | Java | RegisterNatives: имена, JNI signatures и адреса | — |

\* Обязательный параметр. native_trace / native_stalker_calls дополнительно требуют ровно один symbol или offset. Для java_trace signature=[] выбирает overload без аргументов; пропущенный signature означает все overloads. Экспериментальный Stalker не входит ни в один готовый профиль.

Числовые границы: native_memory.length 1..4096; native_scan.length 1..1048576; native_stalker_calls.max_ms 100..5000. Большинство остальных полей необязательны. Контроллер отклоняет неизвестные параметры и неправильные базовые типы до подключения агента.

Структура событий: schema, time (epoch ms), pid, agent, kind, data; обычные наблюдения также содержат tid. events.jsonl дополнительно оборачивает сообщение Frida и host_time. Управляющие события: agent_status, ready, quota. Свидетельства установки: hook_installed, java_hooks. Свидетельства покрытия: java_call, enter/leave, socket_destination и остальные конкретные события.

