# Contributing

Thanks for helping improve this fork. Russian and English reports and pull requests are welcome.

## Scope

This repository develops the runtime, Trajectory, Termux integration, skill workspace and ReBro additions on top of RikkaHub and ExTV/RikkaHub Agent. Keep upstream attribution and third-party licenses. Changes should preserve saved conversations, assistant settings and user-edited skill packages.

## Report a bug

GitHub Issues is currently disabled for this repository. The [bug report](.github/ISSUE_TEMPLATE/bug-report.yml) and [feature request](.github/ISSUE_TEMPLATE/feature-request.yml) forms are ready; the repository owner can enable them with **Settings → General → Features → Issues**. Until then, use the checklist below when sharing a report with the maintainer; pull requests remain available.

Include the app version **and commit**, device/Android version, relevant model/provider, enabled tools and reproducible steps. For Termux issues, include whether RUN_COMMAND and skill transfer are configured. Distinguish an emulator result from a physical-device result.

Attach only the relevant log excerpt or screenshot. Trajectory exports can contain prompts, commands, file paths and tool results; remove credentials and private data before posting. Do not post private signing keys, API keys or unfiltered backups.

## Make a change

1. Branch from `master` and keep the change focused.
2. Follow existing Kotlin/Compose patterns and localized UI resources. Use the [build guide](docs/building.md) and the repository's Gradle wrapper.
3. Run checks that cover the behavior you changed. Explain what you verified and what you could not run. A documentation-only edit does not require rebuilding the app.
4. For a visible UI change, include a real capture and the reproduction steps. Use fixture data when sharing traces/screenshots.
5. Open a PR describing the problem, resulting behavior, compatibility impact and evidence. Keep model-generated text/code only if you have reviewed its claims and changes.

Runtime changes should account for cancellation, timeouts, retries, checkpoint ownership and concurrent writes. Skill changes should preserve package boundaries, revisions, byte integrity, draft recovery and the distinction between app files and versioned Termux copies. Tool registration changes must respect assistant-level settings across chat, workflows and background execution.

## Documentation and screenshots

Keep [README.md](README.md) and [README.en.md](README.en.md) aligned when changing user-facing capabilities. Describe actual behavior and distinguish this fork's additions from inherited features. Link limits and setup details instead of claiming unlimited background execution or complete deterministic replay.

The [gallery](docs/screenshots.md) uses Android instrumentation captures. When replacing them, retain the original files, record the source commit/run and update [provenance.json](docs/media/screenshots/provenance.json). Do not present edited mockups or fixture token rates as real device performance.

## Bundled ReBro kit

The initial kit files are preserved byte-for-byte and checked against package manifests. Preserve provenance and vendor licenses. Do not mass-format vendored scripts or silently change the dated device/Frida baseline. A new kit version should make its origin and migration behavior explicit.
