---
name: netbro-nmap
description: "Use Nmap from Termux/Linux: inventory hosts, TCP/UDP ports and services, select version detection and NSE for the task, handle privilege constraints, save and interpret XML."
---

# Nmap

Check nmap --version and the selected environment. Use netbro-environment for
installation; read the [procedures](references/operations.md) for scan options.

1. Take explicit hosts/networks, exclusions and required ports from the case.
2. For ordinary Termux work, start with an unprivileged TCP connect scan (-sT),
   a reasonable port set and a finite host timeout.
3. Separate discovery from service detection. If discovery is unavailable, use
   -Pn for a known bounded list; do not automatically scan an entire large network.
4. SYN/UDP/OS detection and some NSE scripts have additional requirements and
   traffic costs. Check actual capabilities; proot root alone does not grant them.
5. Save XML and text through -oA; inspect the exit code and XML runstats.
6. Base reports on host/port/state/service evidence; do not call filtered or
   open|filtered ports open. A version banner alone does not prove vulnerability.

Load netbro-workflow for summarize.py nmap. The helper shows port states and a
limited preview; omitted preview items remain included in total counts.
