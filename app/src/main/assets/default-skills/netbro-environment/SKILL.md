---
name: netbro-environment
description: "Check the NetBro environment in Android/Termux or a selected Linux: BBOT, Nmap, Nuclei and Legba availability and versions, compatible installation routes and dependency diagnosis."
---

# NetBro environment

Check only the environment needed for the task first. A skill does not imply
that its external binary is installed. Do not change a working toolchain merely
to try instructions written for another environment.

Obtain this package's skill_root through use_skill/termux_skill_sync. The helper
does not scan the network, install packages, invoke su or read credentials:

~~~sh
python3 -B scripts/preflight.py --tool nmap
python3 -B scripts/preflight.py --tool bbot --tool nuclei --timeout 8
~~~

Without --tool, all four programs are checked. Each probe runs only the located
binary's version command, with a deadline and bounded output. version_ok means
only that the version command succeeded, not that every module/plugin is ready
or that a scan succeeded. Save the result in the case directory.

Then read [installation and compatibility](references/install.md).
Within the selected environment, check actual --help, required modules/templates,
free space, routing and DNS only when relevant to the task. Distinguish native
Termux, proot/chroot and remote Linux; paths and versions are not interchangeable.
A scan launched on remote Linux uses that host's network.

For loader, wheel, compiler or module errors, identify the missing layer.
Do not repeat an unchanged installation, use random binaries, or globally alter
VPN, DNS or SELinux.
