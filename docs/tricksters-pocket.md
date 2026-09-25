# The Trickster's Pocket — 2.5.1-pocket.1

Termux-backed workspaces expose the normal `termux_*` tools. Their selected directory is the default for commands, durable jobs and new PTY sessions. `working_dir`, absolute paths and `../` may leave that directory. Termux HOME and global preferences are unchanged. Jobs/sessions belong to the workspace; the console and linked assistants can inspect the same jobs. Individual disabled tools stay disabled. Confirmation switches apply per workspace; ordinary Termux keeps its global settings. Built-in Linux workspaces continue to use `workspace_*`.

The app file browser still operates inside the linked root and protects atomic saves and revision conflicts. Unlinking a workspace does not delete its Termux directory.

## Crew

| Assistant | Role | Avatar |
| --- | --- | --- |
| PocketBro | Everyday writing, planning and practical tasks | Backpack |
| ThinkBro | Explanations, comparisons and learning | Puzzle |
| ReBro | Android analysis, modification and verification | Blue ninja anteater |
| NetBro | Network diagnostics and authorized security investigation | Green ninja spider |
| OrchBro | Select enabled subagent profiles, coordinate tasks, verify and combine results | Purple ninja octopus |

OrchBro reads the live profile roster in `subagent_dispatch`. Linked subagents retain their saved assistant settings and workspace; they do not automatically inherit the parent workspace. Simple tasks do not need delegation. Recursive subagent delegation remains blocked.

Existing profile IDs and custom settings are preserved. Only original generic names/prompts, exact shipped Bro prompts and the bundled NetBro avatar migrate. Descriptions are editable. PocketBro/ThinkBro subagent profiles seed once without restoring deleted ReBro/NetBro profiles.

## Update compatibility

Version code 190 keeps release package `excp.rikkahub.rebro`. Signing with the existing ReBro release key is required to update 2.5.1-rebro.4 in place. CI produces an unsigned optimized release. The delivered ARM64 APK has been signed locally with the previous ReBro key and its v2/v3 signatures verified. Its source commit, APK hash, certificate fingerprint and passing test counts are recorded in the [release manifest](releases/2.5.1-pocket.1.json). Do not generate a new key as a substitute. Device-side Termux integration still requires a physical-device check.

Repository rename target: `shizzgar/the-tricksters-pocket`. Links remain on the current repository until that rename is confirmed.

## Artwork

Built-in image generation produced NetBro and OrchBro using `docs/branding/rebro-mascot.png` as the style reference. Prompts specify a cute silver/navy spider with an emerald ninja eye mask, and a silver/navy octopus with a violet eye mask; isolated transparent mascot, no text or weapons. Optimized 512px avatars are bundled under `app/src/main/assets/branding/`.
