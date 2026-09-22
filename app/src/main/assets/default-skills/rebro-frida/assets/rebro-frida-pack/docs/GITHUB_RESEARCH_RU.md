# Frida-арсенал для Samsung S24 Ultra / rebro

Дата исследования: **22 сентября 2026**. Окружение — из предоставленной пользователем live-сводки; прямого доступа к телефону в этой сессии нет.

**Рекомендация:** строить модульный арсенал вокруг уже работающих rebro-сервиса, Python binding, Compiler и patched Java bridge. Главная ценность найденных проектов — агенты, библиотеки и методики, которые можно подключить к существующему контроллеру.

Проверены метаданные **59 публичных репозиториев**, README 22 из них, а также исходники и зависимости ключевых компонентов, релизы и связанные issues. Это широкий тематический обзор GitHub, а не исчерпывающий обход всех репозиториев. Скрипты и инструменты на телефоне не запускались. В каталоге отдельно отмечены готовые кандидаты, доноры кода, эксперименты, архивы и инструменты внешней рабочей станции. Число звёзд не использовалось как доказательство качества.

## 1. Опорная конфигурация

| Компонент | Принятый baseline |
|---|---|
| Устройство | SM-S928B / e3q, Android 16 / API 36 |
| ABI | Только arm64-v8a |
| Root | Работающий KernelSU, SELinux Enforcing |
| Сервис | rebro-frida --serve, core 17.18.0, build.17180-sepol3-20260922-004847 |
| Endpoint | **127.0.0.1:27044** |
| Python binding | 17.2.14-4+rebro.compiler1; публичный API сообщает 17.2.14 |
| Java bridge | Проверенный bridge-final.js, pinned hash |
| Compiler | Локальный frida.Compiler(), build/watch работают по сводке |
| Ресурсы | Около 3.1 ГБ MemAvailable, 27 ГБ свободного места |
| Управление | Termux + scoped su; внешнего ADB-транспорта нет |

Разные номера core и Python binding здесь — известное свойство проверенной сборки. Общая рекомендация согласовывать upstream-версии не является основанием менять эту конфигурацию. Перечисление процессов и handshake не доказывают совместимость всех новых клиентских API: каждую надстройку проверять отдельно.

**Уточнение паспорта:** SM-S928B/e3q соответствует S24 Ultra. Samsung указывает Snapdragon 8 Gen 3 for Galaxy для всех S24 Ultra; запись «Exynos 2400» в сводке требует исправления/повторной проверки. Наблюдаемые восемь ядер согласуются с этим уточнением. Для выбора бинарников использовать фактически установленный ABI arm64-v8a. [Samsung](https://news.samsung.com/global/enter-the-new-era-of-mobile-ai-with-samsung-galaxy-s24-series).

Прочие ограничения: питание от сети не устраняет нагрев и ограничения фоновых процессов; load average не равен проценту занятости CPU; MemAvailable — моментальный показатель, а не гарантированный бюджет JVM. Для начала разумен один JADX-процесс с heap около 1–1.5 ГБ и малым числом потоков, затем увеличение по наблюдаемому RSS и memory pressure.

## 2. Что добавлять в первую очередь

| Приоритет | Компонент | Что получить | Как подключать к rebro |
|---|---|---|---|
| 1 | apksigner и native zipalign | Закрыть rebuild → align → sign → verify | Пакеты/сборки Termux; отдельная проверка плана установки |
| 1 | Собственный реестр агентов | Повторяемость, версии, лимиты, единый вывод | Существующий Python/Compiler + endpoint 27044 |
| 1 | 0xdea Android trace/enum | Разведка Java-классов и методов, точечный tracing | Выбранные JS-файлы после code review |
| 1 | Medusa modules | HTTP, intents, WebView, storage, JNI, crypto | Извлекать небольшие модули; адаптировать prolog/bridge |
| 1 | HTTP Toolkit scripts | Управляемая диагностика TLS/proxy/pinning | Выбранный набор файлов и собственный config |
| 2 | friTap | TLS key log и/или plaintext PCAP | Remote backend; отдельная среда зависимостей |
| 2 | r2frida | Соединить имеющийся radare2 с runtime | Сборка под Android arm64 и проверка собственного Frida core |
| 2 | Objection | Интерактивное исследование Java/runtime | TCP + отдельная проверка встроенного агента |
| 2 | JNI/native mini-agents | RegisterNatives, dlopen, exports, backtrace | Небольшие агенты на современном API |
| 3 | IL2CPP/Flutter/Gadget | Поддержка конкретного типа приложения | Только при наличии соответствующей задачи |

### Штатные инструменты Frida

[frida-tools](https://github.com/frida/frida-tools) уже содержит ps, trace, compile, create, apk, itrace и другие команды. В текущем upstream присутствует также frida-strace. Наличие команд в main не означает их наличие или совместимость в установленном patched-пакете.

**Конкретный конфликт:** текущий [setup.py](https://github.com/frida/frida-tools/blob/main/setup.py) требует `frida >= 17.10.0, < 18.0.0`. Установленный binding сообщает 17.2.14. Поэтому установка свежего frida-tools обычным pip-resolver может попытаться заменить binding либо завершиться конфликтом. Нужен совместимый зафиксированный выпуск или осознанный backport конкретных функций после проверки API.

Рецепт Termux [root-packages/frida/build.sh](https://github.com/termux/termux-packages/blob/master/root-packages/frida/build.sh) на дату исследования использует 17.2.14, revision 4, с отключённым автообновлением. Это объясняет расхождение с [upstream 17.18.0](https://github.com/frida/frida/releases/tag/17.18.0), но не описывает частные rebro-патчи.

Для новых агентов переиспользовать структуру [oleavr/frida-agent-example](https://github.com/oleavr/frida-agent-example) или scaffolding `frida-create -t agent`. Демонстрационный код шаблона может содержать macOS-библиотеку libSystem.B.dylib: заменить пример на Android-логику до запуска.

### Библиотеки скриптов

**[0xdea/frida-scripts](https://github.com/0xdea/frida-scripts)** — один из лучших первых источников для этого стека. Конкретные файлы:

- [raptor_frida_android_enum.js](https://github.com/0xdea/frida-scripts/blob/master/raptor_frida_android_enum.js): перечисление Java-классов/методов.
- [raptor_frida_android_trace.js](https://github.com/0xdea/frida-scripts/blob/master/raptor_frida_android_trace.js): Java/native tracing.
- android-snippets: донор отдельных приёмов, с дополнительной ревизией.

README сообщает тестирование основных скриптов с Frida 17.3.2, а snippets — с версиями до 17.0.0. Это полезный сигнал, но не сертификат совместимости с Android 16 и patched bridge.

**[iddoeldor/frida-snippets](https://github.com/iddoeldor/frida-snippets)** — обширная библиотека небольших рецептов. Для собственного набора особенно полезны sections Hook overloads, Hook reflection, Reveal native methods, Binder transactions, Log SQLite query, Print shared preferences updates, Webview URLS, Socket activity, Stalker, Load C module. Последний push — 2024-11-29: перенос API 17 обязателен там, где используются старые вызовы. Не загружать весь README как единый агент.

**[Medusa](https://github.com/Ch0pin/medusa)** — основной донор модулей. README заявляет более 90 модулей. Первыми изучать каталоги:

- [http_communications](https://github.com/Ch0pin/medusa/tree/master/modules/http_communications), [webviews](https://github.com/Ch0pin/medusa/tree/master/modules/webviews), [sockets](https://github.com/Ch0pin/medusa/tree/master/modules/sockets);
- [intents](https://github.com/Ch0pin/medusa/tree/master/modules/intents), [content_providers](https://github.com/Ch0pin/medusa/tree/master/modules/content_providers);
- [db_queries](https://github.com/Ch0pin/medusa/tree/master/modules/db_queries), [file_system](https://github.com/Ch0pin/medusa/tree/master/modules/file_system);
- [JNICalls](https://github.com/Ch0pin/medusa/tree/master/modules/JNICalls), [code_loading](https://github.com/Ch0pin/medusa/tree/master/modules/code_loading), [encryption](https://github.com/Ch0pin/medusa/tree/master/modules/encryption).

**Почему оболочка Medusa требует работы:** medusa.py вызывает adb; libraries/natives.py извлекает Java bridge из установленного frida-tools и преобразует его в глобальный Java. Это не автоматически совпадает с bridge-final.js. Для phone-only режима удобнее сначала использовать экспортированный/адаптированный набор модулей через существующий rebro-loader. Полную оболочку подключать после замены ADB-зависимых операций и согласования bridge. [Исходники](https://github.com/Ch0pin/medusa/blob/master/libraries/natives.py).

### Сеть и TLS

**[HTTP Toolkit interception scripts](https://github.com/httptoolkit/frida-interception-and-unpinning)** — сильный первый выбор для раздельного управления proxy, CA trust и pinning. Взять:

- config.js — общий конфиг;
- android/android-proxy-override.js;
- android/android-system-certificate-injection.js;
- android/android-certificate-unpinning.js;
- android/android-certificate-unpinning-fallback.js;
- native-connect-hook.js и native-tls-hook.js — при реальной необходимости native-покрытия.

Не включать все изменения поведения сразу: начать с конкретного отсутствующего слоя. В README config.js должен идти первым. BLOCK_HTTP3 по умолчанию меняет поведение UDP/443; это учитывать при интерпретации результатов. Успешный hook Java trust manager ещё не означает охват Cronet, Flutter или произвольного собственного TLS-кода. Для контрольного прогона есть [android-ssl-pinning-demo](https://github.com/httptoolkit/android-ssl-pinning-demo).

**[friTap](https://github.com/fkie-cad/friTap)** — дополнительный инструмент, когда нужен key log, plaintext capture или работа с TLS-библиотекой. README содержит таблицу возможностей по библиотекам: key extraction и plaintext R/W поддерживаются неодинаково. В source есть `-H/--host` для remote endpoint.

Пример формы подключения после установки и проверки выбранной версии, **не выполненная команда**:

~~~bash
fritap -H 127.0.0.1:27044 -k keys.log com.example.lab
~~~

Для TLS-библиотек без символов в проекте есть paths через patterns/offsets. [BoringSecretHunter](https://github.com/monkeywave/BoringSecretHunter) помогает получать некоторые паттерны через Ghidra; это второй этап, часто удобнее на внешней машине.

**Ограничение friTap для текущего телефона:** requirements включают frida-tools, AndroidFridaManager, psutil и ряд нативных Python-зависимостей. Python 3.14 + Android/Bionic требуют проверки наличия сборок. Автоматическое управление сервером не использовать для замены rebro. Key-log режим не равнозначен успешному packet capture: вспомогательные операции могут ожидать ADB/root.

**[PCAPdroid](https://github.com/emanuele-f/PCAPdroid)** полезен как Android-интерфейс сетевой видимости/PCAP. Захват пакетов сам по себе не расшифровывает TLS; pinning и поддержка расшифровки остаются отдельными условиями. [mitmproxy](https://github.com/mitmproxy/mitmproxy) — хороший proxy/API-компонент, но его нативные зависимости проверять отдельно для phone-only среды.

### Java/JNI/native

**[Objection](https://github.com/sensepost/objection)** — полезный интерактивный инструмент после проверки агента. Текущий CLI поддерживает network host/port. Пример формы запуска для проверенного выпуска:

~~~bash
objection -N -h 127.0.0.1 -P 27044 -n com.example.lab start
~~~

Флаги проверить через --help именно установленного выпуска. В версиях CLI встречается explore; в текущем source это совместимый старый вход, предпочтителен start.

Objection загружает свой objection/agent.js; dependencies агента включают frida-java-bridge. В [issue #800](https://github.com/sensepost/objection/issues/800) описана поломка после ART-обновления, которую одно обновление сервера не исправляло. Issue закрыт, автор сообщает исправление в [1.12.5](https://github.com/sensepost/objection/releases/tag/1.12.5). Это пример зависимости от встроенного bridge, а не утверждение, что текущий Objection сломан. Для rebro совместимость всё равно предстоит подтвердить.

**[jnitrace](https://github.com/chame1eon/jnitrace)** и [jnitrace-engine](https://github.com/chame1eon/jnitrace-engine) хорошо показывают подход к JNI tracing. Последние pushes — 2023 год; README содержит старый Memory.readCString. Кандидаты на перенос/пересборку, не первая универсальная установка. Remote подключение поддержано.

**[frida_hook_libart](https://github.com/lasting-yang/frida_hook_libart)** — полезный источник для JNI/RegisterNatives. На Android 16 проверять символы и runtime layout; фиксированные ART offsets из примера нельзя считать переносимыми.

**[r2frida](https://github.com/nowsecure/r2frida)** особенно интересен, поскольку radare2 уже установлен. Даёт runtime maps, exports, поиск памяти и запуск агентов из привычного r2. Но плагин поставляется со своим Frida core; установленный Python binding автоматически им не используется. Сначала сборка/проверка под Android arm64 и radare2 6.2.0, затем подключение к 27044. Команда r2pm из desktop README не доказывает готовность phone-only сборки.

**Stalker и frida-itrace:** включать на выбранный поток/функцию и короткое окно. Начинать с counters/call summaries, затем detailed trace. Наличие PAC/BTI не доказывает несовместимость Frida. В [17.18.0](https://frida.re/news/2026/09/09/frida-17-18-0-released/) есть ARM64/BTI-исправления. Frida-strace — отдельная новая возможность: работоспособность обычного attach не доказывает работу eBPF-backend на Samsung kernel; есть [репорт с S23 Ultra/Android 16](https://github.com/frida/frida/issues/3723), который нельзя автоматически переносить на этот S24 Ultra.

### Специализированные задачи

| Задача | Проект | Условие выбора |
|---|---|---|
| Unity IL2CPP | [frida-il2cpp-bridge](https://github.com/vfsfitvnm/frida-il2cpp-bridge) | Использовать для IL2CPP, фиксируя версию bridge и Unity |
| Flutter AOT | [blutter](https://github.com/worawit/blutter) | Поддерживает Android arm64 libapp.so, создаёт blutter_frida.js; Dart-сборка тяжёлая |
| Flutter engine patching | [reFlutter](https://github.com/Impact-I/reFlutter) | Отдельный APK/engine workflow; Frida-пример README использует 16.7.19 |
| Выборочный DEX/SO dump | [frida_dump](https://github.com/lasting-yang/frida_dump) | Проверка API 17, размеров и Android 16 |
| Gadget в APK | [ksg97031/frida-gadget](https://github.com/ksg97031/frida-gadget) | Есть явный --arch и --custom-gadget-path; переподпись меняет приложение |
| Gadget через Zygisk | [ZygiskFrida](https://github.com/lico-n/ZygiskFrida) | Нужен действующий Zygisk, одного KernelSU недостаточно |

Gadget полезен для отдельного лабораторного APK и другого момента загрузки инструментации. Он не является обязательной заменой уже работающего root-сервера. Если его использовать параллельно, предусмотреть отдельный порт; 27044 уже занят rebro. В app-process конфиг и JS должны быть доступны именно UID приложения.

### Интерфейсы и интеграции

- **RMS:** [репозиторий](https://github.com/m0bilesecurity/RMS-Runtime-Mobile-Security). Удобный web UI, но собственные Node binding и bridge; README описывает работу с SystemUI как исходным процессом. Это не оптимальная первая точка интеграции с текущим rebro.
- **Brida:** [репозиторий](https://github.com/federicodotta/Brida). Хороший мост к Burp для вызова функций приложения из HTTP workflow. При отсутствии Burp/внешней станции низкий приоритет.
- **Ghidra hook generator:** [CENSUS](https://github.com/CENSUS/ghidra-frida-hook-gen). Полезен при статическом анализе native-библиотек. Сгенерированные hooks проверять на момент загрузки .so, адреса и сигнатуры.
- **ghidra-frida:** [NSA](https://github.com/NationalSecurityAgency/ghidra-frida). TraceRMI-интеграция, пока в обзорной категории: минимальная README недостаточна для обещания turnkey-совместимости.
- **ghidra2frida:** [репозиторий](https://github.com/federicodotta/ghidra2frida). Дополнительный, более старый вариант интеграции.

## 3. Как организовать собственный набор

Практичная единица переиспользования — небольшой агент с понятными входами и выводом. Рекомендуемая структура (предложение, не существующие файлы пользователя):

| Каталог/файл | Назначение |
|---|---|
| baseline/manifest.json | Полные hashes сервиса, binding, bridge; версия прошивки и endpoint |
| vendor/OWNER/REPO/ | Только необходимые upstream-файлы, зафиксированный commit и лицензия |
| agents/java/ | Разведка и hooks Java |
| agents/jni/ | Регистрация native-методов, ограниченные JNI events |
| agents/native/ | Модули, exports, backtraces, выборочная трассировка |
| agents/network/ | Метаданные запросов, выбранные TLS hooks |
| agents/storage/ | SQLite/SharedPreferences/file events |
| profiles/ | Набор включённых hooks, class/package filters, лимиты |
| cases/CASE/session.json | Версии APK/.so, PID, timestamp, hashes агентов и конфиг |
| cases/CASE/events.jsonl | Структурированный поток событий |
| cases/CASE/artifacts/ | Ограниченные дампы и captures |

Первые 12 агентов, которые дают наибольшую пользу:

| Агент | Функция | Источник подхода |
|---|---|---|
| env-probe | arch, pointer size, page size, loaded modules | Frida JavaScript API |
| java-enum | классы и методы с фильтром | 0xdea enum |
| java-overload-trace | выбранные overloads, типы аргументов, retval | 0xdea / iddoeldor |
| classloader-watch | регистрация загрузчиков и поздних классов | Java bridge API / Medusa code_loading |
| native-module-watch | загрузка/выгрузка .so | Process.attachModuleObserver |
| jni-register-map | соответствие Java method ↔ native address | frida_hook_libart / jnitrace |
| native-call-trace | конкретный export/offset, bounded backtrace | Frida Interceptor / 0xdea |
| intents-observe | action, component, схема extras | Medusa intents |
| webview-observe | URL и JS-interface inventory | Medusa webviews |
| storage-observe | имена файлов, SQLite statements, preference keys | Medusa / iddoeldor |
| network-observe | endpoints и request metadata | Medusa HTTP / PCAPdroid |
| tls-profile | выбранный CA/proxy/TLS hook | HTTP Toolkit / friTap |

Пути и имена собственных агентов выше — предлагаемая организация, а не существующие upstream-файлы.

### Общий контракт агента

- Явные target package/PID и class/module filters.
- Ограничения max events, max bytes, duration и backtrace depth.
- События JSONL с timestamp, pid, tid, agent, kind, payload.
- Тяжёлые данные передавать бинарным send payload, не огромными hex-строками.
- По умолчанию сохранять только необходимые метаданные; дампы аргументов и содержимого включать явно.
- attach-only как первый проверочный режим; spawn/child gating — отдельный шаг.
- Возможность выгрузить свои listeners, timers и RPC-handler без перезапуска сервиса.
- Идентификация модулей по имени + ELF build-id/hash; offsets относительные к конкретной сборке.

### Bridge — отдельная часть контракта

В Frida 17 bridges вынесены из GumJS. REPL и frida-trace продолжают поставлять bridges, а произвольный create_script не получает Java автоматически. [Bridges](https://frida.re/docs/bridges/).

Для rebro нужно документировать, как именно bridge-final.js подключается к агенту. По одной сводке неизвестны его module format, экспорт и loader protocol. Нельзя автоматически импортировать свежий frida-java-bridge поверх него.

Также Java, созданная в одном отдельном Frida Script, не становится глобальной переменной другого Script: при обычном использовании это разные JS-контексты. Bridge и использующий его код должны оказаться в нужном контексте по предусмотренному loader-механизму. Именно это проверяется до интеграции Medusa/Objection/RMS.

### Изоляция зависимостей

venv защищает системные Python-файлы от обычной установки пакетов, но сам по себе не гарантирует использование patched binding: внутри него pip может поставить другой frida и затенить baseline.

Проверять отдельно:

1. Python interpreter и frida.__file__.
2. Сообщаемую версию binding и полный hash реального _frida.abi3.so.
3. Полный hash bridge, реально включённого в bundle.
4. План resolver до установки.
5. Не требует ли приложение API, появившийся после 17.2.14.

`--no-deps` допустим только при ручном согласовании всех зависимостей; это не универсальный способ сделать несовместимый пакет совместимым. Обычный `pip install -U frida frida-tools` для этой конфигурации не является рекомендуемым шагом.

## 4. Закрытие APK build/sign gap

**apksigner уже есть в официальных рецептах Termux**: [packages/apksigner/build.sh](https://github.com/termux/termux-packages/blob/master/packages/apksigner/build.sh). На дату проверки рецепт использует Android Build Tools 37.0.0, требует openjdk-21 и запускает apksigner.jar через Java. У пользователя JDK 21 уже есть.

**zipalign:** официальный [termux/android-build-tools](https://github.com/termux/android-build-tools) включает его в список поддерживаемых tools и install targets. Рецепт пакета [aapt](https://github.com/termux/termux-packages/blob/master/packages/aapt/build.sh) использует этот проект на теге 16.0.0.4; в [CMake этого тега](https://github.com/termux/android-build-tools/blob/16.0.0.4/vendor/CMakeLists.txt) zipalign устанавливается. AAPT2 выделен в отдельный subpackage, поэтому наличие aapt2 само по себе не означает наличие zipalign. Доступность конкретных пакетов в зеркале телефона нужно проверить.

Первая локальная проверка, без изменений установленных пакетов:

~~~bash
apt-cache policy apksigner aapt
apt-get -s install apksigner aapt ripgrep jq
dpkg -L aapt
command -v zipalign
~~~

Если aapt не установлен, dpkg -L ожидаемо сообщит об этом. При установке сначала оценить симуляцию: расширение инструментария не должно неожиданно менять pinned Frida/Python-зависимости.

Для проверки современного alignment у APK с uncompressed .so Android рекомендует -P 16. Это ZIP-alignment; оно само по себе не исправляет внутренние ELF LOAD-segment alignment. Источники: [zipalign](https://developer.android.com/tools/zipalign), [apksigner](https://developer.android.com/tools/apksigner).

Пример последовательности для собственной лабораторной сборки и существующего keystore:

~~~bash
zipalign -P 16 -v 4 rebuilt.apk aligned.apk
apksigner sign --ks lab.jks --v2-signing-enabled true --out signed.apk aligned.apk
apksigner verify --verbose --print-certs signed.apk
zipalign -c -P 16 -v 4 signed.apk
~~~

Сначала alignment, затем подпись; проверка zipalign -c файл не изменяет. Все изменяемые APK должны быть отдельными рабочими копиями. Подпись своим ключом не сохраняет исходную identity приложения; для split APK требуется согласованная обработка набора. Это отдельная задача от runtime instrumentation.

`jarsigner` даёт JAR/v1 signing и не заменяет apksigner для v2. [uber-apk-signer](https://github.com/patrickfav/uber-apk-signer) можно держать как резерв, но его встроенные native executables требуют проверки Android/arm64. Desktop Linux x86_64 zipalign не запускается как Android arm64-бинарник.

## 5. Современные практики Frida

| Старый паттерн | Современный вариант |
|---|---|
| Module.findBaseAddress(name) | Process.findModuleByName(name), затем .base с проверкой null |
| Module.getExportByName(name, symbol) | Process.getModuleByName(name).getExportByName(symbol) |
| Module.findExportByName(null, symbol) | Module.findGlobalExportByName(symbol) |
| Memory.readUtf8String(pointer) | pointer.readUtf8String() |
| Process.enumerateModulesSync() | Process.enumerateModules() |

Источники: [миграция Frida 17](https://frida.re/news/2025/05/17/frida-17-0-0-released/), [JavaScript API](https://frida.re/docs/javascript-api/).

Рекомендации для собственных hooks:

- Использовать Java.perform для Java-runtime, учитывать overloads и конкретный ClassLoader.
- Для поздних .so предпочитать module observer фиксированному setTimeout.
- Перед чтением native-pointer проверять ожидаемый тип, доступность и ограничивать размер.
- Выводить hex/дампы только по фильтру; backtrace в каждом горячем вызове быстро становится узким местом.
- CModule рассматривать для действительно горячих callbacks, когда измерена цена JS-обработчика.
- Stalker включать на выбранный поток с коротким жизненным циклом; снимать только нужные виды событий.
- Разделять наблюдение и изменение поведения в разных профилях: так результаты сравнимы.
- Не использовать системные процессы как первый smoke target. Пользователь уже имеет рабочий лабораторный attach-путь.
- SELinux Enforcing и root-чтения использовать в существующей рабочей модели; общий setenforce 0 не нужен.
- Полные hashes в baseline не заменять сокращениями из переписки.

[Best practices](https://frida.re/docs/best-practices/) и [Stalker internals](https://frida.re/docs/stalker/) полезнее большинства универсальных «mega scripts».

## 6. Проверка нового компонента

Ниже предложенный gate интеграции; в этой сессии он не выполнялся:

1. Проверить, какие файлы и зависимости компонент собирается добавлять.
2. Соединиться с **127.0.0.1:27044**, перечислить процессы через текущий binding.
3. Attach к отдельному лабораторному приложению, загрузить минимальный native-only agent и корректно выгрузить.
4. Повторить с baseline bridge и простым Java.perform/Java.use.
5. Включить только один новый модуль; измерить ошибку/latency/RSS/размер логов.
6. Проверить detach/re-attach и повторяемость результата.
7. Добавить проверенный commit/hash и ограничения в профиль.

Для CLI всегда явно задавать host/port. Короткий -R по умолчанию ориентируется на стандартный 27042 и не выражает текущую конфигурацию; -U требует соответствующего USB/ADB-устройства. Базовая форма штатного клиента:

~~~bash
frida-ps -H 127.0.0.1:27044
~~~

Эта форма не обещает совместимость неизвестной версии CLI: сначала использовать уже работающий Python/ребро-контроллер.

Для storage ограничить одну трассировку по времени и размеру, включить rotation. Например, исходный рабочий бюджет 100 МБ или 60 секунд на прогон — политика пользователя, не технический предел инструмента. Полные memory dumps не должны быть режимом по умолчанию при 27 ГБ свободного места.

## 7. Что не брать за базу

| Кандидат | Причина |
|---|---|
| hluwa/frida-dexdump | Архивирован; последний push 2023-03-04 |
| google/ssl_logger | Архивирован; последний push 2020-10-20; сначала оценить friTap |
| Случайные готовые Termux wheels | Должны совпасть Python ABI, Bionic, build options и собственные патчи |
| Старые «универсальные» SSL/root bypass packs | Не гарантируют покрытие native TLS, custom loaders и обновлённого ART |
| strongR и другие patched-server forks | Не объясняют необходимость замены работающего rebro, незаметность не гарантируется |
| Глобальный npm/pip upgrade ради одной оболочки | Риск затенения/замены baseline и нового bridge |
| MobSF/Ghidra/Burp одновременно на телефоне | Низкий приоритет при текущем RAM/storage-бюджете |
| MagiskFrida/ZygiskFrida как автозамена | Другая модель запуска/инъекции; текущая уже работает |

Root-detection, anti-Frida, pinning и server-side integrity — разные механизмы. Работоспособность одного bypass не доказывает покрытие остальных. Для проверки поведения полезны отдельные учебные приложения и сравнение с исходным профилем.

## 8. Документация и учебные площадки

- [Frida JavaScript API](https://frida.re/docs/javascript-api/): Interceptor, Memory, Process, Stalker, Java.
- [Frida bridges](https://frida.re/docs/bridges/): что поменялось в 17 и как формировать агенты.
- [Frida best practices](https://frida.re/docs/best-practices/): корректность и производительность.
- [Frida Stalker](https://frida.re/docs/stalker/): детали AArch64-трассировки.
- [Frida Gadget](https://frida.re/docs/gadget/): варианты interaction и конфигурация.
- [OWASP MASTG](https://mas.owasp.org/MASTG/) и [репозиторий](https://github.com/OWASP/mastg): системная методика, техники, приложения.
- [Frida-Labs](https://github.com/DERE-ad2001/Frida-Labs): упражнения на Android.
- [MASTG Hacking Playground](https://github.com/OWASP/MASTG-Hacking-Playground): учебные APK; учитывать возраст.
- [HTTP Toolkit pinning demo](https://github.com/httptoolkit/android-ssl-pinning-demo): воспроизводимая TLS-проверка.
- [DetectFrida](https://github.com/darvincisec/DetectFrida): старый, но полезный образец instrumentation detection.
- [awesome-frida](https://github.com/dweinstein/awesome-frida): навигация по экосистеме, со смешанным возрастом ссылок.
- [Frida CodeShare](https://codeshare.frida.re/): дополнительный каталог; конкретные опубликованные скрипты в этом обзоре не аудировались.

## 9. Порядок сборки арсенала

1. Зафиксировать полный baseline-манифест из уже имеющихся локальных данных.
2. Закрыть apksigner/zipalign и поставить небольшие утилиты поиска/JSON при приемлемом плане зависимостей.
3. Создать реестр агентов и общий JSONL-вывод вокруг существующего loader.
4. Перенести 0xdea enum/trace и 5–10 выбранных Medusa/iddoeldor snippets.
5. Сделать отдельный TLS-профиль HTTP Toolkit и проверить его на demo APK.
6. Добавить friTap при успешной проверке native Python-зависимостей/remote backend.
7. Оценить r2frida и Objection как отдельные интеграции.
8. Добавлять Flutter, IL2CPP, Gadget и подробный Stalker только под конкретный кейс.

**Следующий практический результат для этой среды:** baseline-aware loader + manifests + 10–15 проверенных агентов + закрытая APK-подпись. Полная установка всех 59 проектов не нужна.

## 10. Полный каталог исследованных репозиториев

Дата push отражает GitHub API и не является доказательством исправности, активности default branch или наличия свежего релиза. «README/код» — просмотрен README и/или указанные в тексте исходники; «метаданные» — проверены существование, описание и состояние репозитория. Лицензия — идентификатор GitHub API, не юридический вывод; NOASSERTION/неопределённое значение требует чтения LICENSE перед распространением заимствованного кода.

| № | Репозиторий | Роль / решение | Последний push | Архив | Проверка |
|---|---|---|---|---|---|
| 1 | [frida/frida](https://github.com/frida/frida) | **Основа**. Upstream-релизы и исходная точка для сравнения с rebro; рабочий сервис сохранять. | 2026-09-22 | Нет | Метаданные |
| 2 | [frida/frida-tools](https://github.com/frida/frida-tools) | **Основа**. ps/trace/create/compile/apk/itrace; использовать уже совместимые CLI. Текущий main требует frida>=17.10.0. | 2026-09-22 | Нет | README/код или docs |
| 3 | [frida/frida-python](https://github.com/frida/frida-python) | **Основа**. API для локального контроллера; установленный patched binding — опорный. | 2026-09-22 | Нет | README/код или docs |
| 4 | [frida/frida-java-bridge](https://github.com/frida/frida-java-bridge) | **Основа**. Источник API и исправлений ART; baseline bridge-final.js важнее автоматического обновления. | 2026-06-22 | Нет | README/код или docs |
| 5 | [frida/frida-compile](https://github.com/frida/frida-compile) | **Основа**. Сборка модульных агентов; учитывать уже работающий frida.Compiler и его версию. | 2026-03-27 | Нет | Метаданные |
| 6 | [frida/frida-gum](https://github.com/frida/frida-gum) | **Углубление**. Interceptor, Stalker, архитектурные детали, CModule; источник для native-инструментации. | 2026-09-22 | Нет | Метаданные |
| 7 | [frida/frida-itrace](https://github.com/frida/frida-itrace) | **По задаче**. Instruction tracing; тяжёлый режим после проверки совместимых клиентских API. | 2026-04-29 | Нет | Метаданные |
| 8 | [oleavr/frida-agent-example](https://github.com/oleavr/frida-agent-example) | **Переиспользовать**. Шаблон TypeScript-проекта и watch-сборки; адаптировать демонстрационный код под Android. | 2026-02-28 | Нет | README/код или docs |
| 9 | [termux/termux-app](https://github.com/termux/termux-app) | **Уже есть**. Терминал; учитывать подписи источников пакетов и ограничения фоновых процессов. | 2026-09-16 | Нет | README/код или docs |
| 10 | [termux/termux-packages](https://github.com/termux/termux-packages) | **Основа**. Официальные рецепты frida, apksigner, aapt; источник ARM64/Android-совместимых пакетов. | 2026-09-22 | Нет | README/код или docs |
| 11 | [termux/android-build-tools](https://github.com/termux/android-build-tools) | **Приоритет**. Исходники aapt/aapt2/zipalign; предпочтительный источник нативного zipalign. | 2026-07-16 | Нет | README/код или docs |
| 12 | [termux/proot-distro](https://github.com/termux/proot-distro) | **Резерв**. Дополнительный glibc-userland для отдельных утилит; текущему рабочему Frida не нужен. | 2026-09-22 | Нет | README/код или docs |
| 13 | [topjohnwu/Magisk](https://github.com/topjohnwu/Magisk) | **Справка**. Документация Samsung/root. У пользователя уже KernelSU — миграция не требуется. | 2026-09-22 | Нет | Метаданные |
| 14 | [ViRb3/magisk-frida](https://github.com/ViRb3/magisk-frida) | **Резерв**. Автозапуск upstream-сервера, README поддерживает KernelSU. Текущий rebro-сервис не заменять. | 2026-09-09 | Нет | README/код или docs |
| 15 | [Ch0pin/medusa](https://github.com/Ch0pin/medusa) | **Приоритет: модули**. 90+ модулей; отбирать по задаче. Оболочка зависит от ADB и bridge из frida-tools. | 2026-09-06 | Нет | README/код или docs |
| 16 | [Ch0pin/stheno](https://github.com/Ch0pin/stheno) | **По задаче**. Android-интерфейс для мониторинга intents совместно с Medusa; дополнительная интеграция. | 2024-07-29 | Нет | Метаданные |
| 17 | [sensepost/objection](https://github.com/sensepost/objection) | **Кандидат**. Runtime exploration; есть TCP-подключение. Агент со своим bridge; проверять отдельно. | 2026-09-17 | Нет | README/код или docs |
| 18 | [0xdea/frida-scripts](https://github.com/0xdea/frida-scripts) | **Приоритет**. Android trace/enum; основные скрипты заявляют тестирование с 17.3.2, старые snippets требуют ревизии. | 2026-08-02 | Нет | README/код или docs |
| 19 | [iddoeldor/frida-snippets](https://github.com/iddoeldor/frida-snippets) | **Переиспользовать**. Богатая библиотека рецептов Java/JNI/native/Binder/SQLite; переносить на API 17 выборочно. | 2024-11-29 | Нет | README/код или docs |
| 20 | [httptoolkit/frida-interception-and-unpinning](https://github.com/httptoolkit/frida-interception-and-unpinning) | **Приоритет**. Модульные Android/TLS/proxy-скрипты; config.js первым, выбирать минимальный набор. | 2026-09-18 | Нет | README/код или docs |
| 21 | [fkie-cad/friTap](https://github.com/fkie-cad/friTap) | **Кандидат**. TLS keys/plaintext PCAP, remote endpoint; зависимости и доступный backend проверить в изоляции. | 2026-09-19 | Нет | README/код или docs |
| 22 | [federicodotta/Brida](https://github.com/federicodotta/Brida) | **Внешний хост**. Интеграция Frida с Burp Suite; полезна при наличии Burp, не первоочередна для phone-only. | 2025-10-30 | Нет | Метаданные |
| 23 | [emanuele-f/PCAPdroid](https://github.com/emanuele-f/PCAPdroid) | **По задаче**. Android-сетевой монитор/PCAP. Расшифровка TLS — отдельная возможность с ограничениями. | 2026-09-20 | Нет | Метаданные |
| 24 | [mitmproxy/mitmproxy](https://github.com/mitmproxy/mitmproxy) | **По задаче**. HTTP(S)-proxy и автоматизация; для телефона отдельная проверка зависимостей и памяти. | 2026-09-10 | Нет | Метаданные |
| 25 | [chame1eon/jnitrace](https://github.com/chame1eon/jnitrace) | **Требует адаптации**. Специализированный JNI tracer, remote поддержан; код давно не обновлялся, Frida 17 не сертифицирована. | 2023-07-18 | Нет | README/код или docs |
| 26 | [chame1eon/jnitrace-engine](https://github.com/chame1eon/jnitrace-engine) | **Переиспользовать**. Движок JNI interception; исходник/референс после миграции API и сборки. | 2023-07-18 | Нет | Метаданные |
| 27 | [nowsecure/r2frida](https://github.com/nowsecure/r2frida) | **Кандидат**. radare2+Frida; хорошо дополняет установленный r2, но имеет собственный Frida core и сборочные требования. | 2026-09-21 | Нет | README/код или docs |
| 28 | [lasting-yang/frida_hook_libart](https://github.com/lasting-yang/frida_hook_libart) | **Переиспользовать**. JNI/RegisterNatives/ART hooks; символы и layout проверять на Android 16. | 2025-10-22 | Нет | Метаданные |
| 29 | [lasting-yang/frida_dump](https://github.com/lasting-yang/frida_dump) | **По задаче**. DEX/SO dumping; выборочные диапазоны, API 17 и ART проверять. | 2025-08-20 | Нет | Метаданные |
| 30 | [hluwa/frida-dexdump](https://github.com/hluwa/frida-dexdump) | **Архив**. Популярный DEX dumper архивирован; не считать готовым основным инструментом Android 16. | 2023-03-04 | Да | Метаданные |
| 31 | [vfsfitvnm/frida-il2cpp-bridge](https://github.com/vfsfitvnm/frida-il2cpp-bridge) | **По задаче**. Unity IL2CPP: классы, методы, tracing; для конкретных Unity-приложений. | 2026-09-06 | Нет | README/код или docs |
| 32 | [worawit/blutter](https://github.com/worawit/blutter) | **По задаче**. Flutter Android arm64 libapp.so; генерирует Frida-шаблон. Сборка Dart может быть тяжёлой. | 2026-08-18 | Нет | README/код или docs |
| 33 | [Impact-I/reFlutter](https://github.com/Impact-I/reFlutter) | **По задаче**. Патчинг Flutter engine; README Frida-примера фиксирует 16.7.19, нужен отдельный профиль. | 2026-08-11 | Нет | README/код или docs |
| 34 | [monkeywave/BoringSecretHunter](https://github.com/monkeywave/BoringSecretHunter) | **Внешний хост**. Ghidra-анализ stripped BoringSSL для паттернов friTap; нишевое дополнение. | 2026-06-11 | Нет | Метаданные |
| 35 | [m0bilesecurity/RMS-Runtime-Mobile-Security](https://github.com/m0bilesecurity/RMS-Runtime-Mobile-Security) | **Второй эшелон**. Web UI; свой Node binding/bridge, README описывает обращение к SystemUI. Нужна адаптация. | 2026-09-03 | Нет | README/код или docs |
| 36 | [NationalSecurityAgency/ghidra](https://github.com/NationalSecurityAgency/ghidra) | **Внешний хост**. Native static analysis; тяжёлая JVM-нагрузка для текущего телефона. | 2026-09-21 | Нет | Метаданные |
| 37 | [CENSUS/ghidra-frida-hook-gen](https://github.com/CENSUS/ghidra-frida-hook-gen) | **Внешний хост**. Генерация Frida hooks из Ghidra; учитывать загрузку модулей и сигнатуры. | 2026-09-03 | Нет | README/код или docs |
| 38 | [federicodotta/ghidra2frida](https://github.com/federicodotta/ghidra2frida) | **Второй эшелон**. Мост Ghidra↔Frida; старее, дополнительный PoC совместимости. | 2024-01-04 | Нет | Метаданные |
| 39 | [NationalSecurityAgency/ghidra-frida](https://github.com/NationalSecurityAgency/ghidra-frida) | **Наблюдать**. Поддержка Frida через Ghidra traceRMI; минимальная README, зрелость для этого стека не установлена. | 2026-01-15 | Нет | README/код или docs |
| 40 | [skylot/jadx](https://github.com/skylot/jadx) | **Уже есть**. Основной Java/DEX-декомпилятор; переиспользовать установленный 1.5.5. | 2026-09-12 | Нет | Метаданные |
| 41 | [iBotPeaches/Apktool](https://github.com/iBotPeaches/Apktool) | **Уже есть**. Ресурсы/smali/rebuild; установленный 3.0.3 уже работает. | 2026-09-21 | Нет | Метаданные |
| 42 | [patrickfav/uber-apk-signer](https://github.com/patrickfav/uber-apk-signer) | **Резерв**. Java-оболочка подписания; встроенный native zipalign должен соответствовать Android/arm64. | 2023-10-30 | Нет | Метаданные |
| 43 | [MobSF/Mobile-Security-Framework-MobSF](https://github.com/MobSF/Mobile-Security-Framework-MobSF) | **Внешний хост**. Крупный статический/динамический framework; не помещать в первичный phone-only набор. | 2026-09-22 | Нет | Метаданные |
| 44 | [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) | **Внешний хост**. Управление экраном при появлении ПК/ADB-транспорта. | 2026-09-19 | Нет | Метаданные |
| 45 | [ksg97031/frida-gadget](https://github.com/ksg97031/frida-gadget) | **По задаче**. APK patcher; --arch arm64 и --custom-gadget-path уменьшают зависимость от ADB/автоскачивания. | 2026-08-16 | Нет | Метаданные |
| 46 | [lico-n/ZygiskFrida](https://github.com/lico-n/ZygiskFrida) | **Эксперимент**. Инъекция Gadget через Zygisk. KernelSU сам по себе не подтверждает наличие Zygisk. | 2025-10-18 | Нет | README/код или docs |
| 47 | [OWASP/mastg](https://github.com/OWASP/mastg) | **Документация**. Методика мобильного тестирования и RE; текущий канонический репозиторий. | 2026-09-20 | Нет | Метаданные |
| 48 | [OWASP/mastg-hacking-playground](https://github.com/OWASP/MASTG-Hacking-Playground) | **Лаборатория**. Учебные приложения; возраст Android-проектов учитывать. | 2022-10-31 | Нет | Метаданные |
| 49 | [DERE-ad2001/Frida-Labs](https://github.com/DERE-ad2001/Frida-Labs) | **Лаборатория**. Набор практических Android-задач для проверки собственного инструментария. | 2026-02-22 | Нет | Метаданные |
| 50 | [httptoolkit/android-ssl-pinning-demo](https://github.com/httptoolkit/android-ssl-pinning-demo) | **Лаборатория**. Контрольное Android-приложение для воспроизводимой проверки TLS hooks. | 2026-07-31 | Нет | Метаданные |
| 51 | [dweinstein/awesome-frida](https://github.com/dweinstein/awesome-frida) | **Навигация**. Широкий индекс; ссылки включают старые и iOS-only проекты. | 2026-04-10 | Нет | Метаданные |
| 52 | [r0ysue/AndroidFridaBeginnersBook](https://github.com/r0ysue/AndroidFridaBeginnersBook) | **Учебный архив**. Материалы книги и примеры; перенос на Frida 17 вручную. | 2022-08-06 | Нет | Метаданные |
| 53 | [darvincisec/DetectFrida](https://github.com/darvincisec/DetectFrida) | **Лаборатория**. Учебный образец детектирования instrumentation; не карта всех современных защит. | 2021-06-12 | Нет | Метаданные |
| 54 | [google/ssl_logger](https://github.com/google/ssl_logger) | **Архив**. Архивирован; для нового TLS workflow сначала оценивать friTap. | 2020-10-20 | Да | Метаданные |
| 55 | [Nightbringer21/fridump](https://github.com/Nightbringer21/fridump) | **Старый инструмент**. Общий memory dumper; ограниченная свежесть, проверять API 17 и объёмы вывода. | 2024-08-07 | Нет | Метаданные |
| 56 | [CrackerCat/strongR-frida-android](https://github.com/CrackerCat/strongR-frida-android) | **Не в базу**. Патченный сервер для обхода детектирования; не заменяет проверенный rebro и не гарантирует незаметность. | 2025-04-14 | Нет | Метаданные |
| 57 | [hzzheyang/strongR-frida-android](https://github.com/hzzheyang/strongR-frida-android) | **Не в базу**. Более свежий fork патченного сервера; изменения и сборки требуют отдельного аудита. | 2026-09-09 | Нет | Метаданные |
| 58 | [rendiix/termux-zipalign](https://github.com/rendiix/termux-zipalign) | **Не в базу**. Старые prebuilt-бинарники и сторонний репозиторий; официальный Termux build-source предпочтительнее. | 2021-09-06 | Нет | README/код или docs |
| 59 | [b-erdem/rekit](https://github.com/b-erdem/rekit) | **Наблюдать**. Новый малый toolkit для API/traffic/HAR; зрелость и покрытие требуют отдельного PoC. | 2026-05-01 | Нет | Метаданные |

## 11. Опорные revisions для воспроизводимого сравнения

Это HEAD default branch, полученный при исследовании; не автоматически рекомендованный релиз для установки. README/код читались в ходе этой же сессии, но репозитории могут обновляться независимо.

| Репозиторий | Зафиксированный commit |
|---|---|
| frida/frida-tools | [99eaf0f0d38124ec4848a737ce2f0bfbcf5c0a31](https://github.com/frida/frida-tools/commit/99eaf0f0d38124ec4848a737ce2f0bfbcf5c0a31) |
| Ch0pin/medusa | [f5835f590fd9cc6ca792bc9cd757a6825e4cde54](https://github.com/Ch0pin/medusa/commit/f5835f590fd9cc6ca792bc9cd757a6825e4cde54) |
| sensepost/objection | [35c4e2c9e68a0354b21db4833252e0c4acd5bd28](https://github.com/sensepost/objection/commit/35c4e2c9e68a0354b21db4833252e0c4acd5bd28) |
| fkie-cad/friTap | [31a7e07eb673914264ee274d826e8b4f7f40db2c](https://github.com/fkie-cad/friTap/commit/31a7e07eb673914264ee274d826e8b4f7f40db2c) |
| termux/termux-packages | [3454667d1a1cd005c59d2abbf2ab3e8002b8a568](https://github.com/termux/termux-packages/commit/3454667d1a1cd005c59d2abbf2ab3e8002b8a568) |
| 0xdea/frida-scripts | [8b8058a118f655cfd2f539ae9e8f8b05b0e72a2f](https://github.com/0xdea/frida-scripts/commit/8b8058a118f655cfd2f539ae9e8f8b05b0e72a2f) |
| httptoolkit/frida-interception-and-unpinning | [b3ea8f63a14b9a6f1b60d9d1535dea7e6b23f028](https://github.com/httptoolkit/frida-interception-and-unpinning/commit/b3ea8f63a14b9a6f1b60d9d1535dea7e6b23f028) |

## 12. Лицензии: навигационный индекс

- [frida/frida](https://github.com/frida/frida): NOASSERTION.
- [frida/frida-tools](https://github.com/frida/frida-tools): NOASSERTION.
- [frida/frida-python](https://github.com/frida/frida-python): NOASSERTION.
- [frida/frida-java-bridge](https://github.com/frida/frida-java-bridge): Не определена API.
- [frida/frida-compile](https://github.com/frida/frida-compile): NOASSERTION.
- [frida/frida-gum](https://github.com/frida/frida-gum): NOASSERTION.
- [frida/frida-itrace](https://github.com/frida/frida-itrace): MIT.
- [oleavr/frida-agent-example](https://github.com/oleavr/frida-agent-example): Не определена API.
- [termux/termux-app](https://github.com/termux/termux-app): NOASSERTION.
- [termux/termux-packages](https://github.com/termux/termux-packages): NOASSERTION.
- [termux/android-build-tools](https://github.com/termux/android-build-tools): Apache-2.0.
- [termux/proot-distro](https://github.com/termux/proot-distro): GPL-3.0.
- [topjohnwu/Magisk](https://github.com/topjohnwu/Magisk): GPL-3.0.
- [ViRb3/magisk-frida](https://github.com/ViRb3/magisk-frida): Не определена API.
- [Ch0pin/medusa](https://github.com/Ch0pin/medusa): GPL-3.0.
- [Ch0pin/stheno](https://github.com/Ch0pin/stheno): GPL-3.0.
- [sensepost/objection](https://github.com/sensepost/objection): GPL-3.0.
- [0xdea/frida-scripts](https://github.com/0xdea/frida-scripts): MIT.
- [iddoeldor/frida-snippets](https://github.com/iddoeldor/frida-snippets): Не определена API.
- [httptoolkit/frida-interception-and-unpinning](https://github.com/httptoolkit/frida-interception-and-unpinning): AGPL-3.0.
- [fkie-cad/friTap](https://github.com/fkie-cad/friTap): GPL-3.0.
- [federicodotta/Brida](https://github.com/federicodotta/Brida): MIT.
- [emanuele-f/PCAPdroid](https://github.com/emanuele-f/PCAPdroid): GPL-3.0.
- [mitmproxy/mitmproxy](https://github.com/mitmproxy/mitmproxy): MIT.
- [chame1eon/jnitrace](https://github.com/chame1eon/jnitrace): MIT.
- [chame1eon/jnitrace-engine](https://github.com/chame1eon/jnitrace-engine): MIT.
- [nowsecure/r2frida](https://github.com/nowsecure/r2frida): MIT.
- [lasting-yang/frida_hook_libart](https://github.com/lasting-yang/frida_hook_libart): MIT.
- [lasting-yang/frida_dump](https://github.com/lasting-yang/frida_dump): Не определена API.
- [hluwa/frida-dexdump](https://github.com/hluwa/frida-dexdump): GPL-3.0.
- [vfsfitvnm/frida-il2cpp-bridge](https://github.com/vfsfitvnm/frida-il2cpp-bridge): MIT.
- [worawit/blutter](https://github.com/worawit/blutter): MIT.
- [Impact-I/reFlutter](https://github.com/Impact-I/reFlutter): GPL-3.0.
- [monkeywave/BoringSecretHunter](https://github.com/monkeywave/BoringSecretHunter): MIT.
- [m0bilesecurity/RMS-Runtime-Mobile-Security](https://github.com/m0bilesecurity/RMS-Runtime-Mobile-Security): GPL-3.0.
- [NationalSecurityAgency/ghidra](https://github.com/NationalSecurityAgency/ghidra): Apache-2.0.
- [CENSUS/ghidra-frida-hook-gen](https://github.com/CENSUS/ghidra-frida-hook-gen): BSD-2-Clause.
- [federicodotta/ghidra2frida](https://github.com/federicodotta/ghidra2frida): MIT.
- [NationalSecurityAgency/ghidra-frida](https://github.com/NationalSecurityAgency/ghidra-frida): Не определена API.
- [skylot/jadx](https://github.com/skylot/jadx): Apache-2.0.
- [iBotPeaches/Apktool](https://github.com/iBotPeaches/Apktool): Apache-2.0.
- [patrickfav/uber-apk-signer](https://github.com/patrickfav/uber-apk-signer): Apache-2.0.
- [MobSF/Mobile-Security-Framework-MobSF](https://github.com/MobSF/Mobile-Security-Framework-MobSF): GPL-3.0.
- [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy): Apache-2.0.
- [ksg97031/frida-gadget](https://github.com/ksg97031/frida-gadget): MIT.
- [lico-n/ZygiskFrida](https://github.com/lico-n/ZygiskFrida): MIT.
- [OWASP/mastg](https://github.com/OWASP/mastg): CC-BY-SA-4.0.
- [OWASP/mastg-hacking-playground](https://github.com/OWASP/MASTG-Hacking-Playground): GPL-3.0.
- [DERE-ad2001/Frida-Labs](https://github.com/DERE-ad2001/Frida-Labs): MIT.
- [httptoolkit/android-ssl-pinning-demo](https://github.com/httptoolkit/android-ssl-pinning-demo): Apache-2.0.
- [dweinstein/awesome-frida](https://github.com/dweinstein/awesome-frida): CC0-1.0.
- [r0ysue/AndroidFridaBeginnersBook](https://github.com/r0ysue/AndroidFridaBeginnersBook): Не определена API.
- [darvincisec/DetectFrida](https://github.com/darvincisec/DetectFrida): MIT.
- [google/ssl_logger](https://github.com/google/ssl_logger): Apache-2.0.
- [Nightbringer21/fridump](https://github.com/Nightbringer21/fridump): Не определена API.
- [CrackerCat/strongR-frida-android](https://github.com/CrackerCat/strongR-frida-android): Не определена API.
- [hzzheyang/strongR-frida-android](https://github.com/hzzheyang/strongR-frida-android): Не определена API.
- [rendiix/termux-zipalign](https://github.com/rendiix/termux-zipalign): Apache-2.0.
- [b-erdem/rekit](https://github.com/b-erdem/rekit): MIT.
