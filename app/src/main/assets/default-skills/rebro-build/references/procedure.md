# Сборка APK: отделить ошибку инструмента от ошибки патча

## До изменения

На первом кейсе с данным приложением/версией сделать **контрольную сборку без патча**
в отдельном каталоге. Сравнить decode → rebuild → sign → lab install с исходным
запуском. Это не обязательно на каждую повторную попытку с теми же входами/toolchain.
Если контрольная сборка не работает, сначала разобраться с ресурсами/framework,
подписью или упаковкой; ошибка patched build ещё ничего не говорит о гипотезе патча.
Если установка/запуск не входят в текущую задачу, ограничить контроль rebuild и
inspection, а функциональную совместимость контрольной сборки отметить непроверенной.

Входы build: прошедший patch report, неизменённое patched tree, source-set,
конкретные Apktool/AAPT2/JDK и vendor framework inputs. Проверить parent receipt.
Не использовать случайный `dist/*.apk` от предыдущего запуска.

## Рабочая копия

Apktool создаёт служебные файлы. Сделать новую копию patched tree в scratch каталоге
**текущего attempt**. Исходный `patch-report.output_tree` оставить неизменным: его
проверит caseflow при finish и следующий навык.
Framework cache также отдельный для case/попытки; vendor framework APK сохранить
с hash и fingerprint устройства. Не получать framework из случайного другого ROM.

```sh
python3 scripts/run_job.py --job-dir "$BUILD_JOB" --cwd "$REBRO_CASE" \
  --heap-mib 1536 --seconds 1200 -- \
  apktool b "$BUILD_WORK_TREE" --aapt "$PREFIX/bin/aapt2" -j 2 \
  -p "$BUILD_FRAMEWORK" -o "$REBUILT_APK"
```

На устройстве использовать native Termux AAPT2; флаги сверить с `apktool b --help`
установленной dirty-сборки. Не добавлять `--copy-original` для сокрытия проблем manifest.
Самостоятельный `.so` build должен завершиться до упаковки APK с собственным отчетом.

## Полный набор

```sh
python3 scripts/assemble_set.py --source-set "$SOURCE_SET" \
  --replace "base=$REBUILT_APK" --out-dir "$CANDIDATE_DIR"
```

Для feature split вместо `base` указать реальный split ID из source-set.
Каждый другой split копируется из проверенного исходного набора. Assembly повторно
проверяет фактические package/version/split identity через aapt2 и требует сохранения
исходной топологии. Количество APK само по себе не доказывает полноту: нужен original
manifest набора. Нет произвольного объединения splits в один APK.

`candidate-set.json` не означает «у всех файлов отсутствуют подписи»: неизменённые
splits пока могут иметь оригинальную подпись. Следующий sign переподпишет весь набор.
Намеренная смена versionCode/package/split topology требует отдельного плана миграции;
скрипт не должен автоматически соглашаться с неожиданным изменением.

## Gate

Build exit 0 + существующий новый APK + разбираемые metadata + полный согласованный
candidate-set + прежний hash patched tree. В квитанцию включить manifest, каталог APK,
stdout/stderr, версии toolchain и контроль изменённых компонентов. Не сравнивать
APK byte-for-byte как универсальный критерий no-op build: packaging может меняться;
важно отдельно проверить DEX/resources/manifest/native libs и поведение.

При resource ID/framework error — диагностировать подготовку; при smali parse error —
вернуться в patch; при OOM/LMKD — уменьшить потоки/объём, не наращивать heap вслепую.
Повтор — новый build attempt с тем же explicit parent или новый patch parent.

Источник CLI: [Apktool](https://apktool.org/docs/cli-parameters/).
