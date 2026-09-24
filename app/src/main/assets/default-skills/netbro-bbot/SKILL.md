---
name: netbro-bbot
description: "Use BBOT for domain and network asset reconnaissance: select presets/modules, handle BBOT 2/3 differences, scope and dependencies, parse JSON events and hand verified targets to the next tool."
---

# BBOT

Check the version in the selected environment and read
[CLI and compatibility](references/operations.md).
Use netbro-environment for installation.

1. Take the established scope from the case, not from a discovered event.
2. Inspect available presets/modules and the selected preset's contents.
3. Choose a narrow set: domain reconnaissance does not require kitchen-sink.
4. Check dependencies and effective configuration. Inspecting a preset does not
   replace checking individual modules' side effects.
5. Set a new scan name, an explicit output-dir in the case, and a finite job deadline.
6. Verify completion, errors/skipped modules and saved events.
7. Pass only relevant, scope-filtered targets to the next stage.

Preserve output.json and logs; read them in bounded pages. For aggregates, load
netbro-workflow and run its summarize.py bbot with the actual file path.
Do not extract structured fields with regexes over ANSI terminal output.
