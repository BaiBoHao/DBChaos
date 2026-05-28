# Scripts Directory Reorg

## Background

The user requested a cleanup of the project layout:

- rename the original Linux packaging script from `build.sh` to `build_for_linux.sh`
- remove `build_for_win.sh`, since it is not actually used in the local Windows workflow
- move database connection probe scripts under `scripts/`
- move `generate_configs.py` and its related files into a dedicated subdirectory under `scripts/`

## Changes

- Renamed:
  - `build.sh` -> `build_for_linux.sh`
- Removed:
  - `build_for_win.sh`
- Reorganized `scripts/`:
  - `scripts/check_db_connection.ps1`
  - `scripts/check_db_connection.sh`
  - `scripts/README.md` now acts as a short index
  - `scripts/config_generator/` now contains:
    - `generate_configs.py`
    - `README.md`
    - local template/output XML files
- Updated path references in:
  - root `README.md`
  - `demo/README.md`
  - `scripts/config_generator/README.md`
  - `.gitignore`

## Validation

- `scripts/check_db_connection.ps1` runs from the new location.
- `scripts/config_generator/generate_configs.py --select plan_flip,memory_pressure,max_prepared` runs from the new location.
- The generator now resolves `resources/db.properties` correctly after the subdirectory move.

## Notes

- This remains an uncommitted local draft.
