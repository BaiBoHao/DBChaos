# Windows Build Helper Draft

## Background

The user wants a way to preview the newly rewritten CLI and Main help output from the local Windows checkout at `E:\DBChaos`, without depending on the Linux build flow.

## Changes

- Added `build_for_win.sh`.
- Added `build_for_win.ps1` for the current local PowerShell workflow.
- The script supports:
  - `build`: compile Java sources, merge dependencies from `lib/`, and create a fat jar
  - `preview-help`: compile sources and directly run `chaos.Main --help`
  - `build-and-help`: build the jar and immediately run `--help`
- The PowerShell script mirrors the same commands for native Windows terminal use.

## Notes

- This is a draft change only.
- No commit has been created yet.
