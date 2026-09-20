# Configurable compaction runtime

This change applies to both automatic compaction and the manual **Compress context** action.
Settings are in **Settings → Models → Context compaction runtime**, independently of the
automatic-compaction switch. Defaults are 15 minutes per model request, 60 minutes per operation,
and two parallel map requests. Request limits are 1–120 minutes; the operation limit is at least
the request limit and at most 240 minutes; parallelism is 1–4. Existing preferences and JSON
backups without these fields inherit the defaults. An operation snapshots its settings at start.

The operation deadline includes all map/reduce passes. The request deadline includes provider
queue time. OpenAI Chat Completions/Responses, native Claude and native Google non-streaming
requests also receive matching HTTP read/call limits; their connect/write settings are retained.
Codex/Grok delegate their non-streaming requests to the Responses implementation. Other provider
transports retain their own limits and are still bounded by the outer coroutine deadline when
they cooperate with cancellation. A reverse proxy or server may impose a shorter timeout.

The shared HTTP body reader keeps cancellation attached through the body read, including the
period after headers arrive. Ordinary requests keep their original timeout settings. Local
deadline expiry is reported as a compaction failure; user cancellation stays cancellation.
Manual compaction shows elapsed time and retains its existing Cancel button.

Automatic compaction after tools no longer consumes the model/tool turn budget. All such
compactions share one cumulative allowance equal to the configured operation limit per loop
invocation. This allowance is not reset by each compaction. Step limits and the model/tool time
limit remain active. This is not process-death recovery or a durable task scheduler.

## Build and test

Use JDK 21, Android SDK platform 37, the project's Gradle wrapper, CMake 3.22.1, Bun and pnpm 10.
Initialize the pinned submodules with `git submodule update --init --recursive`.

```sh
bash ./gradlew :common:testDebugUnitTest :ai:testDebugUnitTest
bash ./gradlew :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.data.ai.CompactionRuntimeTest' \
  --tests 'me.rerere.rikkahub.data.ai.GenerationTurnClockTest' \
  --tests 'me.rerere.rikkahub.data.ai.ContextCompaction*Test' \
  --tests 'me.rerere.rikkahub.data.ai.GenerationHandlerTurnBudgetTest' \
  --tests 'me.rerere.rikkahub.data.datastore.PreferencesStoreTest'
bash ./gradlew :app:assembleDebug
```

The `Compaction debug APK` Actions workflow performs these checks and publishes the signed
ARM64 debug APK plus its commit and SHA-256. Its existence does not mean a build has run.
The existing debug application ID is `excp.rikkahub.debug`, separate from the installed release.
It needs its own settings/permissions. No release signing key is used; different clean CI runs
can produce different debug signing certificates, so this is a test build rather than a release
update channel. Do not uninstall the main app to resolve a signature conflict.

## Device acceptance checks

1. With automatic compaction disabled, set the three limits, restart the app, and confirm they
   persist. Manual compaction must use them; raising the request limit also raises a smaller
   total limit. Validate exported/imported settings too.
2. Against a controlled non-streaming endpoint, delay a valid response beyond three minutes.
   With a higher configured request limit both manual and automatic compaction should finish.
   Separately delay beyond ten minutes to verify the inherited HTTP read limit is gone.
3. Set a one-minute request limit; delay past it. Confirm a request-timeout error, no partial
   summary publication, and cancellation on the server where supported. Restore normal limits.
4. Use several groups with a short total deadline. Confirm the whole-operation limit cancels
   siblings, and the previous complete context remains usable. External server cancellation
   must be measured; closing a client socket alone is not proof the model stopped computing.
5. Cancel after response headers but before the body completes. The HTTP call must close
   promptly. Also verify Cancel during queue wait and manual compaction.
6. Perform automatic compaction that takes longer than the model/tool turn budget but fits the
   compaction allowance. Verify the next model/tool step still runs. Repeated compactions must
   consume the same finite allowance.
7. Compare parallelism 1 and 2 on the two-replica TP=2 endpoint. Record queue time, input/output
   tokens, operation duration and server load before changing the default again.

This patch does not repair the separate digest token-budget, thinking-profile, missing-usage,
SSE-integrity or durable-execution findings from the audit. Those need separate changes.
