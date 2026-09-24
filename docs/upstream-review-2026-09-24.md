# Upstream review — 24 September 2026

Compared our `d51d1a5c` baseline with two pinned upstreams. The four fully integrated ExTV commits are also retained in Git ancestry; RikkaHub changes remain selectively ported. These are selective integrations, not an assertion that the fork has merged every upstream feature.

| Source | Reviewed range | Decision |
|---|---|---|
| [ExTV/RikkaHub Agent](https://github.com/ExTV/rikkahub-agent/compare/51cb7e1a53d73fb8f27ff99bc66532313f4ecd97...1c2ca2dcbd440570f5c889b36586824e83f33fc7) | 4 commits after our ExTV base | Integrate all four reliability fixes, retaining this fork's live steering and journal |
| [RikkaHub](https://github.com/rikkahub/rikkahub/compare/2689e753afc97c69fc5044bcd9fc963ecbb175db...2cf09d2ae4337a495b26fd73ff7114a69141f75e) | 30 commits after the common base | Integrate missing fixes; preserve equivalent or more complete implementations already in this fork |

## Reliability and performance

| Upstream change | Result in this fork |
|---|---|
| ExTV [`787589eb`](https://github.com/ExTV/rikkahub-agent/commit/787589eb0f47839cc7e4892d87f1f4a35f8331c4) | Idempotent database migration 30→31; restored databases with an existing `shell_compatibility_mode` column no longer repeat `ALTER TABLE` and crash |
| ExTV [`f088df5a`](https://github.com/ExTV/rikkahub-agent/commit/f088df5a1ec2e1a8d1ae8168d7c5ec4bd6383dd7) | Parallel Chat Completions tool results remain adjacent; tool images move after the whole result batch. Responses API retains inline tool images without duplicating them |
| ExTV [`4e18fb20`](https://github.com/ExTV/rikkahub-agent/commit/4e18fb20cf533909b74fb92838b0fb4c5f5e2bb5) | Build the message-position lookup once per merge instead of rescanning the complete history for each generated message |
| ExTV [`1c2ca2dc`](https://github.com/ExTV/rikkahub-agent/commit/1c2ca2dcbd440570f5c889b36586824e83f33fc7) | Resuming an approved batch also executes its still-unexecuted automatically approved siblings, while pending approvals remain pending |
| RikkaHub [`c8853531`](https://github.com/rikkahub/rikkahub/commit/c8853531b4cd6084c6c12f367fb94f61fbb26758) | Adapted QuickJS migration: timeout and cancellation interrupt native execution, including result serialization; runtimes close after execution stops. Custom search supports async scripts as well as existing synchronous fetch usage |
| RikkaHub [`bd936caa`](https://github.com/rikkahub/rikkahub/commit/bd936caa) | Supply a session ID for otherwise anonymous generation; retain our existing separate IDs and priorities for title, suggestion and compaction requests |

The QuickJS adaptation deliberately retains the fork's **30-second HTTP deadline and 256 KiB response-body cap**. The native binding change must not restore unbounded `body.string()` reads. Logs, returned JS values, memory and stack also remain bounded. No new JavaScript tool is automatically enabled for an assistant.

## Presentation and provider compatibility

Integrated missing pieces of line-number-free code copying ([`324b337b`](https://github.com/rikkahub/rikkahub/commit/324b337b)), JPEG output for the existing `.jpg` crop path ([`7c1629d0`](https://github.com/rikkahub/rikkahub/commit/7c1629d0)), and segmented reasoning timelines without opaque blocks or a large offscreen compositing layer ([`b7f06db1`](https://github.com/rikkahub/rikkahub/commit/b7f06db1), [`2cf09d2a`](https://github.com/rikkahub/rikkahub/commit/2cf09d2a)). Pending tool approvals still force their containing chain open.

Integrated generic reasoning OFF / DashScope effort parameters ([`40426e93`](https://github.com/rikkahub/rikkahub/commit/40426e93), [`94504b5c`](https://github.com/rikkahub/rikkahub/commit/94504b5c)). Reviewed omission of `name` from tool-role messages ([`6e98691c`](https://github.com/rikkahub/rikkahub/commit/6e98691c)), inline-code ligatures ([`a7850967`](https://github.com/rikkahub/rikkahub/commit/a7850967)), favorites undo ([`445341e9`](https://github.com/rikkahub/rikkahub/commit/445341e9)) and numbered conversation forks ([`458c16df`](https://github.com/rikkahub/rikkahub/commit/458c16df)). Existing equivalent changes are retained rather than counted again as new features.

Gemini `propertyNames` removal ([`4391d5a5`](https://github.com/rikkahub/rikkahub/commit/4391d5a5)) is already covered by our recursive schema allowlist. Replacing it with upstream's smaller blacklist would regress validation of other unsupported keywords.

## Deferred deliberately

- The broad session-manager refactor intersects our durable task state, queued inputs and operation-boundary steering. A wholesale merge would require a separate lifecycle migration review; it is not needed to acquire the independent reliability fixes above.
- Workspace multiselect export is separate from the existing skill-package export. It remains a candidate for a focused workspace UX change.
- Additional TTS providers, model registrations, dependency churn, removed providers and glass/blur styling are not prerequisites for this update. Existing tool availability preferences and our navy/steel theme take precedence.
- Upstream's contribution policy and release version numbers are not copied into this independently maintained fork.

## Local changes driven by the supplied traces

See the [anonymized trace review](trace-review-2026-09-24.md). This update adds targeted Termux diagnostics, a per-call preview limit within user-configured ceilings, correct cancellation classification for new trace events, and ReBro branding. Private traces are not included in the repository.
