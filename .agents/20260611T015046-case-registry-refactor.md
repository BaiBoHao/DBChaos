# Case Registry Refactor

## Background

The project needed to move away from hardcoded case catalogs and scattered edits when adding a new adversity case. The target was a template-driven, registry-driven, automatically linked workflow while preserving existing functionality.

## Changes

- Added unified registry resources:
  - `resources/registry/subsystems.tsv`
  - `resources/registry/cases.tsv`
  - `resources/registry/generator_profiles.tsv`
- Added Java registry layer:
  - `src/chaos/registry/SubsystemDescriptor.java`
  - `src/chaos/registry/CaseDescriptor.java`
  - `src/chaos/registry/CaseRegistry.java`
- Refactored `src/chaos/Main.java` to use `CaseRegistry` for:
  - subsystem list
  - case list
  - case matching
  - injector creation
  - example generation
  - mode constraint handling
- Refactored `scripts/config_generator/generate_configs.py` to read generator profiles from `resources/registry/generator_profiles.tsv` instead of hardcoded `FAULT_SPECS`.
- Added scaffolding support:
  - `templates/CaseInject.java.template`
  - `scripts/scaffold/new_case.py`
  - `scripts/scaffold/new_case.sh`
  - `scripts/scaffold/new_case.ps1`
- Updated docs:
  - `README.md`
  - `scripts/README.md`
  - `scripts/scaffold/README.md`

## Validation

- `javac --release 8 ...`
- `java ... chaos.Main --help`
- `java ... chaos.Main buffer memory --help`
- `python -m py_compile scripts/config_generator/generate_configs.py`
- `python scripts/config_generator/generate_configs.py --select plan_flip,memory_pressure,max_prepared`
- scaffold smoke test with a temporary generated case, then cleanup

## Notes

- Existing CLI structure remains:
  - `[--db <DB_TYPE>] <SUBSYSTEM> <CASE> [OPTIONS]`
- Existing injectors remain functional.
- Generated XML outputs stay outside versioned changes.
