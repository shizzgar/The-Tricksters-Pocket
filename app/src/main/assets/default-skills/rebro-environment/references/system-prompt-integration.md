# System-prompt evidence integrated into Rebro kit 2.3

Accepted source: `REBRO-SYSTEM-PROMPT-20260922(1).md`, SHA-256
`fafcecdbc2e9079ccb047e8b91c8a7171f693bfe289af54181084aaf6029145f`.
The original kit preserved it unchanged under source/reference-inputs. The kit
transferred concrete contracts into specialist instructions. The app's English
adaptation now keeps a shorter coordinating system prompt and loads details through
skills, supplemented by local evidence and current version-matched Local search.

## Resolved gaps

Sections 10–11 supplied canonical paths and full expected service, Python extension
and bridge hashes, plus the flat-loader recipe. A separate
`config/pins.rebro-known-good-20260922.json` was created in environment/frida.
Trust derives from the explicitly supplied baseline, not automatic acceptance of
inventory bytes. The adapter checks actual files before use.

Canonical bridge:

```text
/data/data/com.termux/files/home/rebro/cases/frida-repair/bridge-src-20260921/build/bridge-final.js
6be272a9e37d5c8e230a922e95a3ac3f803053ea11236fe0bff0e251b00429ad
```

Its **frida_java_bridge_default** export was not recognized by the original Frida
Pack's generic auto adapter. Kit mode **rebro-flat** reads bytes, verifies the full
pin/format, preserves flat source at the beginning of the same Script, then assigns
`globalThis.Java = frida_java_bridge_default` for pack modules. The device's bridge
is unchanged. The original integration retained all 85 vendor files; this app
adaptation translates documentation/catalog text without changing vendor runtime code.
A missing expected export is an explicit error; stock bridge is never substituted.

Flat assembly and Compiler are different routes. Pass a completed Compiler bundle
to create_script unchanged; do not append a bridge or strip its package header.
The pack controller uses QJS; the new adapter had not yet run on the phone in the
supplied evidence.

**Another broad collector for paths/pins is unnecessary.** The earlier 2.1/2.2
requirement was resolved by the prompt. On mismatch, inspect the specific artifact;
do not search every build tree or reconfigure merely to accept a new hash.

## Interpreting the supplied agent report

| Capability | Supplied evidence | Supported claim |
|---|---|---|
| Transport/list | Inventory output and a later text report | Endpoint responded; process count is a snapshot |
| Native spawn/attach/load/RPC/cleanup | Report: disposable sh, exact echo nonce, PID 29180, unload/detach/kill | Reported pass for that chain; original event logs were not attached |
| Compiler TS build | Report of a successful 199-byte bundle | Reported build pass; execution of that bundle was not shown |
| Java ready/hook and short freeze recovery | Prompt baseline and known evidence path | Historical reported pass under bounded conditions; no fresh Java hook in that smoke |
| Native Interceptor / Stalker | Listed capabilities | The sh/RPC smoke did not test interception or Stalker |
| New kit adapter | Local same-Script alias/error checks | Phone integration pending; old smoke did not test new kit code |
| APK align/sign/install | Tools available | Functional lab-APK pipeline not supplied |

Do not label the entire stack unknown because no Java hook was repeated. Do not
label all Frida APIs tested because RPC ping passed. Preserve reported passes and
their limits; repeat only the check relevant to the task or adapter integration.

## System core and skills

| Original prompt content | Placement |
|---|---|
| §1–4: scope, evidence, UNKNOWN, state | Short system core and skills' agent-contract |
| §5–6: jobs, PTY, quoting, privileges | harness + agent-contract; live tool schemas |
| §7 and §10–11: hardware/pins/Compiler/bridge | environment profile + frida; system retains baseline-preservation rule |
| §8–9: acquisition/static/framework selection | acquire/analyze references |
| §12–17: identity, hooks, native, freezer, network/data | Frida and task-specific references, loaded on demand |
| §18: patch/rebuild/sign/install/verify | Five specialist skills with output/evidence gates |
| §19–21: diagnosis, cleanup, completion | Compact shared rules and case state |

The app keeps detailed prior operating guidance in rebro-workflow's
references/operating-rules.md. Simple questions/reads still do not require the
whole APK pipeline. Current schemas govern harness behavior; rg/jq/signer were
already installed in the dated inventory. PIDs, RAM, listener state and short
smoke results belong in case/evidence, not permanent system constants.

Skills and skill tools require connected skills; management is enabled separately.
The harness guide describes the reviewed source, not proof that this exact APK
is installed on the phone.

## Next relevant device check

Enable/sync the current rebro-frida package and obtain skill_root. Follow procedure.md:
create an external config from the supplied baseline, verify files and the loaded
binding, then check Java readiness and one relevant hook on a selected running lab
target. Service restart, another native smoke, Compiler rebuild or full inventory
are not automatic prerequisites.
