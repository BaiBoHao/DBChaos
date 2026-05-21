# CLI Subsystem Final Draft

## Background

The user approved the root README direction and then requested a larger CLI restructuring so DBChaos no longer jumps directly from database type to a concrete fault case. The tool should first expose the first-level kernel subsystem and then the concrete case.

## Changes

- Switched the local CLI draft to:
  - `java -jar DBChaos-0.0.1.jar [--db <DB_TYPE>] <SUBSYSTEM> <CASE> [OPTIONS]`
- `--db` is optional and falls back to `resources/db.properties:type`.
- Added subsystem keywords:
  - `session`
  - `sql`
  - `exec`
  - `txn`
  - `storage`
  - `log`
  - `task`
  - `quota`
- Added top-level help, subsystem help, and case help.
- Rejected the old syntax path intentionally instead of preserving backward compatibility.
- Synchronized:
  - `resources/chaos.properties`
  - `README.md`
  - `scripts/README.md`
  - `scripts/generate_configs.py`
  - injector help examples
- Added local Windows build helpers:
  - `build_for_win.ps1`
  - `build_for_win.sh`

## Validation

- `javac --release 8 ...`
- `java ... chaos.Main --help`
- `java ... chaos.Main sql --help`
- `java ... chaos.Main txn duplicate_txn --help`
- legacy syntax rejection
- `stack_overflow` subsystem-mode validation
- `python -m py_compile scripts/generate_configs.py`
- `build_for_win.ps1 preview-help`

## Notes

- This remains uncommitted until the user confirms.
