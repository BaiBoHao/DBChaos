# Long Transaction Triplet

## Background

The project previously had a single long-transaction-style adversity case, `txn uncommitted_txn`, focused on lock holding. The new task was to extend DBChaos with three additional long-transaction-derived adversity cases, each targeting a different kernel subsystem while staying as orthogonal as possible.

## Added Cases

- `txn deadlock_storm`
  - Targets transaction and concurrency control.
  - Focuses on cross-lock waiting patterns and deadlock detector overload.
- `storage mvcc_bloat`
  - Targets the storage engine.
  - Uses snapshot anchors plus high-frequency updates to pin visibility horizons and accumulate old versions.
- `exec read_amp_trap`
  - Targets the execution engine.
  - Builds bloat first, then uses scan threads to amplify visibility checks and read amplification.

## Integration

- Added new injector classes:
  - `DeadlockStormInject`
  - `MvccBloatInject`
  - `ReadAmpTrapInject`
- Updated `Main.java` routing and help catalogs.
- Updated root `README.md`.
- Updated `scripts/generate_configs.py` and `scripts/README.md` so the new cases appear in generated upstream configuration.

## Validation

- `javac --release 8 ...`
- case help:
  - `txn deadlock_storm --help`
  - `storage mvcc_bloat --help`
  - `exec read_amp_trap --help`
- `python -m py_compile scripts/generate_configs.py`
- `python generate_configs.py --select all`
- verified generated config contains:
  - `deadlock_storm`
  - `mvcc_bloat`
  - `read_amp_trap`

## Notes

- Generated XML outputs were refreshed only for validation and are not intended for commit.
