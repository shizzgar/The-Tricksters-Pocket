# Pocket.2 experience

Sub-agent dispatch creates a saved child conversation. Parent/run/tool-call identifiers live on the conversation, independent of the in-memory run registry. Tool cards open that conversation; the parent bar returns to the existing parent entry, preserving its input. Deleting a parent does not delete a child. Pre-existing sub-agent chats without recorded relationships cannot be reliably reattached and remain ordinary history entries.

Child chats use ordinary approvals and message steering. The orchestrator can send a follow-up with `subagent_send` and inspect the latest reply with `subagent_get`. An accepted message is not a completed task. Process death stops execution; saved chat history survives and the run ledger records the interruption. Recursive delegation remains disabled. No remote execution is resumed automatically.

Opening an active conversation preserves its live state. Background child initialization does not change the globally selected assistant. Chat controls follow the conversation’s assistant; per-chat model overrides remain editable.

Skill cards show Markdown instructions, highlighted source files, revision/location/size metadata, package entries, binary-file notices and actionable errors. Instructions that loaded successfully remain visible when Termux package synchronization fails. Long content has bounded pages and full-content copy. Raw input/output remains available. Previewing a script does not execute it.

Binding an assistant to a Termux workspace removes its separate Termux option. The settings screen also normalizes previously bound assistants and disables that switch with the workspace name. Unbinding does not silently re-enable the separate integration. Runtime workspace tools retain their existing permissions.

Room migration 32→33 adds the relationship columns and indexes. Foreign-backup reconciliation remains pinned to the exact exported v32 schema; normal Room migrations then advance the repaired file. Custom assistant instructions and avatars remain user-owned; only exact shipped versions are upgraded.

## Artwork

Generated with the built-in image-generation tool, then mechanically resized and padded for Android assets. ReBro’s personal anteater identity and ReBro Blue theme remain intact.

Generation briefs:
- ThinkBro: a thoughtful silver/navy owl in an amber mask, holding a notebook; polished 3D mascot in the crew’s existing visual style, transparent background.
- PocketBro: a silver/navy raccoon with a teal mask and a practical crossbody pouch; the same polished crew style, transparent background.
- Main mark: a clever silver/copper fox peeking out of a stitched navy pocket, teal accents and a small gold sparkle; recognizable at launcher size, transparent background.

Sources: `docs/branding/thinkbro.webp`, `pocketbro.webp`, `pocket-fox.webp`. Runtime avatars are 512px WebP. Adaptive launcher foregrounds have a safe inset in every density bucket; legacy and monochrome assets are included.

Validation results and release provenance will be recorded after CI completes. No private signing material belongs in the repository.
