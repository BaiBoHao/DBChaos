# Scaffold

本目录用于快速新增一个不利 Case，而不是手工同步修改多处代码。

## 提供内容

- `new_case.py`
  脚手架主体，负责：
  - 生成新的 Java 注入器类
  - 追加 `resources/registry/cases.tsv`
  - 可选追加 `resources/registry/generator_profiles.tsv`

- `new_case.sh`
  Linux / macOS 包装脚本。

- `new_case.ps1`
  Windows PowerShell 包装脚本。

- `../../templates/CaseInject.java.template`
  Java 注入器模板。

## 最小用法

Linux / macOS:

```bash
./scripts/scaffold/new_case.sh \
  --subsystem txn \
  --case sample_case \
  --class-name SampleCaseInject \
  --title 示例不利 \
  --description 这是一个示例不利 \
  --example-args "-duration 60000"
```

Windows PowerShell:

```powershell
.\scripts\scaffold\new_case.ps1 `
  --subsystem txn `
  --case sample_case `
  --class-name SampleCaseInject `
  --title 示例不利 `
  --description 这是一个示例不利 `
  --example-args "-duration 60000"
```

## 如果还要进入配置生成链

如果新增的 Case 还需要出现在 `scripts/config_generator/generate_configs.py` 的候选列表中，需要额外提供生成器配置：

- `--generator-id`
- `--generator-key`
- `--generator-description`
- `--generator-category`
- `--generator-args`
- `--generator-during-sec`

这样脚手架会同时把新 Case 追加进：

- `resources/registry/cases.tsv`
- `resources/registry/generator_profiles.tsv`

## 结果

执行完成后，新增一个 Case 的最少工作会被收敛成两部分：

1. 自动生成实现类骨架
2. 自动补齐注册表元数据

后续 CLI、帮助页、配置生成会自动感知这条注册信息。
