# Подключение существующего Java bridge

В архиве нет копии frida-java-bridge. Он использует ваш уже исправленный bridge-final.js, чтобы не подменять совместимость с ART нового Samsung обновлением зависимости.

Конкретный формат вашего файла не предоставлен. Поддержаны следующие **plain JavaScript** формы, проверенные тестами адаптеров:

| Содержимое файла | Режим |
|---|---|
| var bridge = …; объект имеет perform | auto |
| var Java = …; или globalThis.Java = … | auto / global |
| module.exports = …; либо module.exports.default = … | auto |
| var bridge = {default: …}; | auto |
| Единственное выражение, возвращающее bridge | expression |

`auto` и `global` используют один механизм: файл выполняется внутри IIFE, затем извлекается Java / bridge / CommonJS export. `global` — явная метка выбора, не отдельный JavaScript sandbox. Bridge и runtime исполняются **в одном Frida Script**. Java.available затем проверяется самим runtime.

Примеры конфигурации (замените путь):

```sh
python rebro.py configure --bridge /absolute/path/bridge-final.js --bridge-mode auto
python rebro.py configure --bridge /absolute/path/bridge-final.js --bridge-mode expression
```

Выберите одну подходящую команду; повторный configure создаёт новую фиксацию baseline и заменяет local.json. Режим expression подходит только для единственного JS-выражения, не файла с объявлениями.

**Не поддержаны автоматически:** сырой ESM с import/export, Frida compiler bundle с заголовком 📦, JS со сторонними require(), частный загрузочный протокол, конфигурация bridge через неизвестные внешние глобалы. Файл должен быть самодостаточным. Проверка import/export эвристическая; финальную совместимость определяет live smoke.

Если bridge использует приватный launcher:

1. Прочитать его локальный исходник/настройки, установить точный способ загрузки и используемый runtime.
2. Сохранить исходный pinned bridge неизменным.
3. Сделать отдельный self-contained plain JS adapter из той же локальной версии, если исходники и текущий toolchain это позволяют. Не скачивать новый bridge как «исправление».
4. Указать адаптер отдельным путём, зафиксировать его SHA-256 и проверить Java smoke.
5. Если адаптировать нельзя без изменения протокола, использовать существующий launcher и переносить модули согласно его контракту. В этом случае интеграция контроллера ещё не подтверждена.

Почему не загружать bridge отдельным session.create_script: globals из другого Script не становятся доступными агента. Компиляция ESM — отдельный режим сборки, а не простая конкатенация. Наличие frida.Compiler() полезно для дальнейшего расширения, но базовый пак не требует Compiler/npm/TypeScript.

Ссылки: [официальные bridges](https://frida.re/docs/bridges/), [исходники Java bridge](https://github.com/frida/frida-java-bridge), [Python API baseline](https://github.com/frida/frida-python/blob/17.2.14/frida/core.py).

