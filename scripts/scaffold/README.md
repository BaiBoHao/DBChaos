# Scaffold

该目录用于快速新增一个 DBChaos 不利 Case，并自动补齐对应的 JSON 注册信息。

## 提供内容

- `new_case.py`
  脚手架主程序，负责：
  - 生成新的 Java 注入器类
  - 追加 `resources/registry/registry.json` 中的 Case 定义
  - 可选追加配置生成器使用的 `generatorProfiles`

- `new_case.sh`
  Linux / macOS 包装脚本

- `new_case.ps1`
  Windows PowerShell 包装脚本

- `../../templates/CaseInject.java.template`
  Java 注入器模板

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

## 如果还要进入配置生成链路

如果新 Case 还需要出现在 `scripts/config_generator/generate_configs.py` 的候选列表中，需要补充生成器信息：

- `--generator-id`
- `--generator-key`
- `--generator-description`
- `--generator-category`
- `--generator-args`
- `--generator-during-sec`

这样脚手架会把这些信息一并写入 `resources/registry/registry.json` 的 `generatorProfiles` 字段中。

## 结果

执行完成后，一个新 Case 的最小落地会被收敛为两部分：

1. 自动生成注入器实现类
2. 自动补齐 JSON 注册元数据

后续 CLI、帮助页和配置生成器都会基于这份注册信息自动联动。
