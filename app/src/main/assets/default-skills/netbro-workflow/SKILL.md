---
name: netbro-workflow
description: "Conduct a NetBro network investigation: define scope, select BBOT/Nmap/Nuclei/Legba, correlate evidence, interpret results and resume interrupted work."
---

# Network investigation

Choose the smallest useful workflow for the task. Do not start a scan to answer
an ordinary question. Use the user's established authorization and constraints;
discovered assets do not automatically become new targets. Do not ask again for
stages already authorized.

1. Record the objective, hosts/networks/URLs, exclusions, allowed checks, time
   budget and traffic budget. For authentication checks, separately record the
   service, supplied inputs and account constraints.
2. Use netbro-environment to check the required tool in the selected environment.
   Do not recheck the entire stack for every short operation.
3. Select BBOT for reconnaissance, Nmap for ports/services, Nuclei for selected
   templates, or Legba for a specified protocol and finite inputs. These stages
   are independent; do not run all four programs by habit.
4. Create a separate run in a private case directory. Save versions, parameters
   without secrets and original results.
5. Run long operations through termux_job_start with a finite deadline,
   operation_id and job_id. Reconcile the previous job before repeating a request.
   A wait timeout does not mean the process stopped.
6. Verify producer completion and file completeness. Relate each finding to the
   objective and its evidence; report the result and coverage limitations.

## Package and evidence

Obtain this package through use_skill/termux_skill_sync. Use only the returned
skill_root as working_dir; write results outside the package.
Read the [results contract](references/evidence.md).
For a substantial case, copy the [state template](assets/STATE.template.md) into
the case directory and fill it with real observations.

The local helper only parses saved files; it does not access the network or run scanners:

~~~sh
python3 -B scripts/summarize.py --help
python3 -B scripts/summarize.py nmap /absolute/case/run/services.xml --limit 20
python3 -B scripts/summarize.py bbot /absolute/case/run/output.json --limit 20
python3 -B scripts/summarize.py nuclei /absolute/case/run/findings.jsonl --limit 20
python3 -B scripts/summarize.py legba /absolute/case/run/matches.jsonl --limit 20
~~~

The helper counts all records within the configured input limits and limits only
the preview. It omits Legba credential data and Nuclei raw HTTP content.
Empty JSONL is valid: it means zero records, not successful scan completion.
Malformed or oversized data returns exit 2.
Use [scope_filter.py](scripts/scope_filter.py) for scope filtering: it selects
hosts/IPs/URLs from a file using explicit rules, without DNS or command execution.
