# Native RE: короткая карточка

Начать с SHA-256, `file`, ELF headers, program headers, notes, imports/exports и
build ID. Убедиться в ELFCLASS64/AArch64 и Android dynamic linker/runtime, прежде
чем запускать скачанный бинарник. arm64 для GNU/Linux не равен android/arm64.

```sh
file "$LIB"
sha256sum "$LIB"
llvm-readelf -h -l -n -d --wide "$LIB"
llvm-readelf --dyn-syms --wide "$LIB"
llvm-objdump -d --no-show-raw-insn "$LIB" > "$REBRO_CASE/work/disassembly.txt"
rabin2 -I -i -E "$LIB"
```

В radare2 сначала metadata и конкретная функция. `aaa` на многомегабайтном vendor ELF
не обязательный первый шаг. Дизассемблировать нужный диапазон по вопросу; длинный
полный objdump также запускать с лимитом времени/вывода.

Для адреса hook фиксировать module path, build ID, load base и выбранный RVA.
Файловое смещение не равно RVA: сопоставлять `PT_LOAD.p_offset` и `p_vaddr`.
Не переносить offsets между версиями, splits или ABI. На stripped ELF отсутствие
символа не доказывает отсутствие кода.

JNI бывают экспортируемые `Java_*` и динамические регистрации. При втором варианте
исследовать загрузку модуля и момент RegisterNatives в конкретном target; общий
Java hook может быть дешевле глубокого JNI trace. Не цеплять все libart symbols
на постоянной основе.

PAC/BTI — свойства CPU и конкретного кода/отображений, а не одна галочка.
При SIGILL/SIGSEGV после hook сначала снять crash evidence, проверить ABI, адрес,
prototype и executable mapping. Не «чинить» strip PAC или записью инструкций
без понимания pointer provenance и calling convention.

Для 16 KiB совместимости проверять LOAD segment alignment в ELF отдельно от
alignment `.so` внутри APK. `zipalign` не перелинкует ELF.

Использовать `Process.getModuleByName(...).findExportByName(...)` и Interceptor
для первого наблюдения; Stalker оставить для конкретной трассировочной гипотезы.

Источники: [LLVM readelf](https://llvm.org/docs/CommandGuide/llvm-readelf.html),
[r2 book](https://book.rada.re/), [Frida Stalker](https://frida.re/docs/stalker/),
[Android page sizes](https://developer.android.com/guide/practices/page-sizes).
