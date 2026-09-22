# Общий контракт Rebro 2.0

## Навык, скрипт и полномочие

Навык выбирает процедуру, входы, проверки и восстановление. Скрипт выполняет
механическую часть. Успех скрипта не доказывает правильность выбранного патча.
Разрешение инструмента/цели определяется текущей задачей пользователя; skill не
выдаёт новых полномочий. Если конкретная установка уже разрешена, повторное
подтверждение не нужно. Plan/apply разделяют подготовку и применение артефакта,
а не вводят обязательный дополнительный диалог.

Это комплект **для RikkaHub**, не установка персональных навыков в ChatGPT.
Вызвать `use_skill` с точным включённым именем. Использовать только его успешный
`skill_root`, при необходимости `termux_skill_sync`. Каждый пакет самодостаточен:
не обращаться к соседним каталогам skills и не угадывать версионные пути.
`working_dir=skill_root`; интерпретатор явно `python3`/`bash`. Результаты вне skill_root.
Для прямого запуска vendor tools использовать `python3 -B`, чтобы не создавать
Python bytecode внутри синхронизированного пакета. Основные kit wrappers это обеспечивают.

## Состояние и артефакты

`case.json` фиксирует case ID, package, Android user, режим и toolkit version.
В sign-only без установки user может быть null: не выбирать профиль 0 за пользователя.
Перед первой установкой привязать известный профиль через `caseflow bind-user`;
существующую привязку на другой профиль менять нельзя.
Не переносить case ID между приложениями/профилями. Основные варианты:

| Режим | Этапы |
|---|---|
| full | environment → acquire → analyze → patch → build → sign → install → verify |
| sign-only | environment → intake → sign → install → verify |

Остановиться на достигнутой цели: просьба подписать заканчивается на sign;
установка и функциональный запуск выполняются, когда входят в задачу.
`intake` выполняет rebro-sign: проверяет все предоставленные APK. Один base и
согласованные split IDs не доказывают замкнутость split dependencies.
Для installed source `--acquisition` связывает manifest со всеми копиями исходного
снимка; assembly/sign сохраняют его topology. Для внешнего набора полноту установить
по происхождению и manifest dependencies либо явно оставить непроверенной.
Frida — вспомогательный навык для analyze/verify, отдельный обязательный этап не нужен.

На каждый запуск создавать новый attempt через `caseflow.py begin`.
Передавать **явный parent receipt**, не выбирать «последний APK» по mtime.
`finish` создаёт receipt со статусом pass/failed/blocked/unknown, hashes входов,
выходов, evidence и родительской квитанции. `check` перепроверяет всю указанную цепочку.
При изменении входного APK, дерева патча или подписанного файла проверка перестаёт
проходить; старые результаты не «исправлять» заменой hash на текущий.

`caseflow` проверяет целостность и часть структуры, а не достоверность утверждения
агента о поведении приложения. Локальный владелец файлов может переписать JSON;
это защита от случайного рассогласования, не независимая аттестация.

| Артефакт | Значение |
|---|---|
| source-set.json | исходные APK, идентичность, подписи, hashes, provenance набора |
| analysis.json | цель патча, target split, наблюдения, критерии поведения |
| patch-plan.json | точные preimage hashes и операции |
| patch-report.json + tree/ | подтверждённый diff и новое дерево |
| candidate-set.json | набор после сборки/intake; оставшиеся splits могут иметь старую подпись |
| signed-set.json | все APK переподписаны одним ожидаемым ключом, проверки пройдены |
| install-plan.json | проверенное текущее состояние и конкретная установка |
| install-report.json | состояние PM session и сверка установленных байтов |
| verification.json | функциональные проверки с evidence |

Слова `candidate`/`signed` не определять по имени файла: использовать manifest,
проверить hashes и при критической границе повторить инструментальную проверку.
Комплект обслуживает обычные APK и наборы splits одного package/version.
APEX, key rotation lineage, SDK libraries, намеренная смена package/version/split
топологии и системные приложения требуют отдельного разобранного сценария.

## Команды квитанций

```sh
python3 scripts/caseflow.py init --case "$REBRO_CASE" \
  --package com.example.lab --android-user 0 --mode full
python3 scripts/caseflow.py begin --case "$REBRO_CASE" --stage patch \
  --parent "$ANALYSIS_RECEIPT" --input "$DECODED_TREE" --input "$PATCH_PLAN"
```

Сохранить возвращённый путь attempt.json. Если plan ссылается на payload files,
добавить их/каталог payloads как отдельные inputs. В manifest-сценариях сам manifest
содержит hashes APK, которые `check` также проверяет. Все переменные — заранее
определённые абсолютные пути из результатов инструментов; между tool calls shell
environment может не сохраняться.

```sh
python3 scripts/caseflow.py finish --attempt "$PATCH_ATTEMPT" --verdict pass \
  --evidence "$PATCH_OUTPUT/patch-report.json" --output "$PATCH_OUTPUT"
```

Не объявлять output всем каталогом attempt/case: он содержит изменяемые control files.
Результаты хранить в подкаталоге `data/`, квитанции — рядом. Не дописывать логи после
finish; для следующего эксперимента — новый файл/attempt. После аварии между записью
receipt и attempt readback сверить оба; наличие receipt исключает повторный finish.

## Ограничения ресурсов

Одна JVM-задача через общий `run_job.py`, default heap 1536 MiB и 2 CPU;
для signing достаточно начать с 512 MiB. JVM lock `$HOME/rebro/.locks/jvm.lock`
един для навыков. Он advisory, ручные JVM вне wrapper его не соблюдают.
`resource light` не использовать для команды, запускающей apksigner/JADX/Apktool.
Heap+reserve — preflight policy, не RSS limit и не защита от LMKD.
Таймаут, лимит лога и free-space checks обязательны для тяжёлых команд.

Длительное выполнение — штатный `termux_job_start`/`wait`/`cancel` с job ID и
двумя независимыми stdout/stderr cursors. Dispatch не завершение. После SIGKILL,
reboot, force-stop файл started/running может остаться: сверять boot ID и реальность.

## Frida baseline и root

Endpoint `127.0.0.1:27044`; сохранить существующие service/extension/patched bridge.
Не делать автоматический upgrade, restart, смену порта или permissive.
Хеши из исходного сообщения сокращены: пример manifest с null заполнить из
доверенного baseline, а не заново объявлять текущие байты эталоном.
Обычная работа — Termux UID; su только для конкретных Android reads/PM actions.

## Повтор и откат

- Повтор read-only проверки допустим. Новый patch/build/sign — новый каталог.
- Не повторять install после transport timeout; сначала reconcile.
- После failed/blocked менять причину/план, сохраняя прежнюю квитанцию.
- Rollback файлов: вернуться к исходному input, воспроизвести новую попытку.
- Rollback установленного APK не равен откату app data. Схема БД, Keystore и
  побочные действия первого запуска могут быть необратимы без подготовленного backup.
- Не выполнять uninstall/pm clear автоматически при mismatch или неудачном тесте.
