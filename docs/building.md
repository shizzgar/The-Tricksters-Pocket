# Build from source

[Overview](../README.en.md) · [Documentation](README.md) · [Contributing](../CONTRIBUTING.md)

These instructions follow the checked-in [runtime workflow](../.github/workflows/compaction-debug.yml). CI runs on Ubuntu 24.04. Use the Gradle wrapper rather than a separately installed Gradle version.

## Toolchain

| Component | Project configuration |
|---|---|
| JDK | 21 |
| Android SDK | Compile/target API 37; SDK package `platforms;android-37.0` |
| Build tools | `37.0.0` |
| CMake | `3.22.1` |
| Web bundle | Bun and pnpm 10 on `PATH` |
| Native sources | Recursive Git submodules |
| Python | Python 3 for Termux runtime tests |
| Device support | Minimum API 26; `arm64-v8a` and `x86_64` |

Dependency versions are pinned in [libs.versions.toml](../gradle/libs.versions.toml) and the [Gradle wrapper](../gradle/wrapper/gradle-wrapper.properties). Gradle installs the web dependencies with Bun and builds the web bundle with pnpm. The `bun.lock` generated from the tracked pnpm lockfile is not committed.

## Clone and build

```sh
git clone --recurse-submodules https://github.com/shizzgar/rikkahub-agent.git
cd rikkahub-agent
git submodule update --init --recursive
```

Point `ANDROID_HOME` at your SDK, or set `sdk.dir` in your local, untracked `local.properties`. Install the project's SDK packages:

```sh
sdkmanager 'platforms;android-37.0' 'build-tools;37.0.0' 'cmake;3.22.1'
bash ./gradlew --no-daemon --max-workers=2 :app:assembleDebug
```

APKs are generated under `app/build/outputs/apk/debug/`. For an ARM64 phone, choose the APK whose name contains `arm64-v8a`. The debug application ID is `excp.rikkahub.debug`.

```sh
adb install -r app/build/outputs/apk/debug/*arm64-v8a*.apk
```

An update requires the same application ID and signing certificate. A local debug key can differ from the key used by CI. Preserve an app backup before any installation migration; do not delete another app instance to resolve a signing mismatch.

Release signing is configured separately through `local.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Keep the keystore and credentials out of Git. The current CI workflow publishes debug APKs and does not use a release signing key.

## Tests

Run the tests relevant to your change. The full app test selection is maintained in the workflow; not every app test is included in the published 680-test baseline.

```sh
# HTTP and provider behavior
bash ./gradlew --no-daemon --max-workers=2 \
  :common:testDebugUnitTest :ai:testDebugUnitTest

# Example: ReBro preset, migration and prompt contracts
bash ./gradlew --no-daemon --max-workers=2 :app:testDebugUnitTest \
  --tests 'me.rerere.rikkahub.data.datastore.RebroAssistantTest'

# Durable jobs and full skill-package transfer
python3 -m unittest discover -s tests -p 'test_termux_*runtime.py' -v
```

UI checks use an Android API 35 x86_64 emulator. The workflow runs phone tests, changes the display to 1920×1200 at density 160 for the wide-layout test, captures 11 screens, then verifies the ARM64 APK signature. See the workflow for the exact instrumentation class list and emulator commands.

## CI artifacts

| Artifact | Contents |
|---|---|
| `rikkahub-compaction-arm64-debug` | ARM64 APK, `COMMIT.txt`, `SHA256SUMS` |
| `compaction-test-results` | JVM test result files |
| `trajectory-visual-checks` | Android instrumentation output, screenshots, emulator log |

Artifacts are retained for 14 days. Documentation screenshots are copied into the repository so they remain available after CI artifact expiry. Their [provenance manifest](media/screenshots/provenance.json) records the original build and file hashes.

The workflow runs on `master` and the existing runtime feature branches, and supports manual dispatch. Changes limited to Markdown, documentation assets and issue templates do not rebuild the APK. Changes to the workflow itself still run the build.

## What CI does not establish

JVM and Python tests exercise runtime contracts; emulator tests exercise Android integration and UI. They do not verify the user's physical Termux installation, live Frida setup, inference server or OEM background-process behavior. Reports of those checks should identify the device, configuration and tested commit separately.
