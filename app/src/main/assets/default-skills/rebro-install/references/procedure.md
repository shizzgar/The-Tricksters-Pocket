# Установка: подготовить, применить один раз, сверить фактическое состояние

## Область действия

Сценарий рассчитан на обычный лабораторный APK/full split set и явный Android user.
Package Manager использует общие code paths приложения для нескольких профилей:
`--user 0` не означает, что обновление кода не затронет другой профиль.
Системные пакеты, APEX, downgrade, key rotation и смена split topology исключены
из автоматического installer; это отдельные задачи, а не ошибки для обхода флагами.

Право установить/обновить берётся из текущего запроса. Если пользователь попросил
только патч или подпись, завершить на соответствующем артефакте. Если установка уже
разрешена, подготовленный ready plan можно выполнить без повторного вопроса.
Отдельное действие требуется для расширения области на другие профили, удаления
приложения или его данных. Скрипт не выполняет uninstall, pm clear, reboot и downgrade.

## Read-only план

```sh
python3 scripts/run_job.py --job-dir "$INSTALL_PLAN_JOB" --cwd "$SKILL_ROOT" \
  --heap-mib 512 --seconds 600 -- \
  python3 scripts/install_set.py plan --set "$SIGNED_SET" \
    --android-user 0 --out-dir "$INSTALL_PLAN_DIR"
```

Plan использует root-чтения и пишет evidence в новый каталог:

1. Повторно проверяет реальную подпись и alignment всех входных APK.
2. Перечисляет Android users и присутствие **только целевого package**.
3. Снимает реальные пути/hashes установленного набора во всех затронутых профилях.
4. Сохраняет исходные installed APK в Termux и читает их identity/certificates.
5. Сравнивает signer, version, split topology, system-app status и scope users.
6. Повторяет snapshot, чтобы заметить изменение во время подготовки.

Выход `install-plan.json`, его полный SHA-256 и status ready/blocked.
При certificate mismatch установка поверх существующего app блокируется; авторский
signing key нельзя заменить root. Не выполнять автоматическую деинсталляцию.
`--allow-other-users` применять только если влияние на перечисленные профили уже
входит в разрешённую задачу. Plan живёт 10 минут; после истечения подготовить заново.

## Применение

```sh
python3 scripts/install_set.py apply --plan "$INSTALL_PLAN" \
  --expected-plan-sha256 "$REVIEWED_PLAN_SHA256" --out-dir "$INSTALL_RESULT_DIR"
```

Запускать как managed job с внешним deadline, но не как daemon/root Python:
скрипт остаётся Termux UID, отдельные `su -c pm ...` выполняют PM-операции.
В apply нет JVM-команд; при обёртке run_job использовать resource light.

Перед первым изменением сверяются plan hash, APK hashes, boot ID и текущий snapshot.
Под plan записывается одноразовый execution journal. В одной PM session создаётся
полный набор; APK передаются в `install-write` **через stdin**, без chmod Termux home
и без обязательного root staging. Перед commit состояние `commit_requested`
сохраняется на диск. После `Success` читаются реальные установленные пути/hashes.
`commit_requested` — checkpoint перед вызовом PM, сам по себе он не доказывает,
что команда дошла до сервиса. Он требует осторожной сверки исхода.

Только совпадение полного installed set с intended set даёт install pass.
Если нужные байты уже установлены в нужном профиле на этапе plan/apply, результат
фиксируется как already_present; это не требует повторной установки.
Скрипт не запускает Activity и не доказывает функциональную работоспособность.

## Аварии и неизвестный исход

| Состояние | Что делать |
|---|---|
| Ошибка до create | исправить preflight, новый plan |
| Create ответ потерян | unknown; изучить лог и состояние сессий, не создавать повтор вслепую |
| Ошибка write, session ID известен | abandon только своей session; сохранить результат cleanup |
| Commit отправлен, ответа нет | unknown; сначала read-only reconcile |
| Success получен, hashes отличаются | unknown: возможна гонка/внешнее обновление; не признавать pass |
| SIGKILL/force-stop | читать execution journal; started/commit_requested не означают failed |

Сначала проверить прежний managed job: потеря клиента не означает, что процесс
остановился. Использовать сохранённые job ID, status, boot ID и stdout/stderr cursors;
не начинать сверку, пока старый исполнитель продолжает менять evidence. Не удалять lock.

```sh
python3 scripts/install_set.py reconcile --report "$INSTALL_REPORT" \
  --out "$RECONCILIATION_JSON"
```

В `--report` допустим также полный `execution.json`: в него сохраняется копия отчёта.
Сопоставить его с report_path и логами; два файла пишутся последовательно и после
обрыва могут отражать разные checkpoints. Три поля phase/session/status без plan,
его hash и target identity не заменяют полный journal.

Reconcile ничего не устанавливает: сравнивает нынешнее состояние с задуманным и
сохраняет новую запись. При полном совпадении можно закрыть install receipt этой
записью; при несовпадении остаётся unknown. Это не доказательство, что старая session
никогда не завершится. Сначала изучить её фактическое состояние; автоматического retry нет.
Скрипт не запрашивает состояние PM session: при mismatch остаётся отдельное
device-specific исследование её ID/принадлежности/терминального состояния, начиная
с `pm help` текущей сборки. Универсальной команды `install-status` toolkit не обещает.

CLI exit codes: ready/pass=0, blocked plan=3, unknown reconcile=4; исключение —
ненулевой выход с диагностикой. Всегда читать JSON status и конкретные evidence.
Если install attempt ещё открыт, закрыть его reconciliation evidence. Если receipt
уже создан как unknown, не переписывать: новый install attempt от того же passing
sign parent может только сверить старую операцию, без повторной установки.

Прежний план одноразовый, повторный apply запрещён. Для новой операции создать
новый plan после разрешения предыдущей неопределённости. Global install lock
координирует только этот toolkit; другие PM callers могут работать независимо.

## Восстановление и границы

Сохранённые APK — резервная копия кода, не пользовательских данных. Обновление может
мигрировать БД; первое открытие может менять remote state. До рискованного теста
подготовить собственный data backup и критерий восстановления либо отдельный lab target.
Успешный rollback APK не гарантирует совместимость новой БД со старой версией.

Parser PM намеренно строгий: неизвестный output/ошибка root/недоступный user приводит
к остановке. В Android/Samsung сборке сначала проверить `pm help` и acceptance на
своём lab APK. Реальный install этого комплекта в данной сессии не выполнялся.

Источники: [ADB/PM](https://developer.android.com/tools/adb#pm),
[AOSP PM implementation](https://github.com/aosp-mirror/platform_frameworks_base/blob/main/services/core/java/com/android/server/pm/PackageManagerShellCommand.java).
