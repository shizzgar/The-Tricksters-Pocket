# Проверки и критерии приёмки

Офлайн-проверки выполнены на Linux с Python 3.12 и Node; это не Termux/ART и не ваш patched Frida. Точная версия инструментов и результаты: tests/VALIDATION.json, tests/VALIDATION.txt.

Покрытие тестов:

- все 38 агентов: загрузка, init и cleanup на ограниченных mock API;
- все 16 профилей: совместная инициализация, лимит hooks, завершение;
- генерация bundle каждого профиля с поддержанными mock bridge-адаптерами;
- сохранение receiver, аргументов, результата и исходного Java-исключения;
- observer exceptions не заменяют исходный результат метода;
- снятие своей implementation по read-back token; отказ от перезаписи существующей реализации;
- квоты, отсутствие Java/API, поздняя загрузка ELF, границы памяти;
- IPv4 byte order, JNI table/row layout, Stalker cleanup;
- callback paths Java modules с представительными аргументами;
- pin mismatch, timeout cancellation, controller failures, resume-after-load-failure, unload/detach;
- синтаксис всех поставляемых Python/JS файлов.

Повторить:
```sh
python tools/verify.py --tests
```

Не увеличивайте тестовую выборку на устройстве без конкретной причины. Достаточная первая приёмка: doctor online, обе фазы smoke, короткие native-survey/java-survey, затем реальный вызов одного нужного хуку метода. Зафиксируйте runtime, хеши, целевой процесс и результаты. Только такие события подтверждают on-device coverage.

Тесты не проверяют качество патчей bridge, ART inline/compiled hooks, vendor SELinux rules, PAC/BTI/Stalker на конкретной прошивке, наличие классов стороннего приложения или все реальные overloads Android16. Ни один успешный mock тест не помечает агент как device-verified.
