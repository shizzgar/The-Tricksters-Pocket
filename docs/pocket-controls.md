# The Trickster's Pocket — chat and workspace controls

## Tasks are explicit

Use the chat composer's **+ → Task and results** action to set a goal and optional acceptance criteria. Saving the task reveals its card above the composer. Ordinary chats and automatically registered files do not reveal the card. The task is stored with the conversation and survives reopening it.

The card opens Task and Results. Closing the task hides its card and stops including its brief in future turns; it does not interrupt a running turn or delete its files or chat history. Previously saved non-empty task briefs remain active after updating.

Result review opens an assistant selector. The chosen assistant is remembered for that task, and a child chat opens with a draft request containing the task's goal, criteria and evidence. Send that draft to start the review. Review runs use a persisted read-only policy regardless of the chosen assistant's normal tool settings. There is no dependency on a particular built-in assistant.

## Termux workspace settings

Termux-backed workspaces expose the same execution limits and skill settings as the standalone Termux screen. These settings are shared across the Termux connection: editing either screen updates the same persisted values. The workspace's working directory remains specific to that workspace.

The controls include command and verification timeouts, output limits, package-manager compatibility, skill synchronization, and the app-wide turn/step limits. The screen distinguishes shared Termux settings from app-wide limits and provides a connection/permission troubleshooting link.

## Live status and identity

The overlay requires Android's permission to draw over other apps. It uses the active application theme and shows the current phase and a context-usage ring. Its context calculation is shared with the composer and message statistics, including compaction resets and unknown capacity. With parallel sessions, it shows the session with the largest context occupancy and the number of active sessions; it never adds unrelated context windows together.

Drag the overlay to move it; tap the expanded pill to collapse it into a context ring, then tap the circle to expand it. Position and collapsed state survive reopening. Placement is kept inside system bars and cutouts and adapted on rotation. Accessibility actions also support moving and expanding it.

The search button always retains its magnifier. Model selection uses a recognized model or provider icon, then falls back to the pocket if neither is known or no model is selected.

The exact pocket-and-ears notification vector is reused in the sidebar, assistant selector, model fallback and generation indicator. With the app-style loading indicator enabled, the generation icon gently pulses between theme colors and follows the system animation setting.

Product text and current-project links use **The Trickster's Pocket**. Android application identity, existing data keys and compatibility paths are retained so this remains an update to the installed app. Upstream acknowledgements, third-party service identities and license notices remain accurate.

## Context and request measurements

The composer, statistics card and overlay use the same context snapshot. Its percentage estimates the next request against the model's context window. A manually selected compaction threshold is a separate setting; if the model window is unknown, the explicit token budget provides a labelled fallback. Unknown capacity is shown as unknown rather than zero.

The effective auto-compaction threshold is capped at the model window minus the configured output reserve. The same threshold drives the UI and runtime. If the output limit is unspecified, the reserve remains unknown. The threshold is checked after tool results when the provider reports input usage; an actual overflow also triggers recovery. Crossing a local estimate does not mean compaction has already started.

A provider measurement belongs to a specific request. New assistant output and tool results are added only when they were not already included in that input. Editing messages, rerunning an included tool, changing models or prompt settings, and compaction invalidate stale anchors. The local fallback is approximate: tokenization and hidden provider overhead cannot be measured exactly. Claude cycles that combine internal continuation requests retain their cumulative usage for totals but do not use that sum as context occupancy.

The statistics card keeps context, latest-request input/output and basic timing visible. **Details and connection** opens full diagnostics and whole-reply totals. Repeated input sent across tool rounds contributes to usage totals, not to repeated occupancy of the context window. Partial usage is marked; old messages without request-level measurements are labelled as saved usage. Elapsed reply time includes tools and pauses; generation speed uses the measured content interval.

## Termux durations

Tool cards show a live monotonic request timer and preserve its final duration. Managed commands also expose a separate job duration, because a background job can outlive the request that started it. Old timestamp-derived job values are marked approximate. Restored calls without recorded timing do not fabricate a zero or restart their clock. Cancelling a request does not claim that an external command was stopped.

## Validation

The pocket.5 changes await CI. The previous pocket.4 [CI for `f3b06be`](https://github.com/shizzgar/The-Tricksters-Pocket/actions/runs/36246686773) passed 1051 JVM, 65 Python and 72 Android tests and built the optimized ARM64 release. The emulator checks use the debug variant. The release APK was signed and its v2/v3 signatures verified against the permanent ReBro certificate. [Release provenance](releases/2.5.1-pocket.4.json) records the exact source, APK hash, test counts and screenshot-capture limits. Physical-device Termux and OEM overlay behavior require a device check.
