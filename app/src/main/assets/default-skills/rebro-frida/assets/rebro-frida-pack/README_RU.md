# Rebro Frida Pack 1.0.0

Переносимый комплект для существующего рабочего Frida в Termux: **38 агентов, 16 профилей, Python-контроллер без сторонних зависимостей кроме уже установленного frida**. Дата сборки: 2026-09-22.

Целевой baseline: Android 16 / API 36 / arm64; KernelSU; SELinux Enforcing; rebro endpoint `127.0.0.1:27044`; пользовательский core 17.18.0, binding 17.2.14-4+rebro.compiler1, внешний patched `bridge-final.js`. Эти версии взяты из предоставленной сводки. Код не устанавливает, не обновляет и не перезапускает Frida.

**Проверено здесь:** синтаксис, сборка, контракты контроллера и агентов на моках. **Проверка на вашем телефоне ещё не выполнена.** Работающий сервер не гарантирует поддержку каждого хука в каждом приложении. `native_stalker_calls` — экспериментальный модуль.

## Быстрый старт в Termux

Распакуйте архив в отдельную папку внутри Termux home. Для работы достаточно вашего baseline Python + frida; Node нужен только для офлайн-тестов. Команды ниже выполняются из корня пакета, без `su`.

```sh
python tools/verify.py
python tools/find_bridge.py "$HOME/rebro"
```

Из результатов поиска выберите **тот bridge, который используется рабочим baseline**, не просто первый файл. Следующая команда использует явный placeholder: замените путь на найденный реальный абсолютный путь.

```sh
python rebro.py configure --bridge /absolute/path/to/bridge-final.js
python rebro.py doctor --online
python rebro.py ps
```

`configure` сохраняет полные SHA-256 загруженного Python binding и выбранного bridge в `local.json`. Это фиксация текущих файлов, а не независимая проверка их происхождения. Для дополнительной фиксации файла сервера укажите `--server-binary /absolute/path/to/rebro-frida`; это не проверяет executable живого PID. При обнаружении изменившегося хеша дальнейший запуск прерывается.

Выберите PID уже запущенного тестового приложения из `ps`. Ниже `12345` — **пример**, замените его актуальным PID:

```sh
python rebro.py smoke --pid 12345
python rebro.py run --pid 12345 --profile native-survey --duration 10
python rebro.py run --pid 12345 --profile java-survey --duration 10
```

`smoke` последовательно создаёт две короткие сессии: native и Java. Без настроенного bridge выполняется только native-фаза с явным сообщением. Проверка Java читает сведения runtime; она не доказывает совместимость всех Java-хуков.

Каждый запуск создаёт `runs/<time>-<id>/session.json`, `events.jsonl`, `summary.json`. Контроллер печатает абсолютный путь. Итог можно обработать:

```sh
python tools/summarize.py runs/ACTUAL_SESSION_DIRECTORY
```

Код возврата `0`: выбранные агенты инициализировались и контроллер не обнаружил ошибок; `2`: ошибка конфигурации/загрузки/наблюдателя/очистки. Остановка по квоте или Ctrl-C может завершиться кодом 0: всегда читайте `reason`, `agent_counters.dropped` и события покрытия. `active` означает «инициализирован», а не «интересующий метод был вызван».

## Что запускать

| Профиль | Назначение |
|---|---|
| native-smoke / java-smoke | Минимальная проверка среды |
| native-survey | ABI, модули, потоки |
| java-survey | Runtime, классы, ClassLoader |
| modules | Загрузка ELF и dlopen |
| exports | Фильтр экспортов libc |
| native-io | open/openat и connect/sendto |
| app-observe | Activity, Intent, WebView, assets |
| storage | SharedPreferences, SQLite, Java files |
| network | Native destinations + java.net.URL |
| okhttp | Отдельный optional OkHttp hook |
| crypto-metadata | Алгоритмы, режимы, размеры Cipher/Digest/Mac |
| dynamic-code | DEX loaders, loadClass, ELF |
| threads | Java/native создание потоков |
| jni | RegisterNatives и модули |
| binder | Коды/флаги BinderProxy.transact |

Один профиль за прогон; не запускайте все 38 модулей одновременно. Профили с Java требуют bridge и подходящий Java-процесс. OkHttp может отсутствовать или быть переименован. Observer API проверяются при запуске.

## Точные трассировки

```sh
python rebro.py run --pid 12345 --agents native_trace --options examples/native-trace.options.json --duration 15
python rebro.py build --profile native-survey --out "$TMPDIR/rebro-native-survey.js"
```

Первый пример наблюдает `libc.so!openat`. Для Java сначала замените пример класса/метода в копии `examples/java-trace.options.json`, затем используйте `--agents java_trace --options <file>`. Сборка `build` создаёт самостоятельный JS для вашего existing loader; live-контроллер предпочтительнее, потому что обеспечивает срок сессии и сохранение отчёта. `build` никогда не перезаписывает существующий файл. В Termux вместо `/tmp` используйте `$TMPDIR` или путь внутри home.

По умолчанию: 30 секунд, 64 хука, 2000 событий, 2 MB JSONL на сессию, 100 событий/секунду, строки до 256 символов, коллекции до 100 элементов. Общий объём папки runs не ограничен автоматически. Метаданные могут содержать пути, адреса, URL без query и имена preference keys. Политика `capture_strings=false` относится к значениям generic Java tracer / SQL, не ко всем текстовым полям.

## Для агента-исполнителя

Начать с [AGENTS.md](AGENTS.md), затем [docs/HANDOFF_PROMPT_RU.md](docs/HANDOFF_PROMPT_RU.md). Этот пак не требует npm/pip install, APK-пересборки, ADB-транспорта или изменений SELinux.

Полезные файлы:

- [docs/CATALOG.md](docs/CATALOG.md): 38 модулей и параметры.
- [docs/API17_CHEATSHEET.md](docs/API17_CHEATSHEET.md): используемый API и шаблоны.
- [docs/BRIDGE.md](docs/BRIDGE.md): поддержанные формы bridge и диагностика.
- [docs/RECIPES.md](docs/RECIPES.md): готовые сценарии.
- [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md): ограничения покрытия и среды.
- [docs/TESTING.md](docs/TESTING.md): что именно проверено.
- [docs/GITHUB_RESEARCH_RU.md](docs/GITHUB_RESEARCH_RU.md): предыдущее исследование 59 репозиториев; внешние проекты не являются установленными компонентами этого пака.
- `catalog.json`, `options.json`, `profiles/*.json`: машиночитаемые справочники.
- `SHA256SUMS`: целостность поставки; не цифровая подпись.
