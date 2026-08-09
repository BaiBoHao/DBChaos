# DBChaos 配置生成脚本

`generate_configs.py` 用于把 DBChaos 已实现的不利能力转换为 TPC-C / ChaosBlade 所需的 XML 配置。

当前注入命令结构为：

```text
java -jar DBChaos-0.0.1.jar [--db <DB_TYPE>] <SUBSYSTEM> <CASE> [OPTIONS]
```

候选故障点不再来自表格文件，而是统一读取：

```text
resources/registry/registry.json
```

## 默认模板与输出

默认模板：

- `template/opengauss_tpcc_config_chaosblade.xml`
- `template/tpcc_worker.xml`
- `template/fault-cases-generic.xml`

默认输出：

- `output/opengauss_dbchaosTpcc_config_chaosblade.xml`
- `output/dbchaosTpcc_worker.xml`
- `output/fault-cases-generated.xml`

如果模板文件不存在，脚本会自动初始化最小骨架；如果输出文件不存在，脚本会自动创建。

## 常用命令

建议在 `scripts/config_generator/` 目录下执行：

```bash
python3 generate_configs.py --list
python3 generate_configs.py --interactive
python3 generate_configs.py --select all
python3 generate_configs.py --select plan_flip,memory_pressure,max_prepared
```

也可以在仓库根目录执行：

```bash
python3 scripts/config_generator/generate_configs.py --select all
```

## 常用参数

| 参数 | 说明 |
| --- | --- |
| `--select` | 最终启用的故障点，支持 key、生成 ID、序号或 `all` |
| `--interactive` | 交互式选择最终启用的故障点 |
| `--suite-name` | 生成的 `testSuite` 名称，默认 `dbchaos-generated-suite` |
| `--planning-start-sec` | 第一个故障开始注入的时间点，默认 120 秒 |
| `--planning-step-sec` | 多个故障之间的注入间隔，默认 80 秒 |
| `--during-sec` | 每个故障持续时间，默认 60 秒 |
| `--worker-time` | worker 总运行时长，默认 `auto` |
| `--db-type` | 覆盖 DBChaos 的数据库类型，默认读取 `resources/db.properties:type` |
| `--agent` | 覆盖注入目标 agent，例如 `master:8000` |

## 高级覆盖参数

仅在需要替换模板名、输出名或命令路径时使用：

- `--template-config`
- `--template-worker`
- `--template-suites`
- `--output-dir`
- `--output-config`
- `--output-worker`
- `--output-suites`
- `--worker-include-href`
- `--java-cmd`
- `--jar-path`
- `--no-db-overrides`
