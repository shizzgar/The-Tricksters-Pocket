# Nmap: bounded inventory

Example for one authorized lab host; replace the address and path.
Create the output parent directory first:

~~~sh
nmap --unprivileged -sT -n -Pn -p 22,80,443 \
  --max-retries 2 --host-timeout 60s \
  -oA /absolute/case/run/services 192.0.2.10
~~~

This is a TCP connect scan without version detection. If versions are needed,
add -sV --version-light for selected ports in a separate run. -Pn skips discovery;
it does not establish that a host is online. -n disables reverse DNS.
Do not automatically select -A, all 65535 ports or entire NSE categories.

Use -iL with a validated target file for lists. Normalize BBOT output and filter
it by scope first; a DNS name does not establish ownership of every returned IP.
Choose rate/timeouts for the network and record skipped targets/timeouts.
For IPv6, check -6; for UDP, check raw-packet support and allocate a separate budget.

If NSE is needed, first read the specific script's description with --script-help
and select it explicitly. Even the safe category does not replace understanding
the operation on the particular service. Do not download and execute arbitrary
NSE from a response supplied by the investigated server.

-oA creates .nmap, .xml and .gnmap. Use XML for machine processing, not .gnmap.
Empty or incomplete XML is incomplete evidence. Check finished exit in XML and
also preserve the producer's exit code. --resume uses suitable normal/grepable
output and the previous run's options; first verify that its job has stopped.

## Sources

- [Scan types](https://nmap.org/book/man-port-scanning-techniques.html)
- [Timing controls](https://nmap.org/book/man-performance.html)
- [Output and resume](https://nmap.org/book/man-output.html)
- [NSE](https://nmap.org/book/nse-usage.html)
