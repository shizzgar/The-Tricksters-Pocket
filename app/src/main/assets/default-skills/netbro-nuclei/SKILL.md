---
name: netbro-nuclei
description: "Работать с Nuclei: выбрать и проверить templates под конкретную цель, ограничить rate/concurrency, сохранить JSONL, разобрать findings и подтвердить применимость результата."
---

# Nuclei

Проверить nuclei -version, help и версию/источник templates.
Для установки читать netbro-environment; по запуску —
[процедуры](references/operations.md).

1. Подготовить ограниченный список URL/hosts из scope. Не передавать все
   результаты разведки без проверки.
2. Выбрать конкретные template IDs/files или узкий набор для нужного сервиса.
   Severity — не оценка нагрузки или безопасности шаблона.
3. Проверить изменённые шаблоны через -validate; понять requests, redirects,
   matchers, extractors и внешние callbacks.
4. Задать rate limit, concurrency, timeout, retries и общий срок job.
5. Сохранить JSONL и диагностический лог отдельно. Тяжёлые headless/code/DAST
   режимы не включать как универсальный способ «проверить всё».
6. Проверить ошибки/skipped templates и полноту run. Коррелировать находки
   по template-id, matcher и endpoint; перепроверять только существенные
   результаты, не запускать весь набор ради одного отсутствующего файла.

Для агрегатов загрузить netbro-workflow и использовать summarize.py nuclei.
Исходные raw request/response и extracted secrets не выводить в чат без нужды.
