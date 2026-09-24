# Validation and acceptance

Original offline validation ran on Linux with Python 3.12 and Node, not Termux/ART
or the user's patched Frida. Exact tool versions and historical results are in
tests/VALIDATION.json and tests/VALIDATION.txt.

Coverage:

- All 38 agents: load, init and cleanup against bounded mock APIs.
- All 16 profiles: combined initialization, hook limits and completion.
- Bundle generation for every profile with supported mock bridge adapters.
- Preservation of receiver, arguments, result and original Java exception.
- Observer exceptions do not replace the method's original result.
- Removal of the owned implementation using a read-back token; refusal to overwrite an existing implementation.
- Quotas, unavailable Java/APIs, late ELF loading and memory boundaries.
- IPv4 byte order, JNI table/row layout and Stalker cleanup.
- Java-module callback paths with representative arguments.
- Pin mismatch, timeout cancellation, controller failures, resume-after-load-failure and unload/detach.
- Syntax of all supplied Python/JS files.

Repeat with:
```sh
python tools/verify.py --tests
```

Do not broaden device testing without a concrete reason. Initial acceptance:
doctor online, both smoke phases, short native-survey/java-survey, then an actual
call to one relevant hooked method. Record runtime, hashes, target process and
results. Those events establish on-device coverage.

Mocks do not verify bridge patch quality, ART inline/compiled hooks, vendor SELinux,
PAC/BTI/Stalker on this firmware, third-party app classes or all Android16 overloads.
No successful mock test marks an agent device-verified.
