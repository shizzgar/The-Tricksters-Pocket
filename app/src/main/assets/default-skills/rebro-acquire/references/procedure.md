# Acquisition: исходный набор как опора всей цепочки

Вход: package, Android user, case ID и свежий environment receipt. Определить,
является ли источник installed package, локальным monolithic APK или полным набором
splits. Для AAB/APKS сначала отдельное осмысленное извлечение под конфигурацию устройства;
не называть произвольный набор ZIP-файлов полным installed set.

```sh
python3 scripts/acquire_apks.py com.example.lab --android-user 0 --out-dir "$ACQUIRE_DIR"
```

Скрипт делает scoped root-read, создаёт копии с Termux ownership, сверяет размер/hash
с источником и повторно проверяет список путей. `acquisition.json` должен иметь complete.
Не выбирать base по порядковому номеру; список нужен целиком.
Процесс чтения не является атомарной транзакцией Package Manager: исключить внешнее
обновление app на время acquisition, а при обнаруженном drift повторить в новом каталоге.

Передать все полученные APK в inspection под JVM lock:

```sh
python3 scripts/run_job.py --job-dir "$INSPECT_JOB" --cwd "$SKILL_ROOT" \
  --heap-mib 512 --seconds 600 -- \
  python3 scripts/apkset.py --kind source --out "$SOURCE_SET" \
    --acquisition "$ACQUIRE_DIR/acquisition.json" \
    --logs "$INSPECT_LOGS" "$BASE_APK" "$SPLIT_APK"
```

Последние аргументы — точный список из acquisition, пример не предписывает два APK.
Inspection читает фактические package/version/split и подписи. Требует ровно один base,
уникальные split IDs и одинаковую signing identity. Исходники сохранить отдельно
от work/output, не re-sign их in-place. Finish evidence=source-set, outputs=копии,
manifest, acquisition и inspection logs.
`--acquisition` также требует точного совпадения всех записанных копий и hashes;
случайно пропущенный split блокирует source manifest. Это снимок установленного
состава, не перечень всех on-demand features, существующих в исходном AAB.

Локальный внешний unsigned APK для одной подписи относится к intake режима sign-only,
а не source: отсутствие подписи не нужно выдавать за успешную проверку оригинала.
Acquisition не копирует app data; backup БД/Keystore — отдельный конкретный сценарий.
