# BBOT: процедуры

~~~sh
bbot --version
bbot --help
bbot --list-presets
bbot --list-modules
bbot -p subdomain-enum --current-preset
~~~

Ориентир для согласованной доменной разведки; example.test заменить заданным
доменом, run-001 и путь — уникальным case run:

~~~sh
bbot -t example.test -p subdomain-enum -rf passive \
  -n run-001 -o /absolute/case/bbot -y
~~~

-y убирает CLI-подтверждение только для уже выбранной операции.
Фильтр passive относится к modules; не обещать отсутствие DNS, внешних API,
проверок зависимостей или иных подготовительных обращений.
Для строгой офлайн-задачи не запускать этот пример.
Один домен может включать поддомены; для точного host проверить --strict-scope.
Исключения задавать через -b по help текущей версии.

## Различия версий

| Вопрос | 2.x | 3.x |
|---|---|---|
| -s | silent | seeds; silent перенесён в -S |
| -w / --whitelist | отдельный scope | удалён; scope задаётся -t |
| Seeds | обычно -t | -s/--seeds; без него используются targets |
| Структурированный event | data может быть object | data_json для структурированных данных |
| Output | проверить установленный help | -o / --output-dir; короткий -o не угадывает имя файла |

Не копировать устаревшие --allow-deadly и названия heavy presets.
Проверять --current-preset или --dry-run до долгого запуска, но не считать dry-run
обещанием отсутствия setup/dependency effects. Не печатать full config с API keys.

BBOT сохраняет результаты в каталоге scan. Имена файлов и успешность записи
проверить по фактическому output. output.json — поток JSON objects по строкам.
Новое выполнение обычно получает новое имя: повтор старого имени может дописать
старые файлы. Не включать Slack/Discord/HTTP output modules для локального отчёта.

## Источники

- [CLI 3.x](https://github.com/blacklanternsecurity/bbot/blob/a6fb827bb144cdb85b52e142a4d6e14ed5f94b69/bbot/scanner/preset/args.py)
- [Миграция 2→3](https://github.com/blacklanternsecurity/bbot/blob/a6fb827bb144cdb85b52e142a4d6e14ed5f94b69/docs/migration/3.0_breaking_changes.md)
- [Форматы output](https://github.com/blacklanternsecurity/bbot/blob/a6fb827bb144cdb85b52e142a4d6e14ed5f94b69/docs/scanning/output.md)
