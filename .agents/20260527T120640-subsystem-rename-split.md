# Subsystem Rename Split

## Background

The user requested two taxonomy refinements for the local `E:\DBChaos` project:

- rename `执行引擎与运行时` to `执行引擎`
- split `存储引擎与缓冲管理` into two first-level kernel subsystems:
  - `存储引擎`
  - `缓冲管理`

## Changes

- Updated `src/chaos/Main.java`:
  - `exec` display name is now `执行引擎`
  - `storage` display name is now `存储引擎`
  - added `buffer` as an independent subsystem
  - `memory_pressure` now belongs to `buffer`
- Updated `src/chaos/inject/MemoryPressureFault.java` help examples to use `buffer memory_pressure`
- Updated root `README.md` subsystem descriptions and case grouping
- Updated `scripts/generate_configs.py` so generated commands use `buffer memory_pressure`
- Updated `scripts/README.md` to stay consistent with the new subsystem layout

## Validation

- `javac --release 8 ...`
- top-level CLI help preview
- `python -m py_compile scripts/generate_configs.py`
- regenerated local XML outputs
- verified generated config contains `<arg>buffer</arg>` for `memory_pressure`

## Notes

- This work only touched the local `E:\DBChaos` checkout.
- Generated XML outputs were refreshed for validation but are not part of the intended commit.
