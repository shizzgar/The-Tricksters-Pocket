# Sources and provenance

Original date: 2026-09-22. Controller, runtime, agents and tests were authored for
this pack; external repositories were not copied or installed. API examples were
checked against primary sources:

- [frida-python 17.2.14 core.py](https://github.com/frida/frida-python/blob/17.2.14/frida/core.py): device/session/script, exports_sync, Cancellable.
- [Frida JavaScript API](https://frida.re/docs/javascript-api/): Process/Module/NativePointer/Java APIs and capability checks.
- [Frida bridges](https://frida.re/docs/bridges/): external Java bridge role.
- [Frida best practices](https://frida.re/docs/best-practices/): hook behavior and memory management.
- [frida-java-bridge env.js, commit b38a5b647d3e6b72aa19bcf8eb5e41c550a0e622](https://github.com/frida/frida-java-bridge/blob/b38a5b647d3e6b72aa19bcf8eb5e41c550a0e622/lib/env.js): JNI RegisterNatives slot/signature.
- [frida-java-bridge class-factory.js](https://github.com/frida/frida-java-bridge/blob/main/lib/class-factory.js): implementation getter returns the replacement; setter creates a callback. GitHub blob SHA at review: 68a1a3b4cefa5ad24e22ee190d1f091b5d1852c8 (file SHA, not a commit).
- [Medusa](https://github.com/Ch0pin/medusa): separate-bridge integration was studied; its code is not included.
- [Historical research](GITHUB_RESEARCH_RU.md): 59 upstream repositories, links, compatibility and integration order. Project status is dated evidence.

Small original modules were chosen for the user's private baseline: large frameworks
may bring another Java bridge or replace the Python binding. External integration
needs a separate adapter and verification against pinned versions. A link or upstream
status establishes neither inclusion nor testing on the phone. The English adaptation
translates documentation/catalog descriptions and preserves runtime sources/licenses.
