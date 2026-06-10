# JSON Registry Migration

## 背景

用户认为当前基于 TSV/表格的 Case 注册方式不适合日常配置和扩展，希望替换为更直观的 JSON 方案，并要求补一条 `quota/test_case` 记录验证联动是否真正生效。

## 本次修改

- 将 `resources/registry/subsystems.tsv`、`cases.tsv`、`generator_profiles.tsv` 收敛为单一文件：
  - `resources/registry/registry.json`
- 保留原有 CLI 行为和配置生成逻辑，但改为统一从 JSON 读取。
- 新增 Java 侧最小 JSON 解析器：
  - `src/chaos/registry/SimpleJsonParser.java`
- 重写 `src/chaos/registry/CaseRegistry.java`：
  - 改为读取 `registry.json`
  - 保持 `Main.java` 现有调用接口不变
- 重写 `scripts/scaffold/new_case.py`：
  - 改为向 `registry.json` 追加 Case
  - 支持把 `generatorProfiles` 一并写入 JSON
- 更新配置生成器：
  - `scripts/config_generator/generate_configs.py`
  - 改为从 `registry.json` 的 `generatorProfiles` 提取候选故障点
- 更新说明文档：
  - `README.md`
  - `scripts/README.md`
  - `scripts/config_generator/README.md`
  - `scripts/scaffold/README.md`

## 联动验证

使用新的脚手架直接新增：

- 子系统：`quota`
- Case：`test_case`
- 注入器：`src/chaos/inject/TestCaseInject.java`
- 生成器 profile：
  - `id=220`
  - `key=test_case`

验证结果：

1. `python -m py_compile scripts/config_generator/generate_configs.py scripts/scaffold/new_case.py`
   - 通过
2. `powershell -ExecutionPolicy Bypass -File build_for_win.ps1 preview-help`
   - 通过，顶层帮助页已展示 `quota -> test_case`
3. `java -cp target\\win-build\\classes;lib\\* chaos.Main quota --help`
   - 通过，子系统帮助页自动展示 `test_case`
4. `java -cp target\\win-build\\classes;lib\\* chaos.Main quota test_case --help`
   - 通过，Case 帮助页可正常联动
5. `python scripts/config_generator/generate_configs.py --list`
   - 通过，列表出现 `id=220 key=test_case`
6. `python scripts/config_generator/generate_configs.py --select test_case --output-dir target/config-smoke`
   - 通过，成功生成只包含 `test_case` 的 XML 输出

## 风险与说明

- 当前 JSON 方案没有引入第三方依赖，避免破坏现有 `build_for_win.ps1` 的本地编译链。
- `TestCaseInject` 目前是验证联动用的占位 Case，具备帮助页和最小执行骨架，但没有实际注入逻辑。
