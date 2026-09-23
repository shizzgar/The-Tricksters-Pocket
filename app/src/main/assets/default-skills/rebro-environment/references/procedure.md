# Доподготовка Termux

## Начало environment stage

Работать из skill_root `rebro-environment`. Создать отдельный data/ каталог текущего
attempt; полный case/attempt не объявлять output. Снять реальное наблюдение:

```sh
python3 scripts/doctor.py --workspace "$REBRO_CASE" --out "$DOCTOR_JSON"
```

Для scoped root/network inspection добавить `--root`; `--probe-frida` включать,
когда проверка transport входит в кейс. Doctor не исправляет среду и не выдаёт
универсальный ready verdict. Агент проверяет необходимые для выбранного маршрута
tools, RAM/disk и доступность операций. Для одной подписи root/Frida не обязательны.
Закрыть environment receipt с evidence/output = doctor.json только после этих gates;
при нехватке зависимости сохранить blocked и выполнить нужный раздел ниже.

Для последующей sign нужны Python, Java/apksigner, aapt2, native zipalign с `-P 16`.
Для rebuild добавить Apktool/JDK и framework; для install — фактический su/PM.

## Приоритеты

Для устройства из отчёта **22.09.2026 23:23 MSK** apksigner, zipalign, rg и jq уже
установлены. Прочитать [профиль устройства](device-profile.md) и config/device-observed.json этого пакета.
Не применять приведённую ниже установку всех dependencies повторно: из удобств
отсутствует только sqlite3 CLI; добавлять его по задаче. После передачи системного
промпта в 2.3 paths/pins/flat recipe известны: config/pins.rebro-known-good-20260922.json.
Приоритет — проверка нового Java adapter и lab приёмка signing tools по нужному scope,
не повторный поиск bridge и не полный ремонт Frida.

| Приоритет | Сделать | Критерий готовности |
|---|---|---|
| P0 | Проверить ownership/PATH для zipalign, поставить apksigner при отсутствии | align → sign → verify проходит на тестовом APK |
| P0 | Зафиксировать полный Frida baseline, loaders и known-good scripts | три полных hash, capability matrix, восстановимые файлы |
| P0 | Добавить case layout и ограничение JVM | два тяжёлых задания не запускаются вместе |
| P1 | ripgrep, jq, sqlite, tmux, coreutils, file, binutils при необходимости | команды найдены, версии записаны |
| P1 | Сохранить APK set и сертификаты | immutable-by-convention input, acquisition manifest |
| P1 | Контроль места, логов, температуры | у каждой долгой задачи deadline и вывод в файлы |
| P2 | PC/offload: Ghidra, тяжёлый MobSF, большие Gradle builds | по потребности, не обязательные сервисы на телефоне |

## Сначала чтение

```sh
command -v apksigner zipalign aapt aapt2 rg jq sqlite3
dpkg-query -W aapt apksigner openjdk-21 ripgrep jq sqlite
dpkg-query -L aapt
apt-cache policy aapt apksigner ripgrep jq sqlite
```

`apksigner` существует в официальном termux-packages. Проверенный recipe использует
SDK JAR и зависит от OpenJDK 21. Внешний JAR — запасной вариант, не обязательный путь.
`zipalign` входит в исходный проект `termux/android-build-tools`, на котором основан
пакет `aapt`. Проверить локальный `dpkg-query -L aapt`: наличие отдельного `aapt2`
не доказывает наличие полного пакета и zipalign. Состояние зеркала проверяется на телефоне.

Перед изменением пакетов сохранить инвентаризацию, локальные wrappers и custom Frida:

```sh
dpkg-query -W > "$REBRO_CASE/evidence/packages-before.tsv"
python3 -m pip freeze > "$REBRO_CASE/evidence/pip-before.txt"
```

`pip freeze` может содержать URL приватных зависимостей; не публиковать автоматически.
Отдельно сохранить реальный trusted baseline и файлы, необходимые для восстановления
сервиса/extension/bridge. Не копировать все 24 GB кейсов для этого шага.

После сохранения baseline обновить индексы и **посмотреть план**:

```sh
pkg update
apt-get --simulate install aapt apksigner ripgrep jq sqlite tmux coreutils file binutils
```

Затем применить проверенный набор:

```sh
pkg install aapt apksigner ripgrep jq sqlite tmux coreutils file binutils
```

Не фиксировать версии из этой заметки как номер, гарантированно доступный зеркалу.
Не отключать проверки подписей apt и не добавлять случайный репозиторий ради zipalign.
Termux — rolling environment: длительные частичные обновления могут ломать зависимости.
Если план требует обновления Python/LLVM/JDK или ABI-зависимостей custom Frida,
выделить это в обслуживание среды, с проверкой и rollback; не решать глобальной заморозкой apt.

Проверка после установки: `apksigner version`, `zipalign` (usage), `aapt2 version`,
`rg --version`, `jq --version`, `sqlite3 --version`; затем hashes и Frida smoke.
У zipalign нет -h в проверенном варианте. Вызов без аргументов может вернуть 2 и
показать usage; распознанная справка подтверждает flags, не успешное выравнивание.
Реальная проверка — `zipalign -c -P 16 -v 4` на конкретном APK после align.
При установленном пакете и отсутствующем файле сначала `dpkg -V aapt` и проверка PATH;
переустановка пакета — только после выяснения, что локальных модификаций в нём нет.

## Необязательные дополнения

- `strace`: полезен для лабораторного собственного процесса; ptrace и SELinux могут
  ограничивать доступ. Не присоединять несколько трассировщиков одновременно.
- `tcpdump`/Wireshark offload: сетевой transport при конкретной гипотезе;
  отсутствие `/proc/net/tcp` у Termux не означает отсутствия соединений.
- `bundletool`: только для работы с AAB/APKS по необходимости. Не смешивать с простой
  процедурой извлечения уже установленных splits.
- Ghidra headless и MobSF: разумнее вынести на внешнюю машину, если задача появится.
  PRoot/glibc на телефоне не нужен для текущего минимального пайплайна.
- wget и 7z — удобство; для текущего набора curl/zip/unzip достаточно.

## Фоновая устойчивость

Для долгого контролируемого прогона можно использовать `termux-wake-lock`, затем
`termux-wake-unlock`. Это не гарантия против force-stop, freezer или LMKD.
Проверить Android battery policy для Termux и RikkaHub, уведомления foreground service,
`dumpsys thermalservice` и состояние зарядки. Не отключать thermal management.
Tmux переживает потерю терминала, но не перезагрузку и не убийство Termux UID.

Источники: [Termux apksigner](https://github.com/termux/termux-packages/blob/master/packages/apksigner/build.sh),
[aapt recipe](https://github.com/termux/termux-packages/blob/master/packages/aapt/build.sh),
[android-build-tools](https://github.com/termux/android-build-tools),
[package management](https://github.com/termux/termux-packages/wiki/Package-Management).
