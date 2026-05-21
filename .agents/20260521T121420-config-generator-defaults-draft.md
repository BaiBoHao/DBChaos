# Config Generator Defaults Draft

## Background

The user requested that `scripts/generate_configs.py` become easier to use by default: template and output files should be configured in the script, command length should be reduced, and the JSON selection-file flow should be removed.

## Changes

- Removed the JSON selection-file path from the script design.
- Added built-in default template names:
  - `opengauss_tpccbbh_config_chaosblade.xml`
  - `tpccbbh-worker.xml`
  - `fault-cases-generic.xml`
- Added built-in default output names:
  - `opengauss_tpcc_config_chaosblade.xml`
  - `tpcc_worker.xml`
  - `fault-cases-generated.xml`
- Kept advanced override parameters, but moved them into a secondary role.
- Rewrote `scripts/README.md` around the default workflow:
  - `--list`
  - `--interactive`
  - `--select all`
  - `--select <cases>`

## Validation

- Local Python environment detected successfully: `Python 3.13.3`
- `python -m py_compile scripts/generate_configs.py` passed.
- Executed:
  - `python generate_configs.py --select all`
- Result:
  - default config and worker templates were bootstrapped
  - suite template reused local `fault-cases-generic.xml`
  - generated output files were written successfully

## Notes

- This remains an uncommitted local draft.
