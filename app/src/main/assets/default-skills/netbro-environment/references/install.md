# Installation and compatibility

The APK includes skills and Python helpers, not external scanners, their wordlists,
templates, API keys or containers. Installing a program is a separate operation
in the selected environment; opening NetBro does not install it.

| Tool | Check | Route |
|---|---|---|
| Nmap | nmap --version | Native Termux has an official package: pkg install nmap |
| Nuclei | nuclei -version | Check your repository's package or build with the official Go toolchain; upstream: go install -v github.com/projectdiscovery/nuclei/v3/cmd/nuclei@VERSION |
| BBOT | bbot --version | Upstream supports Linux and recommends pipx install bbot; native Android is not listed as supported |
| Legba | legba --version | Upstream provides releases and cargo install legba; verify Android compatibility of the binary and native dependencies separately |

Replace VERSION for Nuclei with a selected published tag, or deliberately choose
latest, and record the installed version. Check the selected release's Go
requirements. Do not execute a downloaded Linux ARM64 ELF as an Android ELF
without checking ABI/loader compatibility. Go/Rust/Python must be available in
the environment where the corresponding build runs.

If BBOT/Legba do not work natively, use the user's existing Linux in proot/chroot
or an authorized remote host. Do not automatically replace a Linux rootfs,
container, system Python or working environment. A proot root identity does not
grant raw-socket capabilities. Do not assume Docker is available merely because
an upstream README uses it.

Check help and version inside the selected environment, and run the scan there.
Use actual mounts/SSH paths for case files; a private Termux skill_root is not
automatically visible in another environment. Ordinary TCP connect scans and
HTTP requests usually do not require root.

BBOT may install module dependencies during scan preparation. Inspect selected
modules/dependencies first; do not default to install-all-deps. Add API keys only
for selected sources using the user's configuration. Do not print configurations
containing secrets in chat.

## Primary sources

- [Nmap in Termux](https://github.com/termux/termux-packages/blob/master/packages/nmap/build.sh)
- [BBOT installation](https://github.com/blacklanternsecurity/bbot/blob/stable/docs/index.md)
- [Nuclei installation](https://docs.projectdiscovery.io/opensource/nuclei/install)
- [Legba installation](https://legba.evilsocket.net/install/)
