# Frida: рабочий playbook для агента

## Маршрут от transport к доказательству

Это диагностические ступени по необходимости, не обязательный полный стартовый
checklist. В релизе 2.3 paths/pins/flat recipe даны пользовательским промптом;
native RPC smoke и Compiler build дополнительно сообщены пользователем как pass.
Проверять новый Java adapter и нужный target contract, не переоткрывать ремонт среды.

1. Проверить pins и endpoint `127.0.0.1:27044`.
2. Перечислить процессы через этот endpoint; сверить PID, process name, package и
   Android user. Main process и `package:remote` — разные цели.
3. На выбранном тестовом PID загрузить `agents/native_probe.js` на 5 секунд.
4. Используя **существующий проверенный loader patched bridge**, собрать Java probe.
5. Проверить один Java/native hook и вызвать ровно одно соответствующее действие UI.
6. Сохранить JSONL, ошибки и результат detach. Сравнить с запуском без hook.

Сборка и attach изменяют разные части системы: Compiler build — локальная операция,
создание script и hooks происходят в target. Успех первой не доказывает вторую.
Данные `Frida.version` внутри native probe лучше отражают runtime загруженного агента,
чем версия Python bindings, но не заменяют hash custom server и историю сборки.

## Frida 17 и bridge

В Frida 17 bridges вынесены из GumJS. REPL/frida-trace из upstream frida-tools
поставляют bridges для совместимости; `session.create_script()` сам Java не добавляет.
Для **этой среды** использовать patched bridge, уже прошедший проверку.

Есть два разных сценария:

- Есть исходный ESM-проект patched bridge: импортировать его через существующий
  зафиксированный dependency/path, собрать агент имеющимся `frida.Compiler()`.
- Есть только `bridge-final.js`: для данного baseline известен flat UTF-8 script с
  экспортом `frida_java_bridge_default`. Adapter rebro-flat включает его в начало
  того же Script и задаёт globalThis.Java для модулей пака. Для другого baseline
  сначала выяснить loader/формат. Не дописывать текст к непрозрачному Compiler bundle.

Глобальные переменные разных `create_script()` не общие. Загрузка bridge отдельным
script не гарантирует наличие Java в следующем. В комплекте Java templates рассчитаны
на Java в **том же script**, а не на автоматическую подстановку.

Upstream-образец ESM entry, если именно выбранный dependency уже pinned:

```js
import Java from 'frida-java-bridge';
Java.perform(() => {
  const Clock = Java.use('android.os.SystemClock');
  send({kind: 'java-ready', uptimeMillis: Clock.uptimeMillis().toString()});
});
```

Этот import не предписывает ставить upstream bridge вместо patched.
`package-lock.json` и bridge source revision хранить вместе с проектом кейса.
`npm ci` допустим для отдельного проверенного проекта; `npm install latest` не baseline.

## Совместимость старых snippets

| Старый паттерн | Проверять/использовать в Frida 17 |
|---|---|
| Java существует в любом create_script | явное включение выбранного bridge |
| `Module.findExportByName('libx.so', 'f')` | `Process.getModuleByName('libx.so').findExportByName('f')` |
| поиск export во всех модулях | `Module.findGlobalExportByName('f')`, если глобальный поиск нужен |
| `Memory.readUtf8String(p)` | `p.readUtf8String(limit)` |
| `Memory.readU32(p)` | `p.readU32()` |
| постоянный polling загруженных модулей | проверить наличие `Process.attachModuleObserver` |
| произвольное `--no-pause` из старого гайда | читать `frida --help` установленной версии |

Не применять механическую замену ко всем `Memory.*`: часть API по-прежнему находится
на Memory. Для `get*` учитывать исключение, для `find*` — `null`.

## Практика hooks

Предпочитать наблюдение одного метода изменению поведения. Указывать точный overload,
class loader и процесс. В hook сохранять вызов оригинала и его исключения;
не перехватывать бизнес-исключение и не возвращать случайное значение.

Для поздно загружаемого кода определить конкретный loader и использовать
`Java.ClassFactory.get(loader)`. Не перечислять всю кучу и все методы на каждом
вызове. `Java.perform` может ожидать app loader; пустой лог за 5 секунд — ещё не crash.
UI-операции проводить на main thread, когда этого требует API приложения.

Ограничивать lifetime callback-объектов, wrapper-объектов и native allocations.
Память для строки нельзя считать живой после сборки JS-мусора: держать reference
столько, сколько native-код хранит pointer. Не писать строку большей длины поверх
исходного буфера. Это отдельные вопросы от прав страницы памяти.

## Производительность и наблюдаемость

В комплекте native hook считает вызовы и отправляет агрегат раз в секунду.
Не отправлять `send()` на каждый горячий вызов. Задать ограничение количества
строк/байтов, sampling и окно наблюдения. Аргументы, токены, сетевые body и ключи
не собирать без необходимости конкретного эксперимента.

Backtrace снимать по условию или первым N событиям, а не на каждой итерации.
Stalker включать для одного выбранного потока и короткого окна после более дешёвой
проверки Interceptor; анализировать данные отдельно. JNI tracer нужен, когда JNI
действительно является вопросом, а не стандартным первым действием.

`script.unload()` и `session.detach()` должны выполняться и при ошибке.
Не использовать `Interceptor.detachAll()` для удаления одного своего hook внутри
сложного объединённого script; хранить listener. На Android убийство клиента/UID
не заменяет проверку состояния target после аварии.

## Матрица capability

Заполнять отдельным результатом `pass / fail / not_tested`, target PID/package,
boot ID, хешами агента и baseline, временем и evidence:

| Возможность | Доказательство |
|---|---|
| endpoint/list | health JSON |
| native attach/load | native-ready |
| Java bridge | java-ready |
| Java interception | java-hook-installed + java-call после действия |
| native interception | hook-installed + call-count |
| Compiler build | bundle SHA-256 и отсутствие build exception |
| unload/detach | runner завершился, target продолжает работать |
| spawn/child gating | отдельный лабораторный сценарий; в этом комплекте не автоматизирован |

## Community: как превращать пример в инструмент

Для найденного snippet сохранить URL, автора, revision/дату, license, SHA-256,
ожидаемый API, runtime, target signature и выходную схему. Прочитать весь script
до исполнения. Удалить не относящиеся к задаче hooks и сторонние загрузки.
Заменить глобальное логирование минимальным событием, добавить таймаут и cleanup,
проверить на лабораторном target, затем включить в case scripts.

Не подавать агенту всю коллекцию frida-snippets как system prompt. Давать карточку:
«симптом → нужная API-глава → адаптированный script → как подтвердить результат».
CodeShare — каталог примеров, не пакет проверенных зависимостей для автозапуска.

Первоисточники: [bridges](https://frida.re/docs/bridges/),
[миграция 17.0](https://frida.re/news/2025/05/17/frida-17-0-0-released/),
[API](https://frida.re/docs/javascript-api/),
[best practices](https://frida.re/docs/best-practices/),
[Java bridge](https://github.com/frida/frida-java-bridge),
[Stalker](https://frida.re/docs/stalker/).
