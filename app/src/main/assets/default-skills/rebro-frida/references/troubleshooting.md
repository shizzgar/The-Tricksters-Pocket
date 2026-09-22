# Диагностика: симптом → следующий эксперимент

| Симптом | Сначала проверить | Не делать автоматически |
|---|---|---|
| zipalign отсутствует | PATH, `dpkg-query -L aapt`, policy пакета | скачивать первый APK signing bundle |
| exec format error / missing linker | `file`, ELF interpreter, ABI/bionic | chmod 777 или запуск через su |
| Connection refused :27044 | точный endpoint, root socket read, известная service session | второй сервер на другом порту |
| Health OK, attach fail | актуальный PID/user, точную ошибку, crash/SELinux evidence | объявлять всё несовместимым по номеру версии |
| Java undefined | bridge в том же script, тип bundle/loader | переустановить frida-python |
| Java unavailable | native-only process, неверный PID, стадия запуска | считать bridge сломанным без native probe |
| Java class not found | split, class loader, процесс, obfuscation | dump всех методов/всей кучи |
| Hook installed, событий нет | вызвать действие, overload, loader, JIT/inlining как гипотеза | утверждать, что функция не используется |
| Module missing | lazy load, module path/name, split | бесконечный polling без deadline |
| JNI/native hook crash | address/prototype/ABI, crash log, build ID | отключить SELinux или патчить PAC наугад |
| JADX убит без Java exception | resource status, memory pressure, Android kill logs | увеличить heap до всей MemAvailable |
| Job dispatch успешен, результата нет | job ID/cursors, terminal state, artifact | заново запускать команду с side effects |
| Старый PID существует после reboot | boot ID + process identity | сигнал по устаревшему PID |
| APK verify OK, install fails | certificate, version, user, complete splits, manifest | uninstall / clear data |
| install fails с `.so` | ZIP и ELF alignment, ABI, page size | считать `zipalign` заменой перелинковки |
| Root read denied | фактический SELinux context и AVC | глобальный permissive |

## Минимальное evidence

Записывать время UTC, Android boot ID, target package/user/PID, действие UI,
точную команду без секретов, exit code, stderr и ожидаемый артефакт. Для Frida —
baseline manifest + agent hash + runtime version. Для APK — input/output hash,
cert digest и split manifest. Для crash — доступный crash buffer и tombstone
нужного процесса, не весь каталог чужих crash reports.

```sh
su -c 'logcat -b crash -d -t 200'
su -c 'dumpsys thermalservice'
su -c 'dumpsys battery'
cat /proc/pressure/memory
```

Сначала подобрать фильтр под свой процесс и время. Snapshot может содержать данные
других приложений; перед включением в отчёт выбрать относящиеся строки.
Если freezer неизвестен, прочитать актуальный cgroup path целевого PID и затем
его состояние. Не хранить путь с PID 26048 как постоянную настройку.

## Rollback эксперимента

Остановить собственный managed job, выгрузить собственный script/session, сохранить
частичный лог и отметить, что получилось проверить. Снять второй native probe на
лабораторном target. Не менять baseline и не перезапускать сервис только ради чистого лога.

## Место на диске

Разделять input/evidence и воспроизводимые work/output/cache. Сначала построить
отчёт `du -h -d 2 "$HOME/rebro/cases"`; не удалять ничего по одному имени каталога.
Кандидаты: повторные декомпиляции, старые временные build trees, завершённые логи.
Сохранять исходные APK, полный baseline, патчи, результаты эксперимента и manifest.
Архивация на том же разделе тоже требует места. Не пытаться автоматически упаковать
16 GB при остатке, не покрывающем новый архив и текущую работу.
