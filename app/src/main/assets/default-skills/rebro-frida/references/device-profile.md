# Профиль устройства по инвентаризации 22.09.2026, 23:23 MSK

Источник — пользовательский `inventory-20260922-232336-bc9bd0.zip`, collector 2.1.0.
Внутренние SHA256SUMS проверены. Производный JSON находится в config/device-observed.json
пакетов environment/frida и в DEVICE-PROFILE.observed.json внешнего дистрибутива.
Это снимок наблюдений для выбора команд; он не является trusted pin manifest.

**Дополнение 2.3:** после inventory пользователь предоставил полный системный промпт
с каноническими paths/pins и экспортом frida_java_bridge_default. Создан отдельный
pins.rebro-known-good-20260922.json; наблюдения ниже не переписаны как новые замеры.
Текстовый отчёт агента также сообщает pass native RPC smoke и Compiler TS build.
Повторный широкий collector для paths/pins больше не нужен. Читать
system-prompt-integration.md; ниже сохранён диагноз предыдущей инвентаризации.

## Что подтверждено отчётом

| Параметр | Наблюдение | Следствие для агента |
|---|---|---|
| Hardware | SM-S928B / e3q, QTI SM8650, pineapple | Не использовать прежнее предположение об Exynos |
| Android / ABI / page size | Android 16, API 36, arm64-v8a, 4096 bytes | Native tools нужны Android arm64; 16 KiB zipalign остаётся параметром APK, не размером страниц этого ядра |
| RAM / data | MemAvailable 2905080 KiB ≈ 2,77 GiB; свободно ≈ 25,91 GiB | Один JVM job, heap 1536 MiB, reserve 1024 MiB; signing 512 MiB; перед job обновлять сведения |
| Питание | 41%, AC/USB/wireless false; battery 36,2°C | Старое наблюдение «постоянно заряжается» устарело |
| Root / freezer | su работает; сервис в u:r:ksu:s0; cgroup.freeze=0 | Ограниченные root reads доступны; это не гарантия против будущего freezer/LMKD |
| APK tools | aapt/aapt2 16.0.0.4-2; apksigner package 37.0.0; Java 21.0.12 | Не скачивать другой signer и не переустанавливать рабочие пакеты |
| zipalign | В составе aapt; help поддерживает -P 4/16/64 | Старый `zipalign -h` выдаёт exit 2 из-за неизвестного -h; это не отсутствие инструмента |
| Удобства | rg 15.2.0, jq 1.8.2 установлены; sqlite3 CLI отсутствует | SQLite CLI — необязательное дополнение для задач с БД |
| Android users | 0 и 150 | Всегда выбирать конкретный user; изменение общего APK-кода может затронуть другие профили |
| RikkaHub | excp.rikkahub.debug 2.5.1 / code 186, также две другие установки | Выбрать нужную установку; версии приложения не доказывают commit установленного APK |

`apksigner version` возвращает **0.9**, а установленный Termux package имеет версию
**37.0.0**: это разные поля. Наличие tools и справки не подтверждает реальную
align→sign→verify→install операцию. Сохранить существующий signer и выполнить
приёмку на собственном lab APK по процедурам sign/install/verify.

## Frida: рабочий transport и оставшиеся сведения

Endpoint `127.0.0.1:27044`: handshake **19,4 ms**, access=full, 401 процесс.
Наблюдён PID сервиса 17436; при следующем запуске определить его заново.

| Артефакт | Полный наблюдённый SHA-256 |
|---|---|
| Исполняемый сервис `/proc/17436/exe` | c377c4bb2eb42bfc99a89bb68bc65d4581f1fd84057b23459887d1da3da3fd87 |
| Реально загруженный `_frida.abi3.so` | d3550a89c0cdf32717417f3fdf116e1b462e7c1feb434fbd61ac7f7747eec61a |

Пути находятся в JSON. Binding сообщает 17.2.14; dpkg — 17.2.14-4+rebro.compiler1.
Наличие Compiler.build/watch подтверждено; компиляция в этой инвентаризации не выполнялась.
Префиксы/суффиксы двух hashes совпадают с ранее сообщёнными пользователем. Полные hashes
получены наблюдением, поэтому не заполнять ими trusted pins автоматически.

Поиск 2.1.0 посетил ровно 25000 entries и остановился в деревьях сборок.
Bridge и baseline не найдены. Три найденных Python-файла не содержат create_script,
bridge или иных признаков Java loader. Старая missing-сводка ошибочно считала наличие
любого прочитанного кандидата достаточным: loader contract также остаётся неизвестен.

Collector 2.2.0 ищет в ширину, ограничивает один каталог и каждый тип кандидатов
отдельно, читает допустимые bridge paths из candidate manifests и проверяет соседние
launchers по содержимому. Совпадение с manifest — полезное сопоставление, не доказательство
его происхождения. Ни один найденный скрипт не запускается.

До получения системного промпта для этого предлагался точечный запуск из Termux:

```sh
python3 rebro_collect.py --focus frida
```

Теперь этот повтор не требуется. Для неизвестной среды он пропускает полный hardware/tool/package inventory, использует scoped su reads,
проверяет прежний loopback endpoint и выделяет поиск до 100000 entries. Нужен новый
получившийся ZIP. Не выбирать первый найденный bridge или самый новый mtime как
known-good. До определения рабочего Java loader доступны offline native build и
остальные APK-навыки; live Frida adapter по-прежнему требует полный trusted baseline.

## Связь с форком

Контракт сверён с PR #1 на head `68f0038279735eb33502815cc874f930021abbd7`.
Внешний kit не импортировать одним skill: использовать 10 ZIP из imports/ отдельно.
Синхронизировать из выбранной установки RikkaHub и брать skill_root из ответа.
На телефоне несколько RikkaHub package IDs; их Termux-копии разделены по app ID.
Не строить путь из versionName и не использовать копию другого package ID.

На момент этого inventory native/Java smoke ещё не были предоставлены. Позднее
пользователь сообщил native RPC smoke и Compiler build; свежий Java hook в том
прогоне не выполнялся. Реальная подпись/установка lab APK и применение нового kit
на телефоне пока не подтверждены. Phone end-to-end не объявлять пройденным.

Источник контракта: [руководство форка](https://github.com/shizzgar/rikkahub-agent/blob/68f0038279735eb33502815cc874f930021abbd7/docs/agent-runtime/trajectory-and-termux-skills.ru.md).
