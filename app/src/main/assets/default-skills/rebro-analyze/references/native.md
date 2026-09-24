# Native RE: focused reference

Start with SHA-256, `file`, ELF headers, program headers, notes, imports/exports and
build ID. Verify ELFCLASS64/AArch64 and Android dynamic linker/runtime before
executing a downloaded binary. GNU/Linux arm64 is not android/arm64.

```sh
file "$LIB"
sha256sum "$LIB"
llvm-readelf -h -l -n -d --wide "$LIB"
llvm-readelf --dyn-syms --wide "$LIB"
llvm-objdump -d --no-show-raw-insn "$LIB" > "$REBRO_CASE/work/disassembly.txt"
rabin2 -I -i -E "$LIB"
```

In radare2, begin with metadata and a specific function. `aaa` over a multi-megabyte
vendor ELF is not a mandatory first step. Disassemble the range relevant to the
question; even a full objdump needs time/output bounds.

For a hook address, record module path, build ID, load base and selected RVA.
File offsets differ from RVAs: map `PT_LOAD.p_offset` against `p_vaddr`.
Do not transfer offsets between versions, splits or ABIs. A missing symbol in a
stripped ELF does not prove missing code.

JNI may use exported `Java_*` names or dynamic registration. For the latter,
investigate module loading and RegisterNatives timing in the selected target.
A focused Java hook may be cheaper than deep JNI tracing. Do not hook all libart
symbols persistently.

PAC/BTI depend on CPU and specific code/mappings, not one checkbox. For SIGILL/SIGSEGV
after a hook, capture crash evidence and check ABI, address, prototype and executable
mapping first. Do not "fix" it by stripping PAC or writing instructions without
understanding pointer provenance and calling convention.

For 16 KiB compatibility, check ELF LOAD segment alignment separately from `.so`
alignment inside the APK. `zipalign` does not relink an ELF.

Use `Process.getModuleByName(...).findExportByName(...)` and Interceptor for an initial
observation; reserve Stalker for a specific tracing hypothesis.

Sources: [LLVM readelf](https://llvm.org/docs/CommandGuide/llvm-readelf.html),
[r2 book](https://book.rada.re/), [Frida Stalker](https://frida.re/docs/stalker/),
[Android page sizes](https://developer.android.com/guide/practices/page-sizes).
