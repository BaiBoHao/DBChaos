# DB Connection Probe Draft

## Background

The local `resources/db.properties` was updated, and the user requested a dedicated feature plus a runnable script to validate database connectivity before running any adversity injection.

## Changes

- Added Java feature:
  - `src/chaos/tools/DbConnectionProbe.java`
- Added runnable PowerShell script:
  - `check_db_connection.ps1`
- Added runnable shell script:
  - `check_db_connection.sh`
- Extended local build helpers with:
  - `check-db`
  - `build-and-check-db`
- Updated root `README.md` and `scripts/README.md` to document the connection probe workflow.

## Validation

- Executed:
  - `.\build_for_win.ps1 check-db`
- Result:
  - driver loading succeeded
  - network path to the configured database was reachable
  - authentication failed with:
    `FATAL: Invalid username/password, login denied.`

## Current Scope

- Windows:
  - `.\check_db_connection.ps1`
  - `.\build_for_win.ps1 check-db`
- Linux / shell:
  - `./check_db_connection.sh`
  - `./build_for_win.sh check-db`

## Notes

- The probe is working as intended because it surfaces the connection problem before any adversity injection starts.
- This remains an uncommitted local draft.
