# The Trickster's Pocket — chat and workspace controls

## Tasks are explicit

Use the chat composer's **+ → Task** action to set a goal and optional acceptance criteria. Saving the task reveals its card above the composer. Ordinary chats and automatically registered files do not reveal the card. The task is stored with the conversation and survives reopening it.

The card opens Task and Results. Closing the task hides its card and stops including its brief in subsequent requests; it does not delete its files or chat history. Previously saved non-empty task briefs remain active after updating.

Result review opens an assistant selector. The chosen assistant is remembered for that task, and the review starts as a child chat with the task's goal, criteria and evidence. Review runs use a persisted read-only policy regardless of the chosen assistant's normal tool settings. There is no dependency on a particular built-in assistant.

## Termux workspace settings

Termux-backed workspaces expose the same execution limits and skill settings as the standalone Termux screen. These settings are shared across the Termux connection: editing either screen updates the same persisted values. The workspace's working directory remains specific to that workspace.

The controls include command and verification timeouts, output limits, package-manager compatibility, skill synchronization, and the app-wide turn/step limits. The screen distinguishes shared Termux settings from app-wide limits and provides a connection/permission troubleshooting link.

## Live status and identity

The overlay uses the active application theme and shows the current phase and a context-usage ring. Its context calculation is shared with the composer and message statistics, including compaction resets and unknown capacity. With parallel sessions, it shows the session with the largest context occupancy and the number of active sessions; it never adds unrelated context windows together.

The exact pocket-and-ears notification vector is reused in the sidebar, assistant selector, model fallback and generation indicator. The generation icon gently pulses between theme colors and follows the system animation setting.

Product text and current-project links use **The Trickster's Pocket**. Android application identity, existing data keys and compatibility paths are retained so this remains an update to the installed app. Upstream acknowledgements, third-party service identities and license notices remain accurate.

## Validation

The release workflow builds an optimized ARM64 APK and runs JVM, Python and Android emulator checks. Release provenance is recorded in `docs/releases/2.5.1-pocket.4.json` after the verified build and signing are complete. Physical-device Termux and OEM overlay behavior require a device check.
