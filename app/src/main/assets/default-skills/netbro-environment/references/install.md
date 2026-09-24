# Установка и совместимость

В APK включены skills и Python helpers, не сторонние сканеры, их wordlist,
шаблоны, ключи API или контейнеры. Установка программы — отдельная операция
в выбранном окружении; запуск NetBro сам её не выполняет.

| Инструмент | Проверка | Маршрут |
|---|---|---|
| Nmap | nmap --version | В native Termux есть официальный пакет: pkg install nmap |
| Nuclei | nuclei -version | Проверить пакет своего репозитория или собирать официальным Go toolchain; upstream: go install -v github.com/projectdiscovery/nuclei/v3/cmd/nuclei@VERSION |
| BBOT | bbot --version | Upstream поддерживает Linux, рекомендует pipx install bbot; native Android не заявлен поддерживаемой платформой |
| Legba | legba --version | Upstream предоставляет releases и cargo install legba; Android-совместимость бинарника и native-зависимостей проверять отдельно |

VERSION для Nuclei заменить выбранным опубликованным тегом или осознанно latest,
записать установленную версию. Проверить требования Go выбранного релиза.
Не запускать загруженный Linux ARM64 ELF как Android ELF без проверки ABI/loader.
Go/Rust/Python нужны в той среде, где выполняется соответствующая сборка.

Если BBOT/Legba не работают нативно, использовать уже доступный пользователю
Linux в proot/chroot или согласованный удалённый хост. Новый Linux rootfs,
контейнер, системный Python и рабочие окружения не заменять автоматически.
В proot root — не capability для raw sockets. Docker не считать доступным
только потому, что его команда есть в upstream README.

Проверять help и version внутри выбранной среды, запускать scan там же.
Для передачи case-путей использовать реальные mounts/SSH-пути; приватный
Termux skill_root не виден в другой среде автоматически. Для обычных
TCP connect scans и HTTP-запросов root обычно не требуется.

BBOT может устанавливать зависимости модулей при подготовке scan. Сначала
проверить выбор модулей/зависимостей; не делать install-all-deps по умолчанию.
API keys добавлять только для выбранных источников, из настроек пользователя,
не выводить конфигурацию с секретами в чат.

## Первичные источники

- [Nmap в Termux](https://github.com/termux/termux-packages/blob/master/packages/nmap/build.sh)
- [BBOT: установка](https://github.com/blacklanternsecurity/bbot/blob/stable/docs/index.md)
- [Nuclei: установка](https://docs.projectdiscovery.io/opensource/nuclei/install)
- [Legba: установка](https://legba.evilsocket.net/install/)
