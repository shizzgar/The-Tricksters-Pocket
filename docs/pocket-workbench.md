# Pocket workbench

The workbench provides durable nested agent conversations, a task and results dashboard, technical assistants, skill lifecycle controls, projects, memory history, work search and protected backups.

## Context indicators

The Send/Stop button keeps a 48 dp target with a context ring. Expanded statistics below the latest message show the same snapshot as a horizontal gauge, the latest measured prompt, remaining capacity, configured response reserve and compaction threshold. Unknown capacity uses a dashed indicator. Near-threshold and full states also have text labels.

The calculation uses the latest request measurement and an estimate for subsequent content, not cumulative billing. Compaction, a model change or a cropped history invalidates the old measurement. Current context remains approximate: providers differ in tool, media and system overhead. A user-selected token ceiling takes precedence over model metadata.

## Technical crew

| Assistant | Purpose | Default access |
| --- | --- | --- |
| DevBro | Implementation, debugging and reproducible development checks | Workspace files and Termux |
| OpsBro | Environments, deployment diagnosis and operational runbooks | Workspace, Termux and SSH |
| VerifyBro | Evidence inspection and independent result verification | Explicit read-only tool allowlist |

Existing assistants and user customizations are preserved. New profiles are seeded once. Readiness shows model, workspace, search and skill configuration and flags missing model, workspace and skill prerequisites. The avatars are transparent cel-shaded characters matching the app: an orange beaver with a laptop, a blue badger with a wrench/server, and a ruby snow leopard with a magnifier/checklist. Generated source images were optimized to 512 px WebP for the app and 1024 px WebP for documentation.

## Tasks and results

The dashboard exposes the task goal and acceptance criteria, child conversations and their states, current-chat stop and whole-tree stop. Goals and criteria are included in subsequent generation instructions. File results retain workspace, source, size and SHA-256; exports detect missing or changed files. Shell-created results can be explicitly registered. A chosen reviewer assistant can open a prepared read-only verification conversation with source and criteria.

Child runs retain tool scope, read-only mode, step budget and deadline across approvals and recovery. Completion delivery is durable and epoch-bound. After process loss, a child requires explicit continuation; uncertain external actions are not automatically replayed. Old child chats without a stored policy require a fresh dispatch.

Task token totals include descendants, response alternatives and auxiliary requests. A hard cap is checked before the next request/tool boundary. Already-running parallel requests can exceed it, and absent provider usage is reported as incomplete rather than invented.

## Skills, projects and data

Skill imports preview origin, version, requirements, files and differences before an explicit install/copy/update decision. Updates check revisions and retain a rollback version. Test history records the tested revision.

Projects group conversations, workspace, instructions, knowledge and files. Children and forks keep the effective project; a child inherits its parent’s working directory when both resolve to the same workspace. A fork clears a working directory if its new root no longer has the source’s effective workspace. Saved child execution identities survive result-cache pruning, so an old child remains available for follow-up.

Project references are available through `read_project_reference` in every backend, including Termux without an `/upload` mount. The tool rechecks current project membership for every read and accepts only linked managed uploads. Text mode supports UTF-8 and PDF/DOCX/PPTX/EPUB extraction for files up to 8 MiB, with pages of up to 16,000 UTF-16 code units (plus one when preserving a surrogate pair). Byte mode reads any format as base64 pages of at most 8 KiB. Cursor units are explicit in the tool description. Extraction uses the existing document parsers and does not provide OCR.

Project actions can unlink an individual reference or delete the project. Both dialogs state what is removed: project context and links, with chats, workspace contents and uploaded files kept on the device. Delete an uploaded copy separately in Files. Explicit project deletion, rebinding or workspace changes stop active chats only when their effective workspace changes and reset those working directories; unrelated edits preserve them. Editing an already-open project cannot silently restore a detached reference.

Memory exposes provenance, revisions, history, conflict detection and restore. Search supports project descendants, dates, tool/file/text kinds and pagination. Android sharing supports text, documents and multiple attachments into a new or existing chat.

Default backups remove structured saved credentials, including SSH secrets and request configuration. User text and files can still contain private information. Password-protected backups authenticate chunked AES-GCM data before restore publication; account sign-in sessions remain excluded. Device credentials use Android Keystore. Diagnostic exports use an explicit field allowlist and preview; full raw exports remain separate.

Workspace edits use revision checks and atomic saves. External shell writers are not controlled by the app mutex; observed changes produce conflicts. Physical-device Termux behavior still needs device-side verification.

## Validation

The additions from the September review are tracked in the [pocket.7 follow-up](review-follow-up-2026-09.md). Its full CI passed, and the ARM64 release was signed with the original ReBro key; APK v2/v3 signatures and the certificate were verified.

**Historical workbench release, 2.5.1-pocket.3:** package `excp.rikkahub.rebro`, version code 192. CI runs JVM tests, Android integration tests and screenshot fixtures, and builds an unsigned optimized ARM64 APK. Validation completed on commit `e181596ea16824796fd7776e5617c1fbc77f187b`: 982 JVM tests, 65 Python tests and 66 Android tests passed with no failures or skipped tests. Context gauges, skill rendering and task-result screens were visually inspected. The optimized ARM64 APK was signed locally with the existing ReBro key; the certificate and APK v2/v3 signatures were verified. See the [release manifest](releases/2.5.1-pocket.3.json) for hashes and build provenance.
