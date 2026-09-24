---
name: netbro-legba
description: "Работать с Legba: проверка аутентификации и protocol enumeration на заданных сервисах, выбор plugin, конечные credential/payload inputs, контроль нагрузки, session и результаты без утечки паролей."
---

# Legba

Проверить legba --version и --list-plugins; прочитать help нужного plugin
и [процедуры](references/operations.md). Для установки — netbro-environment.

1. Выбрать конкретный сервис и разрешённую операцию. Проверка портов или
   баннера не означает задачу на подбор учётных данных.
2. Проверить существование входных файлов и конечный объём попыток.
   Пропущенные username/password могут включить генератор комбинаций;
   несуществующий путь может трактоваться как literal.
3. Явно задать необходимые inputs или конечный combinations file.
   Не запускать usernames×passwords автоматически, если нужны только
   заранее заданные пары.
4. Установить concurrency/rate, deadline и ограничения lockout из задачи.
   Начать с малого диагностического run; не увеличивать нагрузку при 429,
   блокировке, нестабильном сервисе или неоднозначном matcher.
5. Использовать уникальный session и output в приватном case. Долгую работу
   вести как managed Termux job; после обрыва проверить старый job прежде,
   чем возобновлять session.
6. Отделять partial от полного match и от подтверждённого успешного входа.
   Для HTTP проверить критерий успеха: одна только страница/статус 200
   может быть ответом неуспешного входа.

Пароли не передавать literal в tool arguments и не выводить в чат.
Файлы session/output могут содержать credentials даже если stdout quiet.
Для отчёта загрузить netbro-workflow и использовать summarize.py legba:
он выводит counts, plugin, target и partial, исключая credential data.
