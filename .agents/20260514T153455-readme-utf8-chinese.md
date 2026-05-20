# README UTF-8 Chinese Rewrite

## Background

The previous README corruption was caused by file content degradation, not only by editor display issues. An ASCII rewrite solved the corruption, but the user explicitly requires a Chinese README.

## Actions

- Rewrote `scripts/README.md` in Chinese.
- Used a byte-safe UTF-8 write path: UTF-8 content was encoded before transport and written on the remote server as raw bytes.
- Kept the current script name `generate_configs.py` and current server-side usage examples.

## Validation

- `file -bi scripts/README.md` now reports `charset=utf-8`.
- Verified the first lines can be read back as Chinese text.
- Verified the content no longer contains the previous question-mark placeholders.

## Notes

- This change was made directly in `/home/baibh/DBChaos` on branch `20260514`.
