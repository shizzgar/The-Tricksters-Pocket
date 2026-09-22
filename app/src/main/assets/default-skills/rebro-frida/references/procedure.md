# Frida Pack в модульном Rebro kit

## Состав и входы

В `assets/rebro-frida-pack/` включены все 85 файлов пользовательского пака 1.0.0:
38 модулей, 16 профилей, controller/runtime, examples, документация, тесты и MIT license.
Файлы сохранены побайтно; исходный `SHA256SUMS` проверяется адаптером при запуске.
Это подключаемый слой внутри rebro-frida; analyze/verify используют его по необходимости.

В RikkaHub запускать через `scripts/frida_pack.py`. Он использует внешний config и
внешний каталог evidence, сохраняет неизменяемость skill_root и общий Frida lock.
Примеры прямого `rebro.py configure/run` в исходном README относятся к самостоятельной
копии пака: их defaults `local.json`/`runs` не подходят для синхронизированного skill.

Получить собственный skill_root через use_skill/sync. Прочитать контракт и baseline,
проверить task scope, process/package/user. PID берётся из актуального списка процессов.
Внешние profile/options можно передавать абсолютными путями; placeholders из examples
не являются реальными class names или адресами целевого приложения.

## Первый запуск

Для этой среды пользовательский системный промпт уже дал полный baseline и loader
recipe. Использовать `config/pins.rebro-known-good-20260922.json`; широкого поиска
bridge больше не требуется. Прочитать system-prompt-integration.md. Для иного
неизвестного окружения collector остаётся отдельным способом сбора наблюдений.

Создать внешний config из существующего доверенного manifest формата kit:

```sh
python3 scripts/frida_pack.py --config "$FRIDA_CONFIG" configure --pins "$BASELINE_PINS"
python3 scripts/frida_pack.py --config "$FRIDA_CONFIG" doctor --online
python3 scripts/frida_pack.py --config "$FRIDA_CONFIG" ps
```

Для первого использования поставленного baseline из текущего skill_root доступна
конкретная команда (указанный config должен ещё не существовать):

```sh
python3 -B scripts/frida_pack.py \
  --config "$HOME/rebro/config/frida-pack.2.3.json" configure \
  --pins config/pins.rebro-known-good-20260922.json --root-read
```

Manifest объявляет режим rebro-flat; configure выбирает его автоматически и проверяет
файлы/loaded binding. Команда не делает attach, не запускает сервис и не ставит пакеты.
Config хранит точную ссылку на immutable manifest этой версии навыка: сохранять её
Termux-копию, пока config используется, либо заранее перенести manifest в отдельный
приватный config-каталог и передать его явный путь через --pins. После обновления
не угадывать расположение прежнего skill_root и не перезаписывать config автоматически.

`FRIDA_CONFIG` — новый файл вне skill_root. Для server binary, доступного только root,
при configure добавить `--root-read`: это разрешает только scoped su sha256sum.
Manifest, service file, bridge и действительно загруженная Python extension должны
совпадать с уже выбранными pins. Повторное configure не перезаписывает файл.

Поддерживаются plain bridge формы auto/global/expression из upstream docs/BRIDGE.md
и rebro-flat для экспорта frida_java_bridge_default из пользовательского baseline.
Режим rebro-flat сохраняет bridge source в начале общего Script, затем публикует
alias globalThis.Java для модулей пака. Исходный bridge-файл не изменяется. Отсутствие
нужного экспорта приводит к явной JS-ошибке, не к подстановке другого bridge.
Контроллер использует QJS. Для compiler bundle/ESM/private loader сначала прочитать
его текущий контракт. Можно создать отдельный config с `configure --native-only` и
использовать native profiles, сохранив Java на существующем loader. Этот режим не
объявляет Java проверенной. Не ставить новый bridge ради прохождения проверки.

## Smoke и профиль

Полный smoke не обязателен перед каждым кейсом. Пользователь уже сообщил успешную
native spawn/attach/load/RPC/cleanup цепочку и Compiler build; новый Java adapter
нуждается в отдельной проверке на текущем lab target. CLI smoke запускает обе фазы;
для точечной проверки Java использовать run с выбранным Java модулем/профилем.

Долгие команды выполнять штатным managed Termux job из skill_root. Контроллер имеет
собственные duration/startup bounds и best-effort cleanup; внешний deadline должен
учитывать две фазы smoke и время detach, например 90 секунд для короткого smoke.
Контроллер выполняется с Termux UID; инъекцию обслуживает уже работающий root service.
CLI принимает PID и не заменяет внешнюю проверку `(boot_id, PID, start_ticks)`:
сверить актуальную identity/профиль и сохранить в case перед attach. Пример ниже
использует явно выбранный LAB_PID/TARGET_PID, не PID из старого отчёта.

Для первой Java-приёмки нового adapter профиль java-smoke проверяет только java_probe,
без установки hooks. Чтобы проверить также lifecycle hit выбранного lab приложения:

```sh
python3 -B scripts/frida_pack.py --config "$FRIDA_CONFIG" run \
  --pid "$LAB_PID" --agents java_probe,android_lifecycle \
  --duration 15 --startup-timeout 10 --output "$JAVA_EVIDENCE"
```

Подставить полученную текущую identity и новый evidence path. После готовности hook
выполнить разрешённый переход Activity и потребовать событие activity от этой сессии.
Отсутствие hit при отсутствии триггера не доказывает неисправность bridge. Сохранить
script errors, statuses, событие и cleanup summary; слово ready само по себе не gate.

```sh
python3 scripts/frida_pack.py --config "$FRIDA_CONFIG" smoke \
  --pid "$LAB_PID" --duration 5 --output "$SMOKE_EVIDENCE"
python3 scripts/frida_pack.py --config "$FRIDA_CONFIG" run \
  --pid "$TARGET_PID" --profile java-survey --duration 10 --output "$SURVEY_EVIDENCE"
```

Один профиль за сессию. Создаваемая вложенная session directory имеет уникальный ID.
Сохранить напечатанный абсолютный путь. Shared lock координирует только этот adapter;
прямой upstream controller и другие launchers его не соблюдают.

| Задача | Профиль / модуль |
|---|---|
| Проверить transport/runtime | doctor, native-smoke, java-smoke |
| Выбрать module/loader/class | native-survey, java-survey, exports |
| Наблюдать поздний код | modules, dynamic-code, jni |
| UI и IPC | app-observe, binder |
| Файлы/БД/preferences | storage, native-io |
| Сетевые endpoints | network, okhttp |
| Алгоритм/режим/размер crypto | crypto-metadata |
| Потоки | threads |
| Точный метод/символ | java_trace / native_trace + отдельный options JSON |
| Малый участок памяти | native_memory / native_scan с явным диапазоном |
| Краткое tracing одного потока | native_stalker_calls, experimental, отдельный lab target |

Capture metadata имеет конкретные границы: имена keys, пути и URL path могут попадать
в evidence. Native memory/scan — отдельные выбранные операции. Профили не включают
автоматическое получение ключей или изменение результатов бизнес-логики.

## Сборка агента и existing loader

Native bundle можно подготовить полностью offline:

```sh
python3 scripts/frida_pack.py build-native --profile native-survey --out "$BUNDLE_JS"
```

Для Java bundle — `--config "$FRIDA_CONFIG" build ...`; bridge попадает в тот же
Script context. Пример с точечным hook:

```sh
python3 scripts/frida_pack.py --config "$FRIDA_CONFIG" run \
  --pid "$TARGET_PID" --agents native_trace --options "$TRACE_OPTIONS" \
  --duration 10 --output "$TRACE_EVIDENCE"
```

Модули из пака можно перенести на существующий loader через native build/явно
подготовленный Java adapter. Непрозрачный Frida compiler bundle не конкатенировать
с plain JavaScript. Оригинальные минимальные probes и Compiler wrapper из kit также
сохранены; для них прочитать `references/frida.md`.

## Как принять результат

Из session directory читать session.json, summary.json и events.jsonl, затем:

```sh
python3 -B assets/rebro-frida-pack/tools/summarize.py "$SESSION_DIR"
```

`active` означает инициализацию. Нужны hook_installed/java_hooks и реальное целевое
hit-событие. Проверить reason, ready, cleanup_errors, unavailable, quota/truncation и
agent_counters.dropped. Exit 0 при quota/interrupt не подтверждает полное покрытие.
После instrumentation проверить нужный сценарий также без hooks.

Session directory и итоговую интерпретацию включить в outputs текущего analyze/verify
attempt. Не менять исходные логи после finish. Frida smoke не закрывает acceptance
статического APK-патча; verify требует своих реальных целевых и regression tests.

При timeout/обрыве сначала сверить managed job, процесс и сохранившийся session report.
Не перезапускать сервис/приложение автоматически. Cleanup после SIGKILL не гарантирован.
