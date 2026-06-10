# Probe and Config Layout

## Background

The goal was to add one more practical script under `scripts/` and reorganize the directory so it no longer looked cluttered. The chosen addition was a reusable preflight checker for connectivity, jar readiness, and optional demo prerequisites.

## Changes

- Moved probe scripts into `scripts/probe/`:
  - `check_db_connection.sh`
  - `check_db_connection.ps1`
- Added:
  - `scripts/probe/preflight_check.sh`
- Kept `scripts/README.md` as a short entry index.
- Organized config-generator assets under `scripts/config_generator/`:
  - `template/`
  - `output/`
- Updated `generate_configs.py` defaults to use the new `template/` and `output/` layout.
- Updated path references in:
  - root `README.md`
  - `demo/README.md`
  - `demo/demo.sh`
  - `scripts/config_generator/README.md`
  - `.gitignore`

## Validation

- `./scripts/probe/check_db_connection.sh`
- `./scripts/probe/preflight_check.sh --with-demo`
- `cd scripts/config_generator && python3 -m py_compile generate_configs.py`
- `cd scripts/config_generator && python3 generate_configs.py --select plan_flip,memory_pressure,max_prepared`

## Notes

- The server-side `DBChaos-0.0.1.jar` local modification was intentionally left out of the commit.
