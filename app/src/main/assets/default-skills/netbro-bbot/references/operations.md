# BBOT procedures

~~~sh
bbot --version
bbot --help
bbot --list-presets
bbot --list-modules
bbot -p subdomain-enum --current-preset
~~~

Example for authorized domain reconnaissance; replace example.test with the
selected domain, and run-001 and the path with a unique case run:

~~~sh
bbot -t example.test -p subdomain-enum -rf passive \
  -n run-001 -o /absolute/case/bbot -y
~~~

-y suppresses CLI confirmation only for the already selected operation.
The passive filter applies to modules; it does not promise an absence of DNS,
external API requests, dependency checks or other preparation traffic.
Do not run this example for a strictly offline task.
A domain target can include subdomains; check --strict-scope for an exact host.
Set exclusions with -b according to the installed version's help.

## Version differences

| Question | 2.x | 3.x |
|---|---|---|
| -s | silent | seeds; silent moved to -S |
| -w / --whitelist | separate scope | removed; scope is set by -t |
| Seeds | usually -t | -s/--seeds; defaults to targets when omitted |
| Structured event | data may be an object | data_json holds structured data |
| Output | check installed help | -o / --output-dir; short -o does not infer a filename |

Do not copy obsolete --allow-deadly flags or heavy preset names. Inspect
--current-preset or --dry-run before a long execution, but do not treat dry-run
as a guarantee of no setup/dependency effects. Do not print full configurations
containing API keys.

BBOT saves results in the scan directory. Verify filenames and successful writes
from actual output. output.json is a stream of JSON objects, one per line.
A new execution normally gets a new name: reusing a name may append to old files.
Do not enable Slack/Discord/HTTP output modules for a local report.

## Sources

- [CLI 3.x](https://github.com/blacklanternsecurity/bbot/blob/a6fb827bb144cdb85b52e142a4d6e14ed5f94b69/bbot/scanner/preset/args.py)
- [Migration 2 to 3](https://github.com/blacklanternsecurity/bbot/blob/a6fb827bb144cdb85b52e142a4d6e14ed5f94b69/docs/migration/3.0_breaking_changes.md)
- [Output formats](https://github.com/blacklanternsecurity/bbot/blob/a6fb827bb144cdb85b52e142a4d6e14ed5f94b69/docs/scanning/output.md)
