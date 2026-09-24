# Legba: inputs and sessions

Use the installed version as the reference:

~~~sh
legba --version
legba --list-plugins
legba ssh --help
~~~

Example for an owned lab SSH service on loopback, using a prepared finite
username:password file. This shows command structure, not a default target:

~~~sh
legba ssh --target 127.0.0.1:2222 \
  --combinations /absolute/private/case/approved-pairs.txt \
  --concurrency 1 --rate-limit 1 --timeout 3000 --retries 1 \
  --session /absolute/private/case/run/session.json \
  --output /absolute/private/case/run/matches.jsonl --output-format jsonl
~~~

Verify these flags in the installed version first.
--timeout and --wait use milliseconds; --rate-limit uses requests/second.
--concurrency is the number of workers, not a cap on total attempts.
Also set the operation's overall deadline in termux_job_start.
--single-match is appropriate only when the user wants to stop at the first
match; otherwise it reduces coverage.

Explicit --username/--password support literals, files and special expressions;
omitted values may select a generator. Do not print passwords in commands,
tool calls, STATE or diagnostic logs. Before reading a file, verify that it is
the intended existing nonempty file, not a typo. A combinations file bounds
pairs; two wordlists may produce a Cartesian product.

A session automatically resumes progress and stores options/results, including
credentials. Do not change inputs between resumes: record file hashes.
Do not run two processes with one session. Start a new run/session after changes
to scope or the credential set. Do not claim that cancelling a local SSH wrapper
stopped the remote process.

JSONL records contain plugin, target, data and partial.
partial=true does not fully confirm credentials. Interpret even a full match
according to the plugin contract. Do not treat a connection error as a wrong
password or absence of matches as proof of coverage.

For HTTP, select a specific http.* plugin from current --list-plugins and inspect
its body/auth/matcher options; do not guess the form structure.
Set finite payload sets and scope separately for enumeration plugins.
Do not enable Legba's REST/MCP server for an ordinary Termux execution.

## Sources

- [CLI, inputs, sessions and output](https://legba.evilsocket.net/usage/)
- [HTTP plugins](https://legba.evilsocket.net/plugins/http/)
- [Source and installation](https://github.com/evilsocket/legba/tree/dab974b910d52babc4767d06e093b7b86e259604)
