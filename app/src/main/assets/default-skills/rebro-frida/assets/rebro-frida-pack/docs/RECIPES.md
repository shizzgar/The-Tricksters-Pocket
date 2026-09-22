# Рецепты

Все команды выполняются из корня распакованного пакета. PID 12345, com.example.app, libexample.so и Example.calculate — placeholders. --config указывается **перед** подкомандой.

## Проверить другой конфиг без изменения baseline

```sh
python rebro.py --config /absolute/path/local.json doctor --online
python rebro.py --config /absolute/path/local.json ps
```

## Посмотреть систему модулей и экспорты

```sh
python rebro.py run --pid 12345 --profile native-survey --duration 5
python rebro.py run --pid 12345 --profile exports --duration 5
python rebro.py run --pid 12345 --profile modules --duration 20
```

Для другого ELF создайте options JSON:
```json
{"native_exports":{"module_pattern":"^libexample\\.so$","symbol_pattern":"JNI|decode|encode"}}
```

```sh
python rebro.py run --pid 12345 --agents native_exports --options /path/exports.options.json --duration 10
```

## Точечный native hook

```sh
python rebro.py run --pid 12345 --agents native_trace --options examples/native-trace.options.json --duration 10
```

Пример использует libc openat. Для своей библиотеки задайте module и ровно одно из symbol / hex offset. Возврат и четыре аргумента записываются как pointer-sized значения; их семантика зависит от реальной сигнатуры. Скрипт не угадывает типы и не читает буферы аргументов.

## Java-класс, методы и overloads

```sh
python rebro.py run --pid 12345 --profile java-survey --duration 10
```

Создайте `methods.options.json`:
```json
{"java_methods":{"class_name":"com.example.app.Example","pattern":"calculate"}}
```

```sh
python rebro.py run --pid 12345 --agents java_methods --options methods.options.json --duration 5
python rebro.py run --pid 12345 --agents java_trace --options examples/java-trace.options.json --duration 15
```

Второй пример сначала адаптировать под найденный класс! Для нестандартного loader добавить loader_class после java_loaders. Если совпало несколько экземпляров, generic tracer откажется выбирать случайный.

## Наблюдение сети, данных и криптоопераций

```sh
python rebro.py run --pid 12345 --profile network --duration 20
python rebro.py run --pid 12345 --profile storage --duration 20
python rebro.py run --pid 12345 --profile crypto-metadata --duration 20
```

Во время каждого прогона воспроизвести соответствующее действие в приложении. Профили запускаются последовательно. Для строковых значений/SQL сделайте собственный профиль с limits.capture_strings=true; значения будут усечены max_string. Это не включает считывание ключей/всей памяти.

## JNI и DEX

```sh
python rebro.py run --pid 12345 --profile jni --duration 20
python rebro.py run --pid 12345 --profile dynamic-code --duration 20
```

Attach не восстанавливает прошлые события. Если нужен старт приложения, --spawn com.example.app доступен явно; сначала проверить обычный attach. Spawn запускает процесс и сам по себе не гарантирует открытие Activity/воспроизведение UI. Пак возобновляет свой spawn после script.load, чтобы Java.perform смог дождаться application loader.

## Маленький диапазон памяти и pattern scan

```sh
python rebro.py run --pid 12345 --agents native_memory --options examples/memory.options.json --duration 3
python rebro.py run --pid 12345 --agents native_scan --options examples/scan.options.json --duration 3
```

Примеры смотрят начало libc. Отсутствие ELF magic match не означает повреждение: отображение/offset/permissions нужно проверить по актуальному процессу. Пак не превращает read/scans в dump всего адресного пространства.

## Свой профиль и готовый bundle

```sh
python rebro.py run --pid 12345 --profile examples/custom-profile.json --duration 10
python rebro.py build --profile examples/custom-profile.json --out "$TMPDIR/rebro-custom.js"
```

В custom profile сначала заменить Example.calculate. Параметры --options заменяют **весь объект опций указанного агента**, а не рекурсивно объединяют поля. --agents заменяет список агентов профиля; limits профиля сохраняются.

Готовый bundle можно передать существующему verified loader. Он не имеет внешнего Python watchdog: владелец loader должен вызвать rpc stop и unload/detach. Не запускайте несколько копий одного Java hook одновременно.

