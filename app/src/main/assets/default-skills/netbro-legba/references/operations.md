# Legba: inputs и session

Опираться на установленную версию:

~~~sh
legba --version
legba --list-plugins
legba ssh --help
~~~

Пример для собственного лабораторного SSH на loopback, по уже подготовленному
конечному файлу username:password. Это форма команды, не default target:

~~~sh
legba ssh --target 127.0.0.1:2222 \
  --combinations /absolute/private/case/approved-pairs.txt \
  --concurrency 1 --rate-limit 1 --timeout 3000 --retries 1 \
  --session /absolute/private/case/run/session.json \
  --output /absolute/private/case/run/matches.jsonl --output-format jsonl
~~~

Сначала проверить наличие этих flags у установленной версии.
--timeout и --wait измеряются в миллисекундах; --rate-limit — запросы/сек.
--concurrency — число workers, не предел общего числа попыток.
Срок всей операции задавать также в termux_job_start.
--single-match подходит только когда пользователь просит закончить после
первого совпадения; иначе он уменьшает покрытие.

Явные --username/--password поддерживают literal, files и специальные выражения;
отсутствующие значения могут использовать генератор. Не печатать password
в команде, tool call, STATE или диагностическом логе. Перед чтением файла
проверить, что это существующий непустой ожидаемый файл, а не опечатка.
Файл combinations ограничивает пары; две wordlist могут создать произведение.

Session автоматически возобновляет прогресс и хранит options/результаты,
включая credentials. Не менять inputs между resume: сохранить hashes файлов.
Не запускать два процесса с одной session. После изменения scope или
credential set начать новый run/session. Не обещать, что локальная отмена
SSH-wrapper остановила удалённый процесс.

JSONL записи содержат plugin, target, data и partial.
partial=true не является полным подтверждением credentials. Даже full match
нужно интерпретировать по контракту plugin. Не считать ошибку соединения
неверным паролем и не превращать отсутствие matches в proof of coverage.

Для HTTP использовать конкретный http.* plugin из текущего --list-plugins,
изучить его body/auth/matcher параметры; не угадывать шаблон формы.
Для enumeration plugins задавать конечный payload set и scope отдельно.
Не включать REST/MCP server Legba для обычного запуска через Termux.

## Источники

- [CLI, inputs, session и output](https://legba.evilsocket.net/usage/)
- [Plugins HTTP](https://legba.evilsocket.net/plugins/http/)
- [Исходники и installation](https://github.com/evilsocket/legba/tree/dab974b910d52babc4767d06e093b7b86e259604)
