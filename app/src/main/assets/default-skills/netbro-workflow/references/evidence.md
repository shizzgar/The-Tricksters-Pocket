# Evidence and handoff between stages

- Case: objective, scope, exclusions, environment (native Termux / specific Linux),
  program versions and paths, budget, active operation_id/job_id.
- Run: exact command without secrets, input files and their SHA-256, start/end,
  actual exit code, stop reason and stdout/stderr/results paths.
- Finding: original scanner record, host/service, observed fact, hypothesis,
  verification/refutation and limits of confidence.
- JSONL contains findings but usually does not establish whether the whole process
  completed successfully. An empty file and exit 0 do not establish coverage.
- Nmap XML may be cut off on cancellation. Require valid XML, finished exit and
  the producer's exit code; leave completion unknown when finished is absent.
- BBOT 2.x/3.x: structured events may use data or data_json. Preserve the original
  event and scope_distance; do not automatically pass an out-of-scope event to
  the next scanner.
- Nuclei: preserve template-id, matcher, timestamp, endpoint and exact template
  version. Do not publish raw HTTP, tokens or extracted credentials.
- Legba: treat partial=true as a partial observation. Output and sessions may
  contain passwords; use aggregates and a private evidence path in chat.

## Scope without expanding authorization

Example scope.txt:
~~~text
app.example.test
*.lab.example.test
192.0.2.0/28
2001:db8::/126
~~~

An exact name permits only that host. *.lab.example.test permits subdomains,
but not lab.example.test itself; add the apex on a separate line if needed.
CIDRs apply only to literal IPs; the helper does not resolve domains or prove
that an IP belongs to a domain.

~~~sh
python3 -B scripts/scope_filter.py --scope /absolute/case/scope.txt \
  --input /absolute/case/candidates.txt --output /absolute/case/allowed.txt \
  --exclude /absolute/case/excluded.txt
~~~

--exclude is optional and takes precedence. Output is created only when it does
not already exist; the helper never overwrites case files. An invalid line aborts
processing before writing. Inputs are hosts/IPs/HTTP(S) URLs, not scanner flags.
A host match does not constrain URL paths, ports or DNS redirects: check those
constraints separately before each stage.

After a lost response, read STATE and reconcile the existing job first. For Legba,
also check the session and wordlist fingerprints. Reusing a BBOT scan name may
append to old output; a new execution usually needs a new name rather than
mixing results.
