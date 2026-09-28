# September 2026 review follow-up

Implementation changes and regression tests for all 26 findings from the review of `6d208a56963d0581b34f1e19ea48e319408a2cb9` are included in [PR #12](https://github.com/shizzgar/The-Tricksters-Pocket/pull/12). This page records the scope; the validation section distinguishes executed checks from device-specific limits.

| ID | Priority | Addressed finding | Regression tests |
|---|---|---|---|
| 01 | P1 | Удаление чата может удалить исходную фотографию пользователя | ChatAttachmentOwnershipTest; ConversationLifecycleInstrumentedTest |
| 02 | P1 | Ошибка SSH-скачивания уничтожает существующий файл назначения | SshTransferSafetyTest |
| 03 | P1 | Отмена удаления чата не восстанавливает режим проверки и связанные данные | ConversationLifecycleInstrumentedTest |
| 04 | P1 | Подтверждение одного tool может отменить весь смешанный набор вызовов | GenerationLoopResumeToolSelectionTest |
| 05 | P1 | Лимит токенов задачи недосчитывает запросы внутри одного ответа | TokenBudgetTrackerTest |
| 06 | P1 | Включение защиты Web UI на работающем сервере не закрывает его маршруты | ChatEnvironmentInstrumentedTest |
| 07 | P1 | HTML-интерфейс установленного skill получает доступ к чужим локальным файлам | SkillViewerPathsTest; SkillViewerInstrumentedTest |
| 08 | P2 | Ветки чата совместно используют вложения внутри результатов tools | ConversationLifecycleInstrumentedTest |
| 09 | P2 | Fork теряет проект, но сохраняет его рабочую директорию | ProjectContinuityInstrumentedTest |
| 10 | P2 | Подагент теряет общий CWD из-за сравнения настроек до наследования проекта | ProjectContinuityInstrumentedTest |
| 11 | P2 | После вытеснения из реестра сохранённый sub-chat невозможно продолжить | SubAgentDurabilityTest |
| 12 | P2 | Файлы проекта рекламируются агенту по недоступным в Termux путям | ProjectReferenceReaderTest; ProjectContinuityInstrumentedTest |
| 13 | P2 | Фоновая команда встроенного Linux Workspace теряет mounts и режим совместимости | WorkspaceBackgroundProcessesTest |
| 14 | P2 | Console Jobs и tools агента видят разные наборы Termux-заданий | test_termux_job_runtime.py (owner aliases) |
| 15 | P2 | SSH отклоняет успешное подключение через маршрут по умолчанию | SshTransferSafetyTest |
| 16 | P2 | Чтение обрезанного UTF-8 лога навсегда застревает на последней странице | test_termux_job_runtime.py (terminal UTF-8) |
| 17 | P2 | Поздний ответ проводника подменяет список другой области Workspace | WorkspaceFilesControllerTest |
| 18 | P2 | Отклонённый запуск задания меняет команду, по которой срабатывает hook | ToolHookRuntimeTest; ToolHookPresentationTest |
| 19 | P2 | Восстановление старого бэкапа оставляет более новую очередь hooks | PendingRestoreTest; ToolHookRuntimeTest |
| 20 | P2 | Отрицательная глубина lorebook ломает запрос даже у выключенной записи | LorebookImportValidationTest; PromptInjectionTransformerTest |
| 21 | P2 | Повтор сетевого запроса может выполнить tool из отброшенного ответа | GenerationHandlerTransportRetryTest; StreamTerminalContractTest |
| 22 | P2 | Обрезанный ответ может считаться завершённым и приниматься как compaction | GenerationFinishKindTest; ResponseApiStreamDecoderTest |
| 23 | P2 | Пустой SSE-ответ обходится без предусмотренного повтора | StreamTerminalContractTest |
| 24 | P2 | Лимит размера импортируемого skill применяется после загрузки всего ответа | SkillUrlImporterHttpTest |
| 25 | P2 | Перенаправление URL-импорта обходит запрет localhost | SkillUrlImporterHttpTest |
| 26 | P2 | Избранная модель остаётся доступной после отключения её провайдера | ModelFavoritesAvailabilityTest |

## Small usability additions

- **Projects:** delete a project or unlink one reference without deleting physical files. A Workspace change stops affected active chats and clears their CWD in both the session and database; scoped children retain their own environment.
- **Hooks:** discard one pending instruction from its tool details. The rule stays enabled. An already prepared request is cancelled before transport if it still contains that instruction.
- **Termux output:** view dates and sizes, select individual archives or those older than 30 days, and confirm deletion. This reclaims the existing 128 MiB archive quota; chat previews remain, while deleted full-output references stop resolving. Available from Termux settings and Termux-backed Workspace settings.
- **Chat environment:** use **+ → Chat environment** to see the effective workspace, inheritance source, CWD, enabled tools/skills and exclusions. This uses runtime resolution; it is configuration information, not a successful live connection probe.
- **First skill:** explicitly enabled creation/import works without an existing attached skill. Reading or changing existing packages still requires the existing access gates.
- **Termux jobs:** configured package-manager wrappers now apply consistently to direct job launches as well as captured commands.

## Behavioral details

Undo hides a history row until the snackbar resolves. If the process dies during that window, the intact chat remains. Forking fails visibly if an attachment cannot be copied. File cleanup only owns imported attachments and preserves references still used by another chat or project.

Task budgets count unique request IDs across tool steps and alternative answers. Legacy messages without request metrics retain one message-level fallback. Missing provider usage remains unknown. The context gauge still describes the current request; it is not a task-spend meter. `check_token_usage` includes request-named counters alongside compatibility aliases.

SSE close without a genuine terminal event becomes a transport error. A named tool-call start counts as meaningful output and blocks automatic replay of that partial response. Generation and compaction share finish-reason classification; incomplete compaction leaves the previous context active.

Saved child identity/policy survives pruning of repeated result payloads. Termux job namespaces use Workspace UUIDs and retain a trusted alias for older path-owned jobs, preserving live worker paths and operation receipts.

## Validation

[CI run 36438754795](https://github.com/shizzgar/The-Tricksters-Pocket/actions/runs/36438754795) passed on source `3061b9913771765c7720b2cea5bbc8152761674f` (tree `fa5bed811a45d5bf41b302c48210812fc7d462c5`). [PR #12](https://github.com/shizzgar/The-Tricksters-Pocket/pull/12) was merged as `dd1be97c44a024839a4984c5b03a7b70564b4342`.

- **1337 JVM + 72 Python + 100 Android tests**, no failures or skips. Android includes 99 main tests and one wide-layout test on API 35.
- All **41 required PNGs** decoded successfully. The new archive dialog and Russian chat-environment view, plus the repaired hook dry-run result, were manually inspected. These fixtures do not establish full physical-device navigation, keyboard or OEM behavior.
- The optimized ARM64 release was built separately. Downloaded artifact digests, reconstructed APK/signing-tool hashes, source commit, version/package, non-debuggable flag and bundled `icons/pocket.svg` were verified.
- The first integration run exposed an outdated first-skill access assertion and a hook preview initialization race. The access test now creates the first package and checks caller/package isolation; the preview publishes the loaded conversation only after scope resolution. The complete CI gate was rerun successfully.

[Release provenance](releases/2.5.1-pocket.7.json) records the signed and unsigned APK SHA-256 hashes, signing certificate, artifact IDs/digests and screenshot limits. The release was signed with the original ReBro key; APK v2/v3 signatures and the certificate were verified. Every ZIP entry in the signed APK matches the unsigned build byte for byte.

Physical-device Termux RUN_COMMAND/SSH network behavior and OEM overlays still require device checks. The emulator exercises the debug variant; the optimized release is built separately. No claim of a physical-device test is made.

## Compatibility

Release: **2.5.1-pocket.7**, code **196**, package **excp.rikkahub.rebro**. Existing user-edited assistant settings are preserved. The ARM64 APK is signed with the original ReBro certificate and can update an installed release using that certificate in place.
