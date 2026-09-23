---
name: rebro-frida
description: "Выполнять Android runtime-анализ через pinned Frida: 38 модулей и 16 профилей Java/native/JNI/network/storage/crypto metadata; smoke, точные hooks, Compiler и cleanup. Использовать для наблюдения поведения и проверки гипотез."
---

# Runtime Frida

RikkaHub skill, release 2.3. Включить отдельно. Получить собственный skill_root
из успешного use_skill/termux_skill_sync; использовать как working_dir.
Читать [контракт](references/contract.md), [правила агента](references/agent-contract.md),
затем [процедуру](references/procedure.md). По работе tools/sync читать [harness](references/harness.md).
Уже прочитанные общие references той же версии не перечитывать без причины.
Пути других skills не вычислять. Выходы писать в case, исходники skill не изменять.

## Вход и результат

- Вход: Явный PID/package/user, доверенные pins и гипотеза.
- Выход: Session JSONL, summary и интерпретация покрытия.
- Frida — helper внутри analyze/verify attempt; stage frida не существует. Для отдельной диагностики сохранить самостоятельный evidence-каталог.
- Для перехода между этапами использовать caseflow receipt с explicit parent.
- Проверить фактическую доступность tools и смысл результата, а не только exit 0.
- Учитывать текущие разрешения пользователя; не вводить повторное подтверждение
  уже разрешённого действия и не расширять его на удаление/другие профили.

## Выполнение

1. Прочитать нужную процедуру полностью, проверить идентичность target и входов.
2. Проверить квитанцию предшествующего этапа и hashes. Для узкого запроса выбрать
   соответствующий маршрут, не требовать ненужные этапы.
3. Для этапа создать новый attempt, для helper использовать текущий; завести отдельный data/output каталог. Полезные shell-переменные
   подставлять явно в каждом tool call; environment может не сохраняться.
4. Выполнить скрипты с ограничениями ресурсов. Долгие команды — managed Termux job.
5. Проверить gates из процедуры, сохранить evidence и закончить receipt.
6. Отчитаться о доказанном результате и оставшейся границе проверки.

## Скрипты этого пакета

- `scripts/apkset.py`
- `scripts/caseflow.py`
- `scripts/compile_agent.py`
- `scripts/frida_pack.py`
- `scripts/frida_run.py`
- `scripts/pins.py`
- `scripts/run_job.py`

Вызов `python3 scripts/<name>.py --help` описывает фактический CLI.
caseflow фиксирует целостность и структуру, но не подтверждает вручную заявленное
поведение приложения. Для специализированных операций следовать процедуре.

## Ошибка и восстановление

Записать failed/blocked/unknown с конкретной причиной. Не превращать отсутствие
ответа в успех или в доказательство отсутствия side effect. Не перезапускать PM
commit после обрыва; перейти к reconcile. Новое изменение файлов — новая попытка.
Не заменять pinned Frida, ключ или ожидаемые hashes ради прохождения проверки.

## Дополнительные материалы

- [Источники](references/sources.md)
- [Проверки и ограничения релиза](references/validation.md)
- [frida](references/frida.md)
- [collection](references/collection.md)
- [troubleshooting](references/troubleshooting.md)
- [device-profile](references/device-profile.md)
- [system-prompt-integration](references/system-prompt-integration.md)
- [CATALOG](assets/rebro-frida-pack/docs/CATALOG.md) — читать по текущей задаче.
- [API17_CHEATSHEET](assets/rebro-frida-pack/docs/API17_CHEATSHEET.md) — читать по текущей задаче.
- [BRIDGE](assets/rebro-frida-pack/docs/BRIDGE.md) — читать по текущей задаче.
- [COMPATIBILITY](assets/rebro-frida-pack/docs/COMPATIBILITY.md) — читать по текущей задаче.
- [RECIPES](assets/rebro-frida-pack/docs/RECIPES.md) — читать по текущей задаче.
- [GITHUB_RESEARCH_RU](assets/rebro-frida-pack/docs/GITHUB_RESEARCH_RU.md) — читать по текущей задаче.
