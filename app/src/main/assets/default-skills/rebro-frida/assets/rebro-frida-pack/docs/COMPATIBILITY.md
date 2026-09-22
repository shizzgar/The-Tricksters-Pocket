# Совместимость и пределы

| Слой | Предположение | Проверка |
|---|---|---|
| Устройство | Android16/API36, arm64 | native_probe + java_probe |
| Python | Тот же интерпретатор, который загружает patched binding | doctor: python/frida paths, hash |
| Transport | localhost:27044, уже работающий root service | doctor --online, ps |
| Java | Ваш pinned plain-script bridge поддерживает ART | configure + java-smoke |
| Native observer | API реально доступен в agent runtime | capability guards + события |
| Приложение | Класс/символ существует и нужный путь исполняется | inventory, hook_installed/java_hooks, hits |
| Сборка | Исходные JS + stdlib controller | SHA256SUMS, offline tests |

Разница client/server версий не исправляется автоматически. Frida.version в native_probe отражает наблюдаемый GumJS runtime и может отличаться от строки Python binding. Сам факт подключения не доказывает совместимость всех API.

**Приложение и покрытие.** Java survey перечисляет загруженные классы: max_items ограничивает вывод, но не стоимость полного enumerateLoadedClassesSync. Attach пропускает действия до установки hooks. Activity base hooks не видят override, который не вызывает super. ContextWrapper не охватывает каждый собственный Context. SharedPreferences hooks не охватывают DataStore. SQLite hooks не охватывают произвольные native/Room/SQLCipher пути. URL.openConnection не означает покрытие OkHttp/Cronet. OkHttp hook может не увидеть shaded/obfuscated/custom stack. Native network видит выбранные libc calls, не HTTP content/TLS plaintext и не все syscalls. Crypto metadata не включает ключи и не охватывает самостоятельную native crypto. Binder profile показывает transaction code/flags без decode Parcel. JNI profile видит будущие RegisterNatives вызовы; без них вывод может быть пустым.

**Ошибки и частичное покрытие.** agent_status=active — успешное завершение init. Если целевой ELF ещё не загружен, tracer может быть active при нуле hooks. unavailable у отдельных optional Java methods оставляет другие hooks работающими. hook_error / observer_error / jni_read_error / cleanup_error дают ненулевой итог. Всегда читать события, а не только exit code.

**Лимиты.** JSONL жёстко ограничивается контроллером, runtime дополнительно ограничивает сериализацию и частоту. При max_per_second часть событий пропускается, число dropped возвращается при штатной остановке. Объём session.json / summary.json и сумма всех runs не входят в лимит JSONL. Java inventory и некоторые symbol enumeration сначала получают список целиком. native_scan проверяет максимум 1 MiB за запуск; это не массовый dump. Скан и чтение памяти требуют, чтобы диапазон оставался читаемым во время операции.

**Очистка.** При штатном выходе/Ctrl-C контроллер вызывает stop, unload, detach; новые spawn, если PID уже известен, пытается resume даже при ошибке. После SIGKILL, падения интерпретатора, потери связи или зависшего custom server гарантировать cleanup нельзя. Cancellable/таймаут не являются атомарным откатом удалённого spawn/attach. Поэтому первая проверка — attach к уже живому sample app. Сообщения об ошибках очистки отражаются в summary.

**Влияние instrumentation.** Даже наблюдающий hook меняет timing и может вызвать реакцию приложения. Автоматических bypass/подмен ответов, ключевого материала, root hiding или изменений системной политики в профилях нет. Stalker остаётся experimental: один поток, целевая функция, 100–5000 ms окно. Использовать после обычного native hook на отдельном тестовом процессе.

**Локальная среда.** Контроллер не читает root-only /proc, не требует su и не управляет adbd. Существующий сервер обеспечивает injection. JVM/JADX/Apktool не нужны для запуска пака. Это отдельный слой динамического анализа, а не завершённый APK-signing toolchain.

