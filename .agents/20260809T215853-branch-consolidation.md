# Branch Consolidation

## 背景

用户确认保留远程 `zzk`，并要求其余本地和远程开发分支全部收敛到 `main`。

## 安全判断

- 删除前逐一使用提交祖先关系确认候选分支已被 `main` 包含。
- `origin/zzk` 的提交 `ca14e9e` 不在 `main` 中，按用户要求保留。
- 当前工作区未提交的 PPT UTF-8 修复记录先以提交 `e5821c1` 保存，再通过合并提交 `6372ffc` 纳入 `main`。

## 本次操作

- 删除本地已合并分支：
  - `20260610`
  - `20260630`
  - `backup/20260610-before-registry-refactor`
  - `codex/chaosblade-config-generator`
  - `codex/json-registry-migration`
  - `codex/project-intake-20260513`
- 删除远程已合并分支：
  - `origin/20260610`
  - `origin/20260630`
  - `origin/codex/json-registry-migration`
- 保留：
  - 本地 `main`
  - 远程 `origin/main`
  - 远程 `origin/zzk`

## 验证

- GitHub 实际 heads 已核对为 `main` 和 `zzk`。
- 本地长期分支已核对为 `main`。
- `main` 工作区干净并与 `origin/main` 对齐。

## 风险与恢复

- 已合并分支的提交仍可通过 `main` 历史访问。
- `zzk` 未删除，未合并提交保持可访问。
- PPT 记录已提交到 `main`，删除旧工作区副本不会造成数据丢失。
