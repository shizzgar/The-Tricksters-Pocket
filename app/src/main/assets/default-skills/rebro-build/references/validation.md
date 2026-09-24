# Original Rebro 2.3 validation and its boundaries

The original kit was checked on host Linux/Python 3.12, not the phone. Real root,
Android Package Manager, apksigner and Frida endpoint were not invoked there.
That release reported **67 Python tests** (55 kit + 12 supplied Frida Pack tests)
and **65 JavaScript contract checks** from the pack. Preserve those original scopes.
ZIP limits, checksums, links, CLI help and JavaScript syntax were also checked.
Exact historical results are in validation-results.json of the original distribution.
These counts describe the supplied kit, not the current app CI or new phone tests.

Collector checks covered output/time bounds, root-argument quoting, exclusion of
raw bridge/loader source, search bounds, no symlink following and self-contained
reports. Nine 2.2 checks covered wide search across large build trees, independent
limits, false loader candidates, reference paths, zipalign usage and focused mode.
Adapter checks covered external config/output, pin mismatch, rejection of an
observed-only baseline, offline native build and refusal to overwrite config.
Five 2.3 checks covered same-Script frida_java_bridge_default export in Node VM,
source-byte preservation, rejection of wrong hash/Compiler bundle/ESM/missing export,
manifest mode selection and exact pin agreement with the original prompt.
Host tests did not perform root operations or live injection.

A real user inventory ZIP from collector 2.1.0 supplied root reads, tool probes,
Frida handshake and loaded/live executable hashes. Collector 2.2.0 was verified
against host fixtures. The later full prompt resolved this phone's paths/pins,
so another collector run for those fields is unnecessary. Inventory does not
establish APK installation or Java injection.

The original integration retained the Frida Pack's 85 files unchanged. This app's
English adaptation changes documentation/catalog text, not vendor runtime code,
and regenerates checksums. Historical checks included 26 targeted integration
tests; 29 pipeline tests and vendor checks retained their earlier results on
unchanged runtime sources. The user also supplied a text report of successful
native RPC smoke and Compiler TS build without its raw event logs. That run did
not test a fresh Java hook or the new rebro-flat adapter on the phone.

Behavior classes checked: exact patches and stale-preimage rejection; path safety;
APK-set integrity; certificate consistency; immutable outputs; parent receipts
and stale-upstream rejection; prevention of repeated install; unknown commit
outcomes and read-only reconciliation. Signing/PM use tool models: this checks
process control, not Android end-to-end or cryptographic certification.

For device acceptance of an APK route, use a small owned lab APK. This is not a
mandatory startup sequence for every analysis task:

1. Import/enable needed skills, use_skill/sync and check script availability.
2. Doctor, actual versions, full pins and fresh RAM/disk checks.
3. Acquire source APKs and verify the actual complete set/certificate.
4. No-op decode/build/sign/install and baseline functional control.
5. Small resource patch with an exact plan; build/sign with the same lab key.
6. Plan/apply, installed-APK hashes, target and neighboring acceptance tests.
7. Separately: controlled split set, wrong-certificate rejection, rejection of a
   second JVM job and safe cancellation of the owned managed job.
8. After acceptance needed for the selected route, proceed to the real case.
   Verify the new Frida Java adapter with the existing patched bridge, not an
   arbitrary replacement upstream bundle.

Simulating an interrupted commit on the phone is a separate lab experiment.
Learn read-only reconcile first. Do not practice recovery on the only installation
of an app containing valuable data.
