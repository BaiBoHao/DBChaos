# DBChaos Agent Index

## Project

- Project root: `E:\DBChaos`
- Project UUID: `27f12b1d-6d86-4c59-a60b-509583178478`
- Main branch: `main`
- Current working branch: `20260514`
- Related project indexes: none recorded

## Reading Rules

- Use this file as the default entry point.
- Keep this index short and append-only.
- Read only the detail entries needed for the current task.
- Do not use root-level long progress files as default context.

## Entries

- [Initial project intake](20260513T213649-project-intake.md) - Bootstrapped `.agents` tracking, noted current branch and pre-existing dirty worktree before project reading.
- [High-level project reading](20260513T214020-project-reading.md) - Summarized DBChaos architecture, fault profiles, validation results, and initial routing/build risks.
- [Fault taxonomy mapping](20260513T214625-fault-taxonomy-mapping.md) - Classified currently implemented DBChaos fault profiles into the user's table categories.
- [ChaosBlade XML generator](20260514T102646-chaosblade-config-generator.md) - Added a DBChaos-to-TPC-C ChaosBlade XML generator and CLI routing fixes needed by generated cases.
- [Branch 20260514 remote setup](20260514T111836-branch-20260514.md) - Created branch `20260514` from the ChaosBlade generator work and pushed it to GitHub.
- [Scripts README](20260514T134542-scripts-readme.md) - Added usage documentation for `generate_chaosblade_configs.py` and its main parameters.
- [Generate configs rename](20260514T150535-generate-configs-rename.md) - Renamed the ChaosBlade generator to `generate_configs.py` and documented automatic creation of missing output files.
- [README encoding fix](20260514T153110-readme-encoding-fix.md) - Rewrote `scripts/README.md` as ASCII-only content to eliminate corrupted question-mark text.
- [README UTF-8 Chinese rewrite](20260514T153455-readme-utf8-chinese.md) - Rewrote `scripts/README.md` in Chinese with verified UTF-8 encoding using byte-safe remote write.
- [Template auto-bootstrap](20260514T154137-template-autobootstrap.md) - Updated `generate_configs.py` to auto-initialize missing template files instead of failing immediately.
