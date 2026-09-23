# Проверка: артефакт, установленный код и поведение

Прочитать acceptance_tests из исходного analysis либо согласованного задания sign-only.
Перед каждым экспериментом сверить package/user/version, полный installed set,
boot ID и runtime PID. PID не постоянный идентификатор и не всегда main process.

## Три независимых результата

| Уровень | Проверка | Чего она не доказывает |
|---|---|---|
| Артефакт | подпись/alignment/metadata/hashes | что приложение установилось |
| Установка | код действительно установлен для нужного user | что нужная ветка работает |
| Функция | воспроизведённое действие и наблюдаемый результат | отсутствие всех возможных регрессий |

Для patch-сценария минимум: исходный контроль, целевое изменение, соседний
сценарий без ожидаемых изменений и отсутствие новой явной ошибки/краша.
Не добавлять постоянный Frida hook для проверки статического патча, если он сам
может создавать наблюдаемый эффект: сначала проверить без инструментации.

Если нужен runtime evidence, вызвать rebro-frida, проверить pins и начать с native
probe. Затем один нужный Java/native hook, ограниченный interval/log volume,
точное действие UI и cleanup. Сопоставить с тестом без hook. Чистый stdout, запуск
Activity и отсутствие crash buffer не равны выполненной acceptance-проверке.

## Evidence и итог

Записывать ожидаемое и фактическое поведение, команду/действие без секретов, время,
источник результата (screen/log/Frida/return value) и путь к исходным evidence-файлам.
Файлы evidence включить в outputs текущего verify attempt, чтобы caseflow фиксировал
их содержимое. В отчет не копировать все app data или логи чужих процессов.

```json
{
  "schema": 2,
  "kind": "verification-report",
  "status": "pass",
  "package": "com.example.lab",
  "android_user": 0,
  "tests": [
    {"id": "target-change", "required": true, "result": "pass",
     "expected": "Lab patched", "observed": "Lab patched",
     "evidence": "evidence/target-screen.png"},
    {"id": "neighbor-flow", "required": true, "result": "pass",
     "expected": "Обычный соседний экран", "observed": "Обычный соседний экран",
     "evidence": "evidence/neighbor-screen.png"}
  ]
}
```

Это пример структуры, **не готовый отчет о реальном тесте**. Заполнять только реально
наблюдёнными результатами. Required skipped/not_tested — не pass. Если исходного
контроля нет, отметить ограничение, не придумывать сравнение. Для sign-only без
запроса install/функционального теста завершить на подписанном артефакте и ясно указать
границы выполненной проверки.

## Диагностика по границе

Неверное поведение при корректной установке → analyze/patch с новым parent.
VerifyError/Resources.NotFound → patch/build. Certificate/install failure → sign/install.
Frida-only crash → проверить без инструментации до изменения APK.
Новый эксперимент сохраняет отдельные данные, прежние квитанции остаются как история.
Автоматический откат/удаление не являются частью verify.
