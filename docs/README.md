# Документация ReBro Agent

[Обзор проекта](../README.md) · [English overview](../README.en.md)

## Для начала

| Задача | Документ |
|---|---|
| Установить приложение, настроить модель и Termux | [Начало работы](getting-started.md) |
| Привязать каталог Termux, настроить профили Bro и перейти на release | [Termux workspaces и release](termux-workspaces-and-release.md) |
| Посмотреть новые экраны | [Галерея с пояснениями](screenshots.md) |
| Понять отличия форка | [Обзор изменений](../README.md#changes) и [CHANGELOG](../CHANGELOG.md) |
| Начать с комплектом ReBro | [Встроенный ассистент ReBro](agent-runtime/rebro-assistant.ru.md) |
| Работать с BBOT, Nmap, Nuclei и Legba | [Встроенный ассистент NetBro](agent-runtime/netbro-assistant.ru.md) |
| Понять роль skills, поиска и обновления промптов | [Источники и принятие решений Bro](agent-runtime/bro-skills-and-evidence.ru.md) |

[ReBro Blue и маскот](branding/README.md) · [Разбор трёх трасс](trace-review-2026-09-24.md) · [Обзор upstream от 24 сентября](upstream-review-2026-09-24.md)

## Возможности и настройки

| Область | Документ |
|---|---|
| Waterfall, Flow, инспектор, поиск и экспорт | [Trajectory и skills](agent-runtime/trajectory-and-termux-skills.ru.md) |
| Полный ZIP: запросы, reasoning, tools, подагенты и диагностика | [Экспорт всей трассы](trace-export.md) |
| Уточнить задачу во время её выполнения | [Сообщения между операциями агента](live-steering.md) |
| Файлы навыков, редактор, HEX, черновики и конфликты | [Мастерская навыка](agent-runtime/trajectory-and-termux-skills.ru.md#мастерская-навыка-и-инспектор-операций) |
| Полные пакеты в Termux, версии и ограничения | [Передача навыков](agent-runtime/trajectory-and-termux-skills.ru.md#навыки) |
| Редактирование навыков агентом, TTS/Whisper и доступ к tools | [Инструменты агента](agent-runtime/trajectory-and-termux-skills.ru.md#инструменты-агента-и-управление-навыками) |
| Длительные задачи, checkpoints, TPS и фоновые команды | [Автономные задачи и трассировка](agent-runtime/autonomous-tasks-and-trajectory.ru.md) |

## Для разработчиков

- [Build from source, tests and APK artifacts](building.md) — актуальное окружение сборки и команды.
- [Contributing](../CONTRIBUTING.md) — изменения, проверки и баг-репорты.
- [Происхождение скриншотов](media/screenshots/provenance.json) — коммит, CI, имена файлов и SHA-256.
- [Происхождение ReBro kit](../app/src/main/assets/assistant-presets/rebro/provenance.json) — исходный комплект и адаптации промпта.
- [Workflow CI](../.github/workflows/compaction-debug.yml) — точный набор проверок.

## История инженерных изменений

Эти документы фиксируют этапы разработки. Указанные в них первоначальные defaults и ограничения могли измениться; для текущего поведения используйте руководства выше и настройки приложения.

- [Первоначальная настройка compaction runtime](compaction-runtime.md).
- [Compaction и Terminal v2](compaction-and-terminal-v2.md).
- [Termux presentation и long-context inference](agent-runtime/2026-09-termux-runtime.md).
- [Обзор ограничений автономности](agent-runtime/autonomy-limits-review.ru.md).
- [Справка по compaction](references/context-auto-compaction-guide.md) и [pipeline генерации](references/chat-generation-pipeline.md).
