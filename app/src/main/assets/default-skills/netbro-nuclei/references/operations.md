# Nuclei: воспроизводимый запуск

Проверить установленные -h/-version и текущие flags. Templates изменяются
независимо от движка: фиксировать tag/commit либо локальный hash выбранных файлов.

~~~sh
nuclei -version
nuclei -validate -t /absolute/case/templates/selected.yaml
~~~

Пример ограниченного run по подготовленному списку, с выбранным template:

~~~sh
nuclei -l /absolute/case/urls.txt \
  -t /absolute/case/templates/selected.yaml \
  -rl 5 -c 2 -bs 2 -timeout 10 -retries 1 \
  -ni -dr -duc -jsonl -omit-raw -omit-template \
  -o /absolute/case/run/findings.jsonl
~~~

Проверить наличие каждого флага в установленной версии.
-rl ограничивает запросы/сек, -c — параллельные templates, -bs — targets
на template; они не взаимозаменяемы. Общий deadline задаётся также в Termux job.
-ni отключает Interactsh/OAST, -dr — redirects, -duc — проверку обновлений.
Это не универсальная песочница для содержимого template: читать сам шаблон.
Если задаче нужны callbacks/redirects, выбрать конкретное разрешённое поведение,
отметить изменение и возможные внешние запросы в case.

Не использовать -ai для обычного локального run: это внешний сервис.
Не включать -code, -headless или DAST по умолчанию.
Не считать отсутствие findings эквивалентом «уязвимостей нет»: часть шаблонов
может быть пропущена, upstream requirements не выполнены или requests не дошли.

JSONL с -omit-raw/-omit-template уменьшает лишний контекст, но extractors и URL
всё ещё могут содержать секреты. Локальный summarize.py не печатает raw HTTP
и извлечённые значения, а query/userinfo убирает из адресного preview.

## Источники

- [Установка](https://docs.projectdiscovery.io/opensource/nuclei/install)
- [Запуск и flags](https://docs.projectdiscovery.io/tools/nuclei/running)
- [Репозиторий шаблонов](https://github.com/projectdiscovery/nuclei-templates)
