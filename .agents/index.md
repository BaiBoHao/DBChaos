# DBChaos Agent Index

## Project

- Project root: `E:\DBChaos`
- Project UUID: `27f12b1d-6d86-4c59-a60b-509583178478`
- Main branch: `main`
- Current working branch: `20260520`
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
- [Merge 20260514 into main](20260520T173656-merge-20260514-into-main.md) - Merged the 20260514 branch work into main while preserving main's root README and lib deletion state.
- [Finalize main merge](20260520T174220-finalize-main-merge.md) - Integrated the latest remote main updates after merging 20260514 and completed validation before pushing main.
- [Local branch 20260520 setup](20260520T220130-local-branch-20260520.md) - Created local branch `20260520` from the latest local main so subsequent work can continue in the E drive checkout.
- [README refactor draft](20260520T232303-readme-refactor-draft.md) - Rewrote the root README draft around subsystem-oriented adversity categories and resilience-boundary positioning.
- [CLI copy draft](20260520T233418-cli-copy-draft.md) - Rewrote the CLI banner and help copy around kernel subsystems, entry keywords, and cleaner examples without changing injection behavior.
- [Windows build helper draft](20260521T000100-win-build-helper-draft.md) - Added a local `build_for_win.sh` helper to compile, package, and preview the CLI help output on Windows.
- [Subsystem command model draft](20260521T002020-subsystem-command-model-draft.md) - Switched the local CLI draft to `[--db <DB_TYPE>] <SUBSYSTEM> <CASE>` and aligned help, docs, and config generation around the new command model.
- [CLI subsystem final draft](20260521T115025-cli-subsystem-final-draft.md) - Finalized the local draft around subsystem-first CLI, cleaner help text, and Windows local build helpers before commit.
