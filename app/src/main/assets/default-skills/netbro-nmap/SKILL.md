---
name: netbro-nmap
description: "Работать с Nmap из Termux/Linux: инвентаризация хостов, TCP/UDP портов и сервисов, версия и NSE по задаче, ограничения привилегий, сохранение и разбор XML."
---

# Nmap

Проверить nmap --version и выбранную среду. Для установки использовать
netbro-environment; по scan options читать [процедуры](references/operations.md).

1. Взять явные хосты/сети, исключения и нужные порты из case.
2. Для обычного Termux начать с unprivileged TCP connect scan (-sT),
   разумного набора портов и конечного host timeout.
3. Отделять discovery от service detection. Если discovery недоступен, -Pn
   использовать для известного ограниченного списка; не превращать большую
   сеть в полный scan автоматически.
4. SYN/UDP/OS и некоторые NSE требуют дополнительных условий и нагрузки.
   Проверить реальную capability; proot root сам её не даёт.
5. Сохранить XML и текст через -oA, проверить exit и XML runstats.
6. Отчёт строить по host/port/state/service evidence; filtered и open|filtered
   не объявлять открытыми. Баннер версии сам по себе не доказывает уязвимость.

Загрузить netbro-workflow для summarize.py nmap. Helper показывает состояния
портов и limited preview; отсутствие портов в превью не скрывает общий счётчик.
