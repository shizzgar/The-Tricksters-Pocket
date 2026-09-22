# Использование системного промпта в Rebro kit 2.3

Принят `REBRO-SYSTEM-PROMPT-20260922(1).md`, SHA-256
`fafcecdbc2e9079ccb047e8b91c8a7171f693bfe289af54181084aaf6029145f`.
Исходник сохранён без изменений в source/reference-inputs. Пользователь сам адаптирует
системный промпт; kit переносит его конкретные контракты в профильные инструкции.

## Что закрылось

Sections 10–11 дают канонические пути и полные expected hashes сервиса, Python
extension и bridge, а также flat loader recipe. Создан
`config/pins.rebro-known-good-20260922.json` в environment/frida. Его trust основан
на явно предоставленном пользовательском baseline, не на автоматическом принятии
байтов из inventory. Перед использованием adapter сверяет реальные файлы.

Канонический bridge:

```text
/data/data/com.termux/files/home/rebro/cases/frida-repair/bridge-src-20260921/build/bridge-final.js
6be272a9e37d5c8e230a922e95a3ac3f803053ea11236fe0bff0e251b00429ad
```

Его экспорт **frida_java_bridge_default** не распознавался generic auto adapter
исходного Frida Pack. В kit добавлен режим **rebro-flat**: прочитать bytes, проверить
полный pin/формат, сохранить flat source в начале того же Script и после него
присвоить `globalThis.Java = frida_java_bridge_default` для модулей пака. Bridge на
диске и все 85 vendor files остаются исходными. Если expected export отсутствует,
агент получает явную ошибку; stock bridge автоматически не подставляется.

Flat assembly и Compiler — разные пути. Готовый Compiler bundle передавать
create_script неизменным, не дописывать к нему bridge, не снимать package header.
Встроенный pack controller использует QJS; новый adapter ещё не исполнялся на телефоне.

**Повторный широкий collector для путей/pins больше не нужен.** Старое требование
из релизов 2.1/2.2 закрыто этим промптом. При mismatch проверять конкретный артефакт,
не запускать поиск всех build trees и не делать reconfigure ради нового hash.

## Как учитывать приведённый отчёт агента

| Возможность | Что предоставлено | Что можно утверждать |
|---|---|---|
| Transport/list | Инвентаризация с выводом и более поздний текстовый отчёт | Endpoint отвечал; число процессов — снимок, не постоянная метрика |
| Native spawn/attach/load/RPC/cleanup | Отчёт: disposable sh, точное echo nonce, PID 29180, unload/detach/kill | Reported pass для этой цепочки; исходные event logs здесь не приложены |
| Compiler TS build | Отчёт об успешном bundle 199 bytes | Reported build pass; выполнение этого bundle в приведённом прогоне не показано |
| Java ready/hook и short freeze recovery | Указаны в baseline промпта, evidence path известен | Исторический reported pass в ограниченных условиях; свежего Java hook в smoke не было |
| Native Interceptor / Stalker | Перечислены как возможности | Этот sh/RPC smoke не проверял interception или Stalker |
| Новый kit adapter | Локальная проверка same-Script alias и ошибок | Phone integration ожидается; старый smoke не проверял новый код kit |
| APK align/sign/install | Наличие tools | Функциональная цепочка на lab APK ещё не предоставлена |

Не объявлять весь стек «неизвестным» из-за отсутствия повторного Java hook. Не
объявлять все Frida API проверенными по RPC ping. Сохранить reported pass и его
границы; повторять только проверку, нужную текущей задаче или интеграции adapter.

## Разделение системного ядра и навыков

| Содержание текущего промпта | Размещение в kit / будущая роль |
|---|---|
| §1–4: scope, честное evidence, UNKNOWN, state | Короткое системное ядро; agent-contract в skills |
| §5–6: jobs, PTY, quoting, privileges | harness + agent-contract; актуальные tool schemas |
| §7 и §10–11: hardware/pins/Compiler/bridge | environment profile + frida; в system оставить правило сохранения baseline |
| §8–9: acquisition/static/framework selection | acquire/analyze references |
| §12–17: identity, hooks, native, freezer, network/data | frida и соответствующие целевые playbooks, загружать по задаче |
| §18: patch/rebuild/sign/install/verify | Пять самостоятельных skills с output/evidence gates |
| §19–21: диагностика, cleanup, завершение | Короткие общие правила + case-specific state |

При будущей адаптации убрать фразу «No skill ... required» как описание архитектуры
модульного пайплайна. Простые вопросы и чтения при этом не требуют всей APK-цепочки.
Исторический harness commit заменить правилом живой схемы и текущей инструкцией
подключения; rg/jq/signer в inventory уже установлены. PIDs, RAM, listener state и
короткие результаты smoke хранить в case/evidence, не как вечные system constants.

На новом head форка навыки и skill-tools доступны только при подключённых skills;
skill management включается отдельно. В пакеты добавлено руководство harness.
Это контракт изученного head, не доказательство, что именно он установлен на телефоне.

## Следующий практический шаг

Импортировать свежий rebro-frida ZIP, подключить его к выбранному ассистенту и получить
skill_root. Следовать procedure.md: создать внешний config по поставленному baseline,
проверить файлы и loaded binding, затем на выбранном уже работающем lab target проверить
Java readiness и один нужный hook. Рестарт сервиса, новый native smoke, Compiler rebuild
или сбор полного inventory не нужны как автоматические предварительные этапы.
