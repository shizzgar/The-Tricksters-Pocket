# Валидация Rebro 2.3

Локальные проверки этого релиза выполняются на host Linux/Python 3.12, не на телефоне.
Реальный root, Android Package Manager, apksigner и Frida endpoint здесь не вызывались.
Релиз опирается на **67 Python tests** (55 kit + 12 из пользовательского Frida Pack)
и **65 JavaScript contract checks** пака. Их исходные scopes сохраняются. Отдельно
проверены ZIP limits, checksums, links, CLI help и JavaScript syntax.
Точные результаты записаны в validation-results.json внешнего дистрибутива.

Collector проверен на ограничение вывода/времени, quoting root arguments, исключение
сырого bridge/loader source, scan bounds, отсутствие следования symlink и выпуск
самодостаточного отчёта. Девять checks версии 2.2 покрывают широкий поиск среди больших
build trees, раздельные лимиты, false loader candidates, reference paths, zipalign usage
и focused mode. Adapter проверен на внешний config/output, pin mismatch,
запрет observed-only baseline, offline native build и отказ от перезаписи config.
Пять новых checks 2.3 проверяют same-Script экспорт frida_java_bridge_default в Node VM,
сохранение source bytes, отказ на неправильный hash/Compiler bundle/ESM/отсутствующий
экспорт, выбор режима из manifest и точное соответствие pins исходному промпту.
Root и live injection в host tests не выполняются.

Дополнительно получен реальный пользовательский inventory ZIP (collector 2.1.0):
root reads, tool probes, Frida handshake и loaded/live executable hashes. Исправленный
collector 2.2.0 проверен на host fixtures. После получения системного промпта его
повторный запуск для paths/pins этого телефона больше не требуется.
Наличие inventory не означает выполненную установку APK или Java injection.
Тесты исходного Frida Pack сохраняют ранее проверенный результат: его 85 файлов не менялись.
В этой итерации адресно повторены 26 integration tests. 29 pipeline tests и vendor
checks сохранены по неизменённым исходникам; scope и результаты записаны в JSON.
Пользователь также передал текстовый отчёт об успешных native RPC smoke и Compiler
TS build; raw event logs этого прогона здесь не приложены. Fresh Java hook и новый
rebro-flat adapter на телефоне в нём не проверялись.

Проверяемые классы поведения: точный patch и отказ на stale preimage; защита путей;
целостность APK set; согласованность сертификатов; immutable outputs; parent receipt
и отказ на stale upstream; запрет повторного install; неизвестный исход commit и
read-only reconciliation. Signing/PM тестируются моделями инструментов: это проверка
управления процессом, не Android end-to-end и не криптографическая сертификация.

Для приёмки APK-маршрута на устройстве использовать собственный маленький lab APK;
эта последовательность не является обязательным стартом каждой задачи анализа:

1. Импорт каждого ZIP, включение skills, use_skill/sync и доступность scripts.
2. Doctor, реальные tool версии, full pins, новая RAM/disk проверка.
3. Acquisition source APK и проверка фактического full set/certificate.
4. No-op decode/build/sign/install и исходный функциональный контроль.
5. Малый ресурсный патч с exact plan; build/sign с тем же лабораторным ключом.
6. Plan/apply, hashes установленных APK; target и neighbor acceptance tests.
7. Отдельно controlled split APK set, отказ при неправильном сертификате,
   отказ второго JVM job и безопасная отмена собственного managed job.
8. После необходимой для выбранного маршрута приёмки перейти к реальному кейсу;
   новый Frida Java adapter отдельно проверяется
   существующим patched bridge, а не случайным новым upstream bundle.

Симуляция обрыва commit на реальном телефоне — отдельный лабораторный эксперимент;
сначала освоить read-only reconcile. Не тренировать восстановление на единственной
установке приложения с ценными данными.
