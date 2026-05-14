# README Encoding Fix

## Background

The user reported that `scripts/README.md` displayed many `?` characters. Inspection confirmed this was not a viewer-only problem: the file content itself had already been degraded to ASCII question marks.

## Actions

- Rewrote `scripts/README.md` completely.
- Kept the content ASCII-only on purpose to avoid future encoding corruption between local Windows tooling, SSH transport, and the remote Linux repository.
- Preserved the current script name `generate_configs.py` and the current server-side usage style.

## Validation

- `file -bi scripts/README.md` should now report ASCII-safe content.
- The file no longer contains the broken question-mark placeholders from the previous version.

## Notes

- This change was made directly in `/home/baibh/DBChaos` on branch `20260514`.
