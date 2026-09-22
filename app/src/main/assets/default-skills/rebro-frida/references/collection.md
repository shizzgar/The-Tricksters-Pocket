# Сбор данных для окончательной настройки

**Для описанного телефона досбор путей/пинов закрыт в kit 2.3:** пользователь передал
системный промпт с тремя полными hashes и flat bridge recipe. Использовать поставленный
pins.rebro-known-good-20260922.json и процедуру rebro-frida. Следующие команды нужны
для новых неизвестных сред или конкретной нехватки evidence, не как обязательный шаг.

Самодостаточный `rebro_collect.py` требует только Python 3. Запустить из обычного
Termux тем же Python, который загружает рабочий frida binding:

```sh
python3 rebro_collect.py
```

При запуске из импортированного rebro-environment: `python3 scripts/rebro_collect.py`.
Root mode auto сначала проверяет KernelSU `su`, затем `sudo -n`; команды остаются
ограниченными чтениями. Сам Python-контроллер не запускать под root: иначе получится
чужой HOME/PATH и неверный Python baseline. При желании явно задать `--root su`.

По умолчанию включены loopback Frida handshake и число процессов, без attach/spawn.
Общий бюджет проверок — 240 секунд; вывод каждой команды ограничен. Инструменты
проверяются последовательно; JVM version commands получают heap 256 MiB.
Каталог поиска — HOME/rebro, глубина до 12, не более 25000 entries; обход в ширину,
до 1024 entries в одном каталоге. Исходники больших декомпиляций и node_modules
пропускаются. Лимиты для bridge/baseline/loader/tool разделены, поэтому множество
build helpers не вытесняет bridge. Причины неполноты отражены в limit_reasons.
Скрипт не сканирует всё /data как filesystem dump.

После уже полученной полной инвентаризации использовать **точечный досбор**:

```sh
python3 rebro_collect.py --focus frida
```

Этот режим пропускает hardware/tool/package inventory и увеличивает лимит поиска
до 100000 entries, сохраняя общий бюджет 240 секунд. Root reads, loaded binding,
loopback handshake, кандидаты bridge/loader/pins остаются. Установка зависимостей,
перезапуск сервиса и attach не выполняются. Флаг --root su допустим, но auto сам
выбирает доступный KernelSU. При неполном поиске можно задать несколько конкретных
--search-root; отсутствие найденного файла не доказывает его отсутствие на телефоне.

| Информация | Для чего нужна |
|---|---|
| Реальные model/SoC/ABI/page size/kernel | Проверить профиль устройства и native alignment |
| RAM/disk/CPU policies/thermal/cgroup | Настроить ресурсные ограничения и устойчивость jobs |
| Tool paths/versions/packages/local apt candidates | Закрыть zipalign/apksigner и сопоставить wrappers |
| Binding path/full hash + server parameters | Проверить используемый Python и transport |
| Root process candidates и hash `/proc/PID/exe` | Сопоставить файл baseline с работающим executable |
| Listener 27044 | Проверить endpoint, не публикуя прочие соединения |
| Bridge candidates/hash/format markers | Выбрать plain adapter либо сохранить private loader |
| Loader AST markers/runtime literals | Проверить QJS/V8 и способ сборки без копирования исходника |
| RikkaHub/Termux package versions | Сопоставить телефон с текущим контрактом синхронизации |

Если пути уже известны, передать их в том же запуске:

```sh
python3 rebro_collect.py \
  --bridge /absolute/path/bridge-final.js \
  --loader /absolute/path/existing-launcher.py \
  --baseline /absolute/path/trusted-pins.json
```

Флаги повторяемые; выбранные пути — реальные локальные файлы, не placeholders.
Без --baseline поиск извлекает только ожидаемые artifact paths/hashes из возможных
manifests; остальные поля JSON исключаются. Ссылки на bridge внутри разрешённых RE
roots дополнительно проверяются, даже если имя отличается от bridge-final.js;
относительные paths разрешаются от каталога manifest. Соседние Python/shell launchers
также проверяются. Для loader candidates сохраняются AST markers, без исходного текста.
Файлы вроде frida_version.py не считаются Java loader из-за одного имени или hash:
нужны признаки create_script и bridge. contract_verified остаётся false до live smoke.
Никакой кандидат не выбирается для исполнения и
не объявляется доверенным автоматически. Поддерживаемые чтением формы:
kit artifacts{id,path,sha256} и Frida Pack pins{binding,bridge,server_binary}.

Выход — новый приватный каталог `HOME/rebro/reports/inventory-...` и ZIP рядом с ним.
stdout выводит report_zip. ZIP содержит report.json, SUMMARY.ru.md,
observed-artifacts.json и SHA256SUMS. Сюда нужно передать получившийся ZIP.

Из истории команд, окружения, keystores, парольных файлов и данных приложений
информация не собирается. Bridge/loader source не копируется. Отчёт всё же содержит
системные пути, названия RE-каталогов, версии, PID и рабочие hashes — это технический
отчёт о данном устройстве, а не анонимный публичный telemetry payload.

Ошибки доступа, отсутствующий tool, timeout и неполный поиск остаются явными статусами;
это не повод ставить пакеты или менять SELinux во время сбора. --no-online пропускает
transport, --no-tools пропускает версии, --no-discover пропускает поиск. Ctrl-C сохраняет
частичный отчёт. Существующий output не перезаписывается.

Collector подтверждает наблюдаемые параметры, не происхождение baseline. Формат
bridge определяется эвристикой; private launcher может потребовать отдельного разбора.
Настоящие native/Java smoke, hook hit и align→sign→install на lab APK остаются
приёмкой на устройстве. Они намеренно не маскируются successful inventory.
