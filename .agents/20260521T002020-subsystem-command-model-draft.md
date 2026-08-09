# Subsystem Command Model Draft

## Background

The user requested a structural CLI change so DBChaos no longer jumps directly from database type to a concrete fault case. The new command model should reflect the approved first-level kernel subsystem taxonomy.

## Draft Changes

- Main CLI draft now uses:
  - `[--db <DB_TYPE>] <SUBSYSTEM> <CASE> [OPTIONS]`
- `--db` is optional and defaults to `resources/db.properties:type`.
- Supported subsystem keywords in the draft:
  - `session`
  - `sql`
  - `exec`
  - `txn`
  - `storage`
  - `log`
  - `task`
  - `quota`
- `task` and `quota` currently appear in help output as planned domains without independent executable cases.
- `stack_overflow` is now validated by subsystem:
  - `sql`: `sql_depth | view_nest | join_bomb`
  - `exec`: `func_recurse | proc_recurse | trans_recurse`

## Related Files

- `src/chaos/Main.java`
- `resources/chaos.properties`
- `README.md`
- `scripts/README.md`
- `scripts/generate_configs.py`
- several injector `printHelp()` methods for syntax alignment

## Validation

- `javac --release 8 ...`
- `python -m py_compile scripts/generate_configs.py`
- top-level help
- subsystem help
- case help
- legacy syntax rejection
- stack_overflow subsystem-mode validation

## Notes

- This remains an uncommitted local draft.
