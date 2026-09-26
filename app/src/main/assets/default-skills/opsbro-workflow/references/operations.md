# Operations playbook

## First observation

Record target identity, time, working root and user privileges. Collect only evidence related to the symptom. Avoid dumping environment variables, complete process command lines, home directory listings or entire config files: these commonly contain credentials.

For a failed service distinguish: invalid config; missing executable/dependency; permission; occupied port; exhausted disk/memory; unavailable remote dependency; incorrect working directory; lifecycle manager. Query the actual manager present on the target. Termux foreground/background lifecycle and Android battery policy differ from server service management.

## Changes and rollback

A useful change record includes original file hash/path, exact changed lines or setting, restore command, expected health signal and failure condition triggering rollback. Keep permissions/ownership when restoring. A backup containing secrets needs private storage and explicit treatment; do not produce a world-readable ZIP or paste it to a chat.

Use dry-run/validation modes where supported. They are not guaranteed side-effect free: read the command documentation and project hooks. Keep an existing SSH session usable when changing network/SSH configuration; do not close the only recovery route.

## Backup verification

Select data and exclusions. Establish retention and storage limits. Test listing/readability and restore into a temporary isolated destination when execution is authorized. Check expected files and integrity. Never overwrite production data merely to test a restore. Report whether restoration was performed or only archive readability was checked.

## Evidence format

Target | symptom | observation | proposed/actual change | check | rollback | remaining limitation.
For every check retain timestamp and exit status, identify the relevant output and redact secrets. A returned SSH error belongs to connection setup until evidence shows the remote command actually ran.
