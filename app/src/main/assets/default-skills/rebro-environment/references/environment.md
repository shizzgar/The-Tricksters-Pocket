# Профиль и границы достоверности

Исходные наблюдения предоставлены пользователем: **22.09.2026 19:41 MSK
(16:41 UTC)**. Доступа к телефону из этой сессии нет. Прошлые PID, boot ID,
RAM/free space и порты нельзя считать актуальными без нового чтения.

| Компонент | Исходный профиль | Рабочее правило |
|---|---|---|
| Android | API 36, Enforcing, KernelSU | Root не гарантирует доступ к каждому объекту SELinux |
| ABI | arm64-v8a | Android/bionic arm64, а не произвольный Linux/glibc arm64 binary |
| RAM | MemAvailable ≈3.1 GB | 1536 MiB heap сначала; 2048 MiB только после нового замера |
| Swap | ~8 GB уже занято | Это не запас для второго JVM-процесса |
| Диск | /data ~27 GB free, cases ~24 GB | Нижний рабочий резерв 5 GiB — выбранная политика комплекта |
| Frida | custom core 17.18.0; Python 17.2.14-4+rebro.compiler1 | Сохранить известную комбинацию |
| Сервис | rebro-frida --serve, loopback:27044 | Не запускать второй экземпляр |
| Bridge | patched bridge-final.js | Не заменять upstream package незаметно |
| Compiler | build/watch доступны | Отдельно проверить build конкретного проекта |
| Питание | AC, charging | Проверять температуру, Doze/freezer и LMKD отдельно |

## Исправить инвентаризацию

В описании смешаны SM-S928B/e3q и «Galaxy S24 / Exynos 2400».
SM-S928B — вариант **Galaxy S24 Ultra**; Samsung описывает S24 Ultra со
Snapdragon 8 Gen 3 for Galaxy. Указанные восемь ядер и частоты также не стоит
использовать как доказательство Exynos. Подтвердить:

```sh
getprop ro.product.model
getprop ro.product.device
getprop ro.soc.manufacturer
getprop ro.soc.model
getprop ro.board.platform
getconf PAGESIZE
cat /proc/cpuinfo
```

Не назначать процессоры по предположенным A520/A720 до проверки topology.
Наличие PAC/BTI в CPU features не доказывает их использование конкретным ELF.
Android 16 сам по себе не определяет, 4 KiB или 16 KiB страницы у этого ядра.

Показания verifiedbootstate=green и ro.debuggable=0 не опровергают проверенный
KernelSU root. Для воспроизводимости сохранять и свойства, и фактический `id`/контекст.
`/usr/bin` в Termux обычно означает `$PREFIX/bin`, а не системный `/usr/bin`.

## Чего исходная проверка Frida не доказывает

Handshake + process listing подтверждают transport и перечисление в тот момент.
Для матрицы возможностей нужны отдельные строки: attach к выбранному PID,
загрузка script, сообщение native-ready, Java-ready, наблюдательный hook,
Compiler build, unload/detach. Spawn/gating — отдельная проверка на лабораторном APK.
Разные client/core версии допустимо сохранять как локально доказанный baseline,
но совместимость всех API из этого не следует.

Полные SHA-256 и абсолютные пути трёх компонентов в запросе отсутствуют.
В `config/pins.example.json` они намеренно `null`; сокращения с многоточием не pins.
Перенести значения из существующего доверенного baseline. PID никогда не pin.

Источники: [Samsung](https://www.samsung.com/levant/smartphones/galaxy-s24-ultra/),
[размеры страниц Android](https://developer.android.com/guide/practices/page-sizes).
