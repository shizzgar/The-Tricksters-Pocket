# Nuclei: reproducible execution

Inspect installed -h/-version and supported flags. Templates change independently
of the engine: record their tag/commit or hashes of selected local files.

~~~sh
nuclei -version
nuclei -validate -t /absolute/case/templates/selected.yaml
~~~

Example of a bounded run over a prepared list with a selected template:

~~~sh
nuclei -l /absolute/case/urls.txt \
  -t /absolute/case/templates/selected.yaml \
  -rl 5 -c 2 -bs 2 -timeout 10 -retries 1 \
  -ni -dr -duc -jsonl -omit-raw -omit-template \
  -o /absolute/case/run/findings.jsonl
~~~

Verify every flag in the installed version.
-rl limits requests/second, -c concurrent templates, and -bs targets per template;
they are not interchangeable. Also set the overall deadline in the Termux job.
-ni disables Interactsh/OAST, -dr redirects and -duc update checks. These are not
a universal sandbox for template contents: read the template itself. If the task
requires callbacks/redirects, choose the specific authorized behavior and record
the change and possible external requests in the case.

Do not use -ai for an ordinary local run: it is an external service.
Do not enable -code, -headless or DAST by default.
No findings does not mean "no vulnerabilities": templates may have been skipped,
upstream requirements unmet, or requests undelivered.

JSONL with -omit-raw/-omit-template reduces unnecessary context, but extractors
and URLs can still contain secrets. The local summarize.py omits raw HTTP and
extracted values, and strips query/userinfo from address previews.

## Sources

- [Installation](https://docs.projectdiscovery.io/opensource/nuclei/install)
- [Execution and flags](https://docs.projectdiscovery.io/tools/nuclei/running)
- [Template repository](https://github.com/projectdiscovery/nuclei-templates)
