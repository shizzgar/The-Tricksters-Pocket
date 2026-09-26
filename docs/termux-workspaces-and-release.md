# Termux workspaces and The Trickster's Pocket release builds

The application is **The Trickster's Pocket**, with the pocket-and-ears icon used throughout its current interface. Its release family is `2.5.1-pocket.N`. Linked Termux projects and assistant-backed subagent profiles were originally introduced in the historical `2.5.1-rebro.4` release (189); the current app retains that release package ID and signing key for updates in place.

## Link a real project directory

1. In **Settings → Termux**, finish the existing connection setup and grant `RUN_COMMAND`. Enable external app commands in Termux as described in the setup guide.
2. Install Python in Termux if needed: `pkg install python`. Create the project directory there if it does not exist.
3. Open **Extensions → Workspace → Add**, select **Termux**, and enter a name and absolute directory path. **Browse** lists child directories; **Parent folder** moves up. Creation verifies the directory through Termux before saving its canonical path.
4. Bind the workspace to an assistant from the chat's workspace selector. The assistant's existing skills, tools and approval preferences still determine its capabilities.

The file browser reads the original directory through the Termux service. It supports text editing, previews, import/export, folder creation, move/rename and deletion. Text previews are limited to 512 KiB; streamed file transfers are limited to 256 MiB. Transfers use 24 KiB chunks. Large imports can take time because each chunk crosses the Android service bridge.

The editor remembers the revision it opened. A save rejects an observed external change instead of replacing it. An accepted save uses a temporary file in the destination directory and an atomic replacement, retaining ordinary executable/permission bits. This is revision conflict detection, not a filesystem transaction with unrelated processes; avoid having two programs save the same file simultaneously.

File operations reject parent traversal, paths outside the linked root, and symbolic-link traversal. The selected root itself cannot be moved or deleted through these operations. Symbolic links appear in listings but cannot be opened; deleting a link removes the link only.

Deleting the workspace removes its database link and assistant references. It does **not** delete the Termux project or stop its jobs. Reattaching the same canonical directory reconnects to the same job history. Built-in Linux workspace deletion retains its existing behavior.

Termux workspaces use the in-app file browser and explicit import/export. They are not exposed as fake local directories through Android's system document provider. Backups include the saved link; the actual Termux project remains in Termux and needs its own backup.

## Commands, jobs and skills

The console starts every command in the linked directory, in a fresh Bash process. A standalone `cd` or `export` does not affect later commands; combine dependent commands in one script or specify an explicit directory. This is a command console, not an interactive PTY.

**Run** waits for a bounded command and shows its output and exit code. **Background job** uses the existing durable Termux supervisor. **Jobs** provides status, paged stdout/stderr, cancellation and log cleanup. Jobs are scoped to the canonical directory and app package; no command is automatically relaunched after an uncertain result. A foreground cancellation requests cancellation of its managed job. A command's full log remains subject to the supervisor's existing storage limits.

Workspace shell commands run with Termux's UID and permissions. They are not confined to the linked directory. Existing command approvals still apply to assistant tool calls.

ReBro and NetBro already enable Termux and their own skill packages. Read the selected skill first and use `termux_skill_sync` / `use_skill` to obtain its real `skill_root` in Termux. There is no `/skills` or `/upload` mount in this backend. Skill scripts and external scanners are not installed automatically. A disabled skill/tool is not re-enabled by creating a workspace.

## ReBro and NetBro as subagents

**Settings → Sub-agents** includes stable ReBro and NetBro profiles, seeded once. Each links to the corresponding saved assistant and therefore uses its prompt, skills, tools, search configuration and workspace. A missing assistant link fails clearly; it never silently adopts the parent's capabilities.

The profile's optional model selection and dispatch's explicit `model_id` retain their precedence. Additional profile instructions are prepended to the dispatched task. An unlinked profile keeps the previous parent-assistant behavior. A parent's current workspace directory is copied only when the child uses the same workspace.

Profiles remain editable, disableable and deletable. Upgrades preserve those choices and custom same-name profiles. The avatar migration replaces only the original NetBro satellite emoji; custom avatars and assistant settings remain intact.

## Release identity and signing

| Build | Application ID | Signing |
| --- | --- | --- |
| Existing debug line | `excp.rikkahub.debug` | Debug key from its build environment |
| The Trickster's Pocket release, including earlier ReBro releases | `excp.rikkahub.rebro` | Permanent owner-held ReBro key |

A signed Pocket release updates an already installed ReBro/Pocket release in place when the package ID and signing certificate match. No deletion, manual data transfer or new Termux grant is required for that update.

Debug is a separate application. To move from debug to release, export a backup from debug, install the signed release alongside it, and restore the backup there. Android grants permissions per application: reconnect Termux and grant the release app's `RUN_COMMAND` permission. Keep the existing installation while checking the restored chats, profiles and settings; there is no need to uninstall it to install or update the release.

The **Tricksters Pocket release APK** workflow (`rebro-release.yml`) runs the runtime/unit/Python/emulator gate and the optimized build in parallel. Its final `release` job publishes the verified artifact only after both succeed. An intermediate `candidate` artifact is retained for one day and is not an approved release. It checks the release package ID and rejects a debuggable manifest. Its `rebro-arm64-release-unsigned` artifact retains the historical CI identifier and is intentionally **not installable** until signed. For `2.5.1-pocket.4`, the APK inside is `tricksters-pocket-2.5.1-pocket.4-arm64-unsigned.apk`. The archive includes the exact commit, APK hashes, manifest metadata and the Android build-tools signing JAR. The verified archive is retained for 30 days.

The permanent private key is kept outside Git, public releases and CI artifacts. The owner must retain its private backup; generating a replacement key prevents future APKs from updating an installed release with the old signature. The APK's public certificate fingerprint can be published safely. The ReBro release key created for 2.5.1-rebro.4 has SHA-256 fingerprint `0FAC079E040D97CCE1786CEB21DC0855C0D09B2DF3222A43E71C46DD2C517805`.

To sign a verified artifact locally, configure `APKSIGNER_JAR`, `REBRO_KEYSTORE`, `REBRO_KEY_ALIAS`, `REBRO_STORE_PASSWORD` and `REBRO_KEY_PASSWORD` in a private environment, then run:

```bash
java -jar "$APKSIGNER_JAR" sign \
  --ks "$REBRO_KEYSTORE" --ks-key-alias "$REBRO_KEY_ALIAS" \
  --ks-pass env:REBRO_STORE_PASSWORD --key-pass env:REBRO_KEY_PASSWORD \
  --v1-signing-enabled false --v2-signing-enabled true \
  --v3-signing-enabled true --v4-signing-enabled false \
  --out signed.apk unsigned.apk
java -jar "$APKSIGNER_JAR" verify --verbose --print-certs signed.apk
sha256sum signed.apk
```

Verify the resulting v2/v3 signature and record its public certificate and APK hash. A standalone signing helper is also included in the private key backup. For a local Gradle build, the same `REBRO_*` signing variables are supported; an incomplete signing configuration fails explicitly. Supplying none produces an unsigned release and never falls back to a debug key.

Signed deliveries use this key with APK Signature Scheme v2 and v3. Check the [manifest for the exact delivered version](releases/) for its APK SHA-256, source commit, certificate fingerprint and build-report hash. The [2.5.1-rebro.4 release manifest](releases/2.5.1-rebro.4.json) is historical evidence for the original signed release; its APK hash does not describe a current Pocket APK.

## Validation boundary

Python tests exercise the file RPC on a Linux filesystem, including external edits, chunk retries, traversal, symlinks and executable permissions. Android emulator tests run the debug variant and cover database migration, branding, linked-profile rendering and the workspace creation UI. The optimized release is built separately, inspected and signed. These checks do not establish successful communication with the owner's installed Termux or prove that third-party scanner binaries work on their phone. Those require a device check after installation.


A short device check after installation: link a disposable Termux project, edit a text file in the app and confirm its contents from Termux; then modify the open file in Termux and confirm that the app asks to reload on save. Start and cancel a background command from the workspace console. Finally unlink the workspace and confirm that the directory remains in Termux. This verifies the actual Android service connection and device permission setup that the emulator tests cannot exercise.
