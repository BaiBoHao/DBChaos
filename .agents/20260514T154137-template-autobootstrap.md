# Template Auto-Bootstrap

## Background

The user ran `scripts/generate_configs.py` from `/home/baibh/DBChaos/scripts` with template filenames that did not exist in that directory. The previous script required all three template paths to exist and failed immediately with `ERROR: template-config not found`.

## Actions

- Changed template path handling from strict existence checks to optional loading.
- If `--template-config` is missing, the script now bootstraps a default `<parameters>` config skeleton.
- The default config skeleton reads `type`, `url`, `user`, and `password` from `resources/db.properties` when available.
- If `--template-worker` is missing, the script now bootstraps a default `<works>` skeleton.
- If `--template-suites` is missing, the script now bootstraps a default `<testSuites>` skeleton.
- Added clear `INFO:` messages so the terminal shows when fallback initialization is happening.
- Updated `scripts/README.md` to explain both the original failure reason and the new fallback behavior.

## Validation

- Re-ran the user's original command from `/home/baibh/DBChaos/scripts`.
- Confirmed the script prints the three `INFO:` fallback messages and generates:
  - `opengauss_tpcc_config_chaosblade.xml`
  - `tpcc_worker.xml`
  - `fault-cases-generic.xml`
- `python3 -m py_compile scripts/generate_configs.py` passed.

## Notes

- Generated XML output files remain ignored by `.gitignore`.
