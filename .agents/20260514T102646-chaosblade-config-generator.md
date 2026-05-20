# ChaosBlade XML Generator

## Background

The user wants DBChaos to inject adverse database conditions during TPC-C benchmark execution and integrate with the upstream runner through `opengauss_tpccbbh_config_chaosblade.xml` as the bridge configuration. The final enabled cases are controlled by `fault-cases-generic.xml` testSuite entries, while `tpcc_worker.xml` references the suite name.

## Implementation

- Added `scripts/generate_chaosblade_configs.py`.
- The generator registers all implemented DBChaos fault points as fault cases:
  - `plan_flip`
  - `max_connection` modes: `conn_storm`, `conn_exhaustion`, `thread_saturation`
  - `uncommitted_txn`
  - `duplicate_txn` modes: `UPDATE`, `INSERT`
  - `stack_overflow` modes: `func_recurse`, `proc_recurse`, `trans_recurse`, `sql_depth`, `view_nest`, `join_bomb`
  - `massive_rollback`
  - `memory_pressure`
  - `max_prepared`
- The generator writes:
  - `scripts/opengauss_tpcc_config_chaosblade.xml`
  - `scripts/tpcc_worker.xml`
  - `scripts/fault-cases-generic.xml`
- Generated XML files are ignored because they inherit runtime database connection details from the provided template.
- Selection supports CLI `--select`, JSON `--selection-file`, and `--interactive`.

## CLI Compatibility Fixes

- `Main.java` now routes `memory`, `memory_pressure`, `max_prepared`, and `duplicate_txn`.
- Per-fault `-h/--help` is now dispatched to the specific injector instead of always showing global help.
- `String.repeat` usage in `Main.java` was replaced with a Java 8-compatible helper.
- `DuplicateTxnInject` and `MaxPreparedInject` now provide help output and avoid executing real database work for `-h` or no args.

## Validation

- `python scripts/generate_chaosblade_configs.py --list` listed 16 DBChaos fault cases.
- Ran generator against the user's three XML templates with `--select all`.
- Parsed generated XML files successfully and verified:
  - 16 `<faultCases><case>` entries in the generated OpenGauss config.
  - `tpcc_worker.xml` references `dbchaos-generated-suite`.
  - `fault-cases-generic.xml` contains 16 selected suite cases, IDs `201` through `216`.
- `python -m py_compile scripts/generate_chaosblade_configs.py` passed.
- `javac --release 8 -encoding UTF-8 -cp lib/*` passed for all Java sources.
- Verified fault-level help for `duplicate_txn`, `max_prepared`, `memory`, and `memory_pressure`.

## Risks And Next Steps

- Generated XML contains DB connection details inherited from the template, so it remains ignored and should be reviewed before sharing.
- Maven package validation was not run because Maven is unavailable in the current shell.
- Some generated cases depend on the runtime placement of the DBChaos jar; default is `scripts/java/DBChaos-0.0.1.jar` and can be overridden with `--jar-path`.
