# Pocket workbench — 2.5.1-pocket.3

This update implements the application review: durable nested agent conversations, a task and results dashboard, technical assistants, skill lifecycle controls, projects, memory history, work search and protected backups.

## Context indicators

The Send/Stop button keeps a 48 dp target with a context ring. Expanded statistics below the latest message show the same snapshot as a horizontal gauge, the latest measured prompt, remaining capacity, configured response reserve and compaction threshold. Unknown capacity uses a dashed indicator. Near-threshold and full states also have text labels.

The calculation uses the latest request measurement and an estimate for subsequent content, not cumulative billing. Compaction, a model change or a cropped history invalidates the old measurement. Current context remains approximate: providers differ in tool, media and system overhead. A user-selected token ceiling takes precedence over model metadata.

## Technical crew

| Assistant | Purpose | Default access |
| --- | --- | --- |
| DevBro | Implementation, debugging and reproducible development checks | Workspace files and Termux |
| OpsBro | Environments, deployment diagnosis and operational runbooks | Workspace, Termux and SSH |
| VerifyBro | Evidence inspection and independent result verification | Explicit read-only tool allowlist |

Existing assistants and user customizations are preserved. New profiles are seeded once. Readiness checks explain missing model, workspace, search and skill configuration. The avatars are transparent cel-shaded characters matching the app: an orange beaver with a laptop, a blue badger with a wrench/server, and a ruby snow leopard with a magnifier/checklist. Generated source images were optimized to 512 px WebP for the app and 1024 px WebP for documentation.

## Tasks and results

The dashboard exposes the task goal and acceptance criteria, child conversations and their states, current-chat stop and whole-tree stop. Goals and criteria are included in subsequent generation instructions. File results retain workspace, source, size and SHA-256; exports detect missing or changed files. Shell-created results can be explicitly registered. VerifyBro can open a prepared verification conversation with source and criteria.

Child runs retain tool scope, read-only mode, step budget and deadline across approvals and recovery. Completion delivery is durable and epoch-bound. After process loss, a child requires explicit continuation; uncertain external actions are not automatically replayed. Old child chats without a stored policy require a fresh dispatch.

Task token totals include descendants, response alternatives and auxiliary requests. A hard cap is checked before the next request/tool boundary. Already-running parallel requests can exceed it, and absent provider usage is reported as incomplete rather than invented.

## Skills, projects and data

Skill imports preview origin, version, requirements, files and differences before an explicit install/copy/update decision. Updates check revisions and retain a rollback version. Test history records the tested revision.

Projects group conversations, workspace, instructions, knowledge and files; children inherit project membership. Memory exposes provenance, revisions, history, conflict detection and restore. Search supports project descendants, dates, tool/file/text kinds and pagination. Android sharing supports text, documents and multiple attachments into a new or existing chat.

Default backups remove structured saved credentials, including SSH secrets and request configuration. User text and files can still contain private information. Password-protected backups authenticate chunked AES-GCM data before restore publication; account sign-in sessions remain excluded. Device credentials use Android Keystore. Diagnostic exports use an explicit field allowlist and preview; full raw exports remain separate.

Workspace edits use revision checks and atomic saves. External shell writers are not controlled by the app mutex; observed changes produce conflicts. Physical-device Termux behavior still needs device-side verification.

## Release validation

The release package remains `excp.rikkahub.rebro`, version code 192. CI runs JVM tests, Android integration tests and screenshot fixtures, and builds an unsigned optimized ARM64 APK. Validation completed on commit `e181596ea16824796fd7776e5617c1fbc77f187b`: 982 JVM tests, 65 Python tests and 66 Android tests passed with no failures or skipped tests. Context gauges, skill rendering and task-result screens were visually inspected. The optimized ARM64 APK was signed locally with the existing ReBro key; the certificate and APK v2/v3 signatures were verified. See the [release manifest](releases/2.5.1-pocket.3.json) for hashes and build provenance.
