# Источники и происхождение

Дата: 2026-09-22. Код контроллера, runtime, агентов и тестов написан для этого комплекта; внешние репозитории не скопированы и не установлены. Примеры API сверялись с первичными источниками:

- [frida-python 17.2.14 core.py](https://github.com/frida/frida-python/blob/17.2.14/frida/core.py): device/session/script, exports_sync, Cancellable.
- [Frida JavaScript API](https://frida.re/docs/javascript-api/): современный Process/Module/NativePointer/Java API и capability checks.
- [Frida bridges](https://frida.re/docs/bridges/): роль внешнего Java bridge.
- [Frida best practices](https://frida.re/docs/best-practices/): поведение hooks и работа с памятью.
- [frida-java-bridge env.js, commit b38a5b647d3e6b72aa19bcf8eb5e41c550a0e622](https://github.com/frida/frida-java-bridge/blob/b38a5b647d3e6b72aa19bcf8eb5e41c550a0e622/lib/env.js): JNI RegisterNatives slot/signature.
- [frida-java-bridge class-factory.js](https://github.com/frida/frida-java-bridge/blob/main/lib/class-factory.js): implementation getter возвращает replacement; setter создаёт callback. На дату чтения GitHub blob SHA: 68a1a3b4cefa5ad24e22ee190d1f091b5d1852c8 (SHA файла, не commit).
- [Medusa](https://github.com/Ch0pin/medusa): исследовался способ подключения отдельного bridge; код проекта не включён.
- [Предыдущее исследование](GITHUB_RESEARCH_RU.md): 59 upstream repos, ссылки, совместимость и очередность интеграции. Состояние этих проектов относится к дате исследования.

Выбор собственных маленьких модулей сделан из-за вашего частного baseline: зависимости больших обвязок могут приносить другой Java bridge или заменять Python binding. Для интеграции внешнего инструмента нужен отдельный adapter и проверка с pinned версиями. Ни наличие ссылки, ни статус upstream не означают включение или проверку проекта на вашем телефоне.
