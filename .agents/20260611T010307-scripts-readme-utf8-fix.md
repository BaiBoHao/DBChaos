# Scripts README UTF-8 Fix

## Background

The server-side `scripts/README.md` had degraded into ASCII question-mark placeholders, so the file content itself was broken rather than merely displayed with the wrong encoding.

## Actions

- Replaced `/home/baibh/DBChaos/scripts/README.md` with the correct UTF-8 version from the local repository.
- Verified the file now reports `charset=utf-8`.

## Validation

- `file -bi scripts/README.md`
- manual readback of the first lines on the server

## Notes

- This commit intentionally excludes the local `DBChaos-0.0.1.jar` change still present in the server worktree.
