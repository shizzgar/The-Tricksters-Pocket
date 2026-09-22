# Подпись: идентичность ключа и полный набор APK

## Вход

Получить `candidate-set.json`, проверить hashes/identity и квитанцию build либо intake
для внешнего готового APK. При sign-only создать case этого режима, выполнить environment,
а затем `apkset.py --kind candidate` для всех переданных APK и оформить intake receipt.
Не требовать analysis/patch от пользователя, который уже предоставил нужный результат.
Включить `rebro-environment` и вызвать его процедуру: doctor находится в том пакете.
Для artifact-only case разрешён user=null; установка не подразумевается подписью.
Intake проверяет состав переданных файлов; полнота split dependencies из одного
badging не выводится. Зафиксировать происхождение/полноту отдельно.

Нужны keystore, alias, два приватных password files и **ожидаемый сертификат**.
Пароли не помещать в промпт, JSON, argv как `pass:...` и tool trace. Файлы mode 600/400,
по одной строке. Keystore и парольные файлы находятся вне skill и публикуемых артефактов.

Для нового лабораторного проекта выбрать один устойчивый лабораторный ключ; не
создавать новый ключ при каждой сборке. Это самостоятельная настройка signing identity.
Digest сертификата не равен hash keystore или hash APK. Получить его заранее из
выбранного доверенного сертификата/known-good APK, сохранить в case signing profile.
После обнаружения mismatch не переписывать expected digest на то, что вышло.

В `case/input/signing-profile.json` сохранить несекретный профиль:

```json
{
  "kind": "signing-profile",
  "alias": "lab",
  "expected_cert_sha256": "FULL_64_HEX_CERTIFICATE_SHA256"
}
```

Это шаблон, digest заменить доверенным полным значением. В sign attempt inputs
включить профиль, candidate manifest и keystore: snapshot фиксирует hash, не копирует
приватный ключ. Парольные файлы не включать в публикуемые outputs и case archives.

## Операция

```sh
python3 scripts/run_job.py --job-dir "$SIGN_JOB" --cwd "$SKILL_ROOT" \
  --heap-mib 512 --seconds 600 -- \
  python3 scripts/sign_set.py --set "$CANDIDATE_SET" \
    --keystore "$KEYSTORE" --alias "$KEY_ALIAS" \
    --ks-pass-file "$KS_PASS_FILE" --key-pass-file "$KEY_PASS_FILE" \
    --expected-cert-sha256 "$EXPECTED_CERT_SHA256" --out-dir "$SIGNED_DIR"
```

Вызов проходит для **каждого** APK: align → sign; затем для всего набора:
cryptographic verify → alignment check → metadata/split/certificate consistency.
Новый signed-set публикуется только после успешной проверки всех членов набора.
Частичная директория с APK и failed progress не является подписанным комплектом.

Политика этого toolkit: V2 и V3 включены, V1 определяет signer по manifest,
V4 отключён; incremental install не используется. Это обычный один signer без
ротации ключей. При специальных signing requirements открыть отдельную процедуру.
`zipalign -P 16` применяется до подписи; после подписи допустима только проверка
`zipalign -c`. ZIP alignment не исправляет ELF PT_LOAD alignment.

## Gate

Каждый APK: подпись действительно проверена apksigner, V2=true, certificate digest
ровно ожидаемый, ZIP alignment прошёл. Весь set: прежние package/version/split IDs,
одинаковый certificate set. Сохранить final APK hashes и полные verification logs.

После этого не менять ZIP-комментарий, ресурсы, manifest или alignment: новое
изменение требует нового candidate и sign. В full route это новый build от нужного
patch; в sign-only — новый intake от environment, затем sign от нового intake.
Не использовать старый receipt, если изменились его входные байты. Подписанный APK не гарантирует update
поверх официальной установки: сравнение installed signer — обязанность install.

## Отказы

| Ошибка | Действие |
|---|---|
| Нет native zipalign / `-P` | environment; не имитировать успех |
| Неверный alias/password | сверить профиль; не генерировать заменяющий ключ |
| Wrong certificate | остановить выпуск набора, выяснить signing identity |
| Только часть APK подписана | сохранить failed attempt, новый output для повтора |
| Verification warning | прочитать конкретное предупреждение; exit 0 не скрывает его смысл |
| Candidate изменился во время подписи | full: новый build → sign; sign-only: новый intake → sign; прежний не принимать |

Источники: [apksigner](https://developer.android.com/tools/apksigner),
[zipalign](https://developer.android.com/tools/zipalign).
