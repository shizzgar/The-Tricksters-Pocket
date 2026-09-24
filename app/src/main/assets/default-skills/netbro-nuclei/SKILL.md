---
name: netbro-nuclei
description: "Use Nuclei: select and validate templates for a specific target, bound rate/concurrency, save JSONL, interpret findings and verify their applicability."
---

# Nuclei

Check nuclei -version, help and the templates' version/source.
Use netbro-environment for installation and the
[procedures](references/operations.md) for execution.

1. Prepare a bounded list of in-scope URLs/hosts. Do not pass all reconnaissance
   results without checking them.
2. Select specific template IDs/files or a narrow set for the relevant service.
   Severity does not measure a template's load or operational safety.
3. Check changed templates with -validate; understand requests, redirects,
   matchers, extractors and external callbacks.
4. Set rate limit, concurrency, timeout, retries and an overall job deadline.
5. Save JSONL and diagnostic logs separately. Do not enable expensive
   headless/code/DAST modes as a universal way to "check everything".
6. Check errors/skipped templates and run completeness. Correlate findings by
   template-id, matcher and endpoint. Recheck only material findings rather than
   rerunning an entire set to obtain one missing file.

For aggregates, load netbro-workflow and use summarize.py nuclei.
Do not unnecessarily print raw requests/responses or extracted secrets in chat.
