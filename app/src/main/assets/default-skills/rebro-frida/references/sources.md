# Карта первоисточников

Проверено 22.09.2026. Это отобранный маршрут чтения и оригинальная адаптация,
а не зеркало всех чужих мануалов. Сторонние scripts не включены в пакет;
четыре templates написаны для этого комплекта. Upstream страницы/branches могут
изменяться: перед заимствованием кода фиксировать конкретный commit и license.

| Источник | Для чего читать | Как использовать агенту |
|---|---|---|
| [Frida Android](https://frida.re/docs/android/) | модель инструментирования Android | команды с USB адаптировать к существующему `-H 127.0.0.1:27044` |
| [Bridges](https://frida.re/docs/bridges/) | различие CLI и create_script, Compiler | перед каждой гипотезой «Java отсутствует» |
| [Frida 17 migration](https://frida.re/news/2025/05/17/frida-17-0-0-released/) | breaking API changes | при переносе старых community snippets |
| [JavaScript API](https://frida.re/docs/javascript-api/) | точные имена/сигнатуры API | читать конкретную секцию, сверять с capability probe |
| [Best practices](https://frida.re/docs/best-practices/) | lifetime native buffers и стоимость callback | перед native replacement и горячими hooks |
| [Messages](https://frida.re/docs/messages/) | send/recv и обработка ошибок | проектирование JSONL событий |
| [Stalker](https://frida.re/docs/stalker/) | tracing и его стоимость | только после выбора потока и окна |
| [Troubleshooting](https://frida.re/docs/troubleshooting/) | базовая классификация ошибок | переносить подход, не команды от другой платформы |
| [frida-python](https://github.com/frida/frida-python) | bindings и примеры клиента | проверка attach/load/detach/Compiler своего клиента |
| [frida-java-bridge](https://github.com/frida/frida-java-bridge) | реализация Android Java interop | сравнение с patched bridge без его замены |
| [frida-snippets](https://github.com/iddoeldor/frida-snippets) | большой набор community patterns | один пример → review → API migration → ограниченный тест |
| [Frida CodeShare](https://codeshare.frida.re/) | поиск готовых точечных идей | сохранить source/fingerprint; не слепой remote execution |
| [OWASP MASTG](https://mas.owasp.org/MASTG/) | методология мобильного анализа | преобразовывать технику в гипотезу и критерий проверки |
| [MASTG Deep Link runtime](https://mas.owasp.org/MASTG/techniques/android/MASTG-TECH-0173/) | пример узкой наблюдательной техники | пример структуры карточки, а не универсальный hook |
| [JADX](https://github.com/skylot/jadx) | CLI, ошибки декомпиляции, выбор режима | flags сверять с установленным `--help` |
| [Apktool CLI](https://apktool.org/docs/cli-parameters/) | decode/build/framework/aapt | внешняя native AAPT2 и case-local framework cache |
| [apksigner](https://developer.android.com/tools/apksigner) | signing schemes, cert verify, password files | перед подписью и диагностикой update mismatch |
| [zipalign](https://developer.android.com/tools/zipalign) | порядок операций, `-P 16` | align до sign, check после sign |
| [Android page sizes](https://developer.android.com/guide/practices/page-sizes) | ZIP vs ELF alignment | проверить реальные страницы и PT_LOAD |
| [Termux apksigner recipe](https://github.com/termux/termux-packages/blob/master/packages/apksigner/build.sh) | происхождение signer и JDK dependency | первый источник перед внешним JAR |
| [Termux aapt recipe](https://github.com/termux/termux-packages/blob/master/packages/aapt/build.sh) | сборка native build tools | сопоставить с локальным package manifest |
| [Termux android-build-tools](https://github.com/termux/android-build-tools) | aapt/aapt2/aidl/zipalign | источник native tooling, не desktop SDK binary |
| [Termux Package Management](https://github.com/termux/termux-packages/wiki/Package-Management) | обновления и зависимости | план обслуживания pinned среды |
| [LLVM readelf](https://llvm.org/docs/CommandGuide/llvm-readelf.html) | ELF metadata | конкретные headers/sections/symbols |
| [Official r2 Book](https://book.rada.re/) | native-analysis workflow | краткие команды под вопрос, не blind full analysis |
| [SQLite backup](https://www.sqlite.org/backup.html) | согласованный live backup | не копировать один открытый DB-файл |

## Проверенные материалы форка

PR: [shizzgar/rikkahub-agent#1](https://github.com/shizzgar/rikkahub-agent/pull/1).
Изученный head: `964714533885709526fd8072f44ccb1a496a0baa`; статус на момент чтения — open.

- [Пакеты навыков и Trajectory](https://github.com/shizzgar/rikkahub-agent/blob/964714533885709526fd8072f44ccb1a496a0baa/docs/agent-runtime/trajectory-and-termux-skills.ru.md).
- [Автономные задачи](https://github.com/shizzgar/rikkahub-agent/blob/964714533885709526fd8072f44ccb1a496a0baa/docs/agent-runtime/autonomous-tasks-and-trajectory.ru.md).
- [SkillsTools](https://github.com/shizzgar/rikkahub-agent/blob/964714533885709526fd8072f44ccb1a496a0baa/app/src/main/java/me/rerere/rikkahub/data/ai/tools/SkillsTools.kt).
- [ZIP importer](https://github.com/shizzgar/rikkahub-agent/blob/964714533885709526fd8072f44ccb1a496a0baa/app/src/main/java/me/rerere/rikkahub/skills/SkillZipImporter.kt).

Описание PR сообщает результаты CI; в этой работе CI заново не запускался.
Runtime-интеграция с физическим телефоном не выводится из чтения исходников.

## Минимальная карточка заимствованного snippet

```json
{
  "name": "observe-selected-method",
  "source_url": "https://...",
  "upstream_commit": null,
  "license": null,
  "source_sha256": null,
  "adapted_sha256": null,
  "bridge": "existing-patched",
  "requirements": ["exact class", "exact overload", "selected process"],
  "evidence_kind": "java-call",
  "duration_seconds": 15,
  "tested_on_device": false
}
```

`null` заполнять реальными данными при review. Hash фиксирует идентичность файла,
но сам по себе не доказывает безопасность или совместимость его поведения.
