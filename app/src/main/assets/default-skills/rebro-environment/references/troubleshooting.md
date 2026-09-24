# Diagnosis: symptom → next experiment

| Symptom | Check first | Do not do automatically |
|---|---|---|
| zipalign absent | PATH, `dpkg-query -L aapt`, package policy | Download the first APK signing bundle |
| exec format error / missing linker | `file`, ELF interpreter, ABI/Bionic | chmod 777 or run through su |
| Connection refused :27044 | Exact endpoint, root socket read, known service session | Start a second server on another port |
| Health OK, attach fails | Current PID/user, exact error, crash/SELinux evidence | Declare incompatibility from version numbers alone |
| Java undefined | Bridge in the same script, bundle/loader type | Reinstall frida-python |
| Java unavailable | Native-only process, wrong PID, startup stage | Declare a broken bridge without a native probe |
| Java class not found | Split, class loader, process, obfuscation | Dump all methods/the whole heap |
| Hook installed, no events | Trigger action, overload, loader, JIT/inlining hypothesis | Assert the function is unused |
| Module missing | Lazy loading, module path/name, split | Poll forever without a deadline |
| JNI/native hook crash | Address/prototype/ABI, crash log, build ID | Disable SELinux or patch PAC speculatively |
| JADX killed without Java exception | Resources, memory pressure, Android kill logs | Increase heap to all MemAvailable |
| Job dispatched, no result | Job ID/cursors, terminal state, artifact | Repeat a command with side effects |
| Old PID exists after reboot | Boot ID and process identity | Signal a stale PID |
| APK verify OK, install fails | Certificate, version, user, full splits, manifest | Uninstall / clear data |
| Install fails with `.so` | ZIP and ELF alignment, ABI, page size | Treat zipalign as ELF relinking |
| Root read denied | Actual SELinux context and AVC | Global permissive mode |

## Minimal evidence

Record UTC time, Android boot ID, target package/user/PID, UI action, exact command
without secrets, exit code, stderr and expected artifact. For Frida: baseline
manifest + agent hash + runtime version. For APKs: input/output hash, certificate
digest and split manifest. For crashes: the relevant process's available crash
buffer/tombstone, not every app's crash reports.

```sh
su -c 'logcat -b crash -d -t 200'
su -c 'dumpsys thermalservice'
su -c 'dumpsys battery'
cat /proc/pressure/memory
```

Choose a filter for the selected process/time first. Snapshots may contain other
apps' data; select relevant lines before reporting. If freezer state is unknown,
read the target PID's current cgroup path and then its state. Do not retain a path
containing historical PID 26048 as permanent configuration.

## Experiment rollback

Stop the owned managed job, unload the owned script/session, preserve partial logs
and state what was verified. Take a second native probe on the lab target.
Do not change the baseline or restart the service merely to get a clean log.

## Storage

Separate input/evidence from reproducible work/output/cache. Start with
`du -h -d 2 "$HOME/rebro/cases"`; do not delete from directory names alone.
Candidates include duplicate decompilations, old temporary build trees and completed
logs. Preserve original APKs, full baseline, patches, experiment results and manifests.
Archiving on the same partition also needs free space. Do not automatically archive
16 GB when available space cannot cover both the archive and current work.
