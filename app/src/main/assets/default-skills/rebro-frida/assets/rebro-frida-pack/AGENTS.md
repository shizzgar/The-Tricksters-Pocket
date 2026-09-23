# Instructions for the device-side coding/research agent

This pack targets the user's already working Frida baseline. Read README_RU.md and docs/COMPATIBILITY.md before a live run.

1. Keep the existing server, binding, patched Java bridge, root policy, SELinux mode, ports and tmux sessions intact. Do not run package upgrades, pip installs or server replacement as part of setup. The reported 17.18.0 / 17.2.14 split is accepted baseline data, not a repair request.
2. Work from Termux using the same Python that loads the working binding. Connect only to the configured endpoint, default 127.0.0.1:27044. No USB discovery or external ADB is required.
3. Verify distribution with python tools/verify.py. Read existing local.json if already configured. Use the known working bridge path; tools/find_bridge.py searches only an explicitly chosen workspace root and prints candidates/hashes. Resolve multiple candidates from the user's existing launcher/config, not arbitrary filename order.
4. Configure once to pin the current known-good files. Do not repeat configure to hide a hash mismatch. The archive contains no full trusted baseline hashes: shortened hashes in the user's report are not enough to authenticate files.
5. Run doctor --online and ps. Select a current target PID with the user's intended package/process; PID 12345 and example package names in docs are placeholders. Use a disposable/sample application for the first smoke run. The controller does not need root; the existing service owns injection privileges.
6. Run smoke first, then one short relevant profile. Native phase passing does not imply Java phase passing. Java is loaded in the same Script context as the selected agents. Two independent Frida Scripts do not share Java globals.
7. Inspect summary.json, events.jsonl, agent_status and hook/hit events. Active only means initialization. No calls observed is not proof of no behavior. Record unavailable methods, dropped events, truncation, detach/crash, and the exact observed runtime version.
8. Prefer attach to an already running process. --spawn is an explicit optional operation; package creation/start changes application state and spawn cancellation is best effort. Do not automatically restart an app after a failure. Never kill unrelated processes, remove sessions, clean the user's case directories or disable system protections.
9. For a task-specific hook, first inspect classes/loaders or modules/exports. Set an exact target in a separate options JSON. Use module-relative offsets derived from the actual on-device ELF; do not reuse absolute addresses across launches. Add new agents using examples/agent-template.js, catalog.json and options.json together.
10. Use one session/profile at a time, limit hot methods and Stalker. Keep JVM-heavy static-analysis jobs sequential; this device had about 3 GB available RAM. The pack never automatically deletes logs; report disk growth to the user when relevant.
11. Do not reinterpret absence of an OkHttp class, renamed symbols or a bridge-format mismatch as a reason to upgrade Frida. Diagnose using docs/BRIDGE.md and docs/COMPATIBILITY.md. If this bridge uses a private loader, preserve that loader contract.
12. Produce a concise device validation report: endpoint, binding/bridge hashes, target PID/package, native/Java smoke outcomes, modules actually exercised, hook-hit counts, errors/quotas, output paths. Distinguish offline tests, initialized agents and actual observed target calls. Acknowledge any remaining unknowns.

These are implementation instructions for this package, not a new user approval procedure. Use the user's existing task scope and authorizations for routine read-only inspection and reversible work.

