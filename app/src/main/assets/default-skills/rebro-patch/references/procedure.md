# Патч: от объяснённого изменения к воспроизводимому дереву

## Вход

Прочитать analysis.json: задача, package/user, точный split, источник наблюдения,
что должно измениться и какие соседние сценарии должны сохраниться.
Нужны неизменённое decoded tree, parent receipt analyze, полный APK set и
текстовый/бинарный payload для планируемого изменения. Для начала создать attempt
с inputs decoded tree, plan и каждым payload. Патчировать минимальное число файлов.

Не править Java, восстановленную JADX, с ожиданием что Apktool её соберёт:
для этой цепочки рабочая модель — smali/resources/manifest/native assets.
Изменение Java исходного проекта и Gradle build — другой build route.

## План exact replacement

Снять tree hash: `python3 scripts/tree_hash.py "$DECODED_TREE"`.
Для изменяемого файла получить полный SHA-256. Составить patch-plan.json:

```json
{
  "schema": 2,
  "purpose": "Изменить диагностическую подпись экрана лабораторного APK",
  "expected_behavior": "На диагностическом экране видна новая подпись; остальные экраны прежние",
  "input_tree_sha256": "FULL_TREE_SHA256",
  "operations": [{
    "action": "replace_text",
    "path": "res/values/strings.xml",
    "before_sha256": "FULL_FILE_SHA256",
    "old": "<string name=\"diagnostic_label\">Lab</string>",
    "new": "<string name=\"diagnostic_label\">Lab patched</string>",
    "count": 1
  }]
}
```

Поддерживаются literal replace_text, replace_file, add_file и delete_file.
Для файлового payload указать `payload` и `payload_sha256`; для add_file preimage
должен отсутствовать и before_sha256=null. Для удаления нужен точный исходный hash.
Нет regex/глобального «замени везде» и shell-команд внутри плана. Это ограниченный
механизм применения уже принятого изменения, не автономный поиск места патча.

```sh
python3 scripts/patch_tree.py --tree "$DECODED_TREE" \
  --plan "$PATCH_PLAN" --out-dir "$PATCH_OUTPUT"
```

Скрипт сначала проверяет план, копирует исходники в новый `tree/`, применяет изменения,
сверяет input и пишет patch-report. На исходной версии план проходит, на других
байтах останавливается. Повтор на существующий output запрещён.

## Ревью по типу изменения

| Тип | Проверить до build |
|---|---|
| Smali | правильный class/method descriptor; именно нужный DEX/split; registers/locals; wide-register пары; type flow; try/catch границы; monitor balance; корректные invoke/move-result/return |
| Manifest | package/version и split identity сохранены; exported/permissions/SDK изменения соответствуют задаче; namespaced XML корректен |
| Resources | resource name/ID не потерян; qualifiers и переводы; ссылки между ресурсами; case-sensitive пути; ресурс не продублирован |
| Native `.so` | правильный ABI/build ID; файловое смещение сопоставлено PT_LOAD; calling convention, PAC/BTI, ELF alignment; новое содержимое проверено отдельно |
| Assets | кодировка, размеры, checksums и формат данных; не повреждены неизвестные бинарные поля |

Smali синтаксис подтвердит rebuild, но он не докажет отсутствие runtime VerifyError
или правильность ветки. До тяжёлого hook/patch сформулировать наблюдаемое изменение,
проверить отрицательный контроль и зафиксировать исходный результат.

## Выход и откат

Pass означает: exact preimage совпал, изменились только заявленные файлы, сохранён
hash результата и есть проверяемая гипотеза. Семантический успех — этап verify.
В failure отметить stale input, ambiguous match, syntax/format issue или неполную
гипотезу; не увеличивать count, чтобы «продавить» неожиданное совпадение.
Откат — исходное дерево остаётся неизменным; новый вариант делать новой попыткой.
