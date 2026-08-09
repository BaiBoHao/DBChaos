# Demo Alignment Draft

## Background

The project CLI and script layout changed significantly, so the `demo/` directory needed a pass to ensure its README and shell script still matched the current DBChaos command structure and helper scripts.

## Changes

- Updated `demo/demo.sh`
  - now runs `../scripts/check_db_connection.sh` before attempting injection
  - now uses the new CLI form:
    - `java -jar "$JAR_PATH" --db "$DB_TYPE" <SUBSYSTEM> <CASE> [OPTIONS]`
  - now checks that the generated JAR exists before continuing
- Updated `demo/README.md`
  - build instructions now point to `./build_for_linux.sh`
  - usage examples now use subsystem-first commands
  - runtime flow now includes the database connection probe step

## Validation

- Static path and command review completed.
- The local Windows environment could not run `bash -n demo.sh` because no usable WSL/Git Bash runtime was available.
- Manual text scan confirmed the demo instructions now reference:
  - `../scripts/check_db_connection.sh`
  - `session max_connection`
  - `txn uncommitted_txn`
  - `sql plan_flip`

## Notes

- BenchmarkSQL presence was intentionally not verified in this step.
- This remains an uncommitted local draft.
