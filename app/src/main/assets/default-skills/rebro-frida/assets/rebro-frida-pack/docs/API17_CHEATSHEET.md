# Шпаргалка API, используемого этим паком

Это короткие собственные примеры для адаптации кода. Полное описание: [Frida JavaScript API](https://frida.re/docs/javascript-api/); [Python binding 17.2.14](https://github.com/frida/frida-python/blob/17.2.14/frida/core.py).

## Сборка и подключение

```python
# Использовать уже установленный binding.
import frida
device = frida.get_device_manager().add_remote_device("127.0.0.1:27044")
print(device.query_system_parameters())
print([(p.pid, p.name) for p in device.enumerate_processes()])
session = device.attach(actual_pid)
script = session.create_script(source_with_bridge_if_needed, runtime="qjs")
script.on("message", on_message)
script.load()
# При завершении:
script.unload()
session.detach()
```

В паке эти операции обёрнуты таймаутами, Frida.Cancellable, логами и finally. Для custom core отмена остаётся best effort.

## Нативный API без старых статических вызовов Module

```js
const m = Process.getModuleByName('libc.so');
const address = m.findExportByName('openat'); // null, если отсутствует
const exports = m.enumerateExports();
const all = Process.enumerateModules();
const globalAddress = Module.findGlobalExportByName('dlopen');
// В onEnter(args) функции openat строка пути находится в args[1].
```

Для строки пути используйте c.cstring(args[1]) внутри callbacks: helper проверяет readable range и ограничивает длину. Для собственной работы с памятью методы чтения вызываются у NativePointer.

Предпочитайте object API модуля и методы NativePointer. Не переносите без адаптации старые вызовы вида Module.findExportByName(moduleName, symbol) или Memory.readUtf8String(pointer).

## Модуль загружается позже

```js
const observer = Process.attachModuleObserver({
    onAdded(m) {
        if (m.name === 'libexample.so') {
            // Здесь допустима точечная установка вашего native hook.
        }
    }
});
// При остановке observer.detach()
```

В паке используйте c.onModule: он сохраняет cleanup и пишет hook_error. Наличие observer проверяется как capability, а не по сравнению строк версий.

## Java и overload

```js
// Включать только после подключения pinned bridge в этот же Script.
Rebro.module('my_agent', true, (o, c) => {
    c.hookJava(o.class_name, o.method,
        function (args, state) { state.begin = Date.now(); },
        function (args, result, state) {
            c.emit('result', {
                result: c.value(result),
                elapsed_ms: Date.now() - state.begin
            });
        },
        o.signature);
});
```

c.hookJava ставит implementation для выбранных overloads, вызывает original через сохранённый overload, возвращает его результат и повторно бросает его исключение. Before/after observers не меняют аргументы. Их собственные ошибки фиксируются отдельно. Не вызывайте this[method](...) внутри observer: это может повторно войти в hook. Уже установленная implementation не перезаписывается. Getter implementation может возвращать NativeCallback, отличный от исходной JS-функции; для cleanup runtime сохраняет прочитанный после установки token.

Для Java class names и signatures используйте фактические reflection/overload данные. Примеры: int, java.lang.String, [B. В java_trace параметр signature=[] означает overload без аргументов. Если параметр не задан — все overloads этого метода.

## ClassLoader, память, JNI, PAC

- java_loaders показывает loader; java_trace.loader_class выбирает ровно один loader данного класса через Java.ClassFactory.get. Если таких экземпляров несколько, требуется собственный точный selector.
- native_memory/native_scan разрешают ограниченный диапазон module + offset и проверяют границы; предварительно смотрите native_modules.
- jni_register использует JNI table slot 215 и stride 3 * pointerSize. См. [upstream env.js](https://github.com/frida/frida-java-bridge/blob/b38a5b647d3e6b72aa19bcf8eb5e41c550a0e622/lib/env.js). Он видит регистрации после установки hook.
- Не удаляйте PAC-биты произвольной маской. Начинайте с адресов, возвращённых runtime/ELF resolver. Наличие arm64/PAC/BTI не доказывает совместимость конкретного Stalker hook.
- Для горячих функций уменьшайте набор хуков и частоту событий. max_per_second сокращает выдачу, но не устраняет стоимость входа в hook.
