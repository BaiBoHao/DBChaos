# DBChaos ChaosBlade Config Generator

`generate_chaosblade_configs.py` 用于把 DBChaos 已实现的不利注入能力转换成 TPC-C 上游可消费的 ChaosBlade XML 配置。

它会生成三份文件：

- `opengauss_tpcc_config_chaosblade.xml`：主配置，包含所有 DBChaos `<faultCases>`。
- `tpcc_worker.xml`：TPC-C worker 配置，指定最终使用的 `testSuite`。
- `fault-cases-generic.xml`：最终生效的不利注入实例，`<testSuite>` 在这里配置。

生成文件会继承模板中的数据库连接信息，默认已被 `.gitignore` 忽略，避免误提交敏感配置。

## 快速开始

先查看当前支持的不利画像：

```bash
python scripts/generate_chaosblade_configs.py --list
```

生成全部 DBChaos 不利画像：

```bash
python scripts/generate_chaosblade_configs.py \
  --template-config "/path/to/opengauss_tpccbbh_config_chaosblade.xml" \
  --template-worker "/path/to/tpccbbh-worker.xml" \
  --template-suites "/path/to/fault-cases-generic.xml" \
  --select all
```

只选择部分不利画像：

```bash
python scripts/generate_chaosblade_configs.py \
  --template-config "/path/to/opengauss_tpccbbh_config_chaosblade.xml" \
  --template-worker "/path/to/tpccbbh-worker.xml" \
  --template-suites "/path/to/fault-cases-generic.xml" \
  --select plan_flip,memory_pressure,max_connection_conn_storm
```

交互式选择：

```bash
python scripts/generate_chaosblade_configs.py \
  --template-config "/path/to/opengauss_tpccbbh_config_chaosblade.xml" \
  --template-worker "/path/to/tpccbbh-worker.xml" \
  --template-suites "/path/to/fault-cases-generic.xml" \
  --interactive
```

## 常用参数

| 参数 | 说明 |
| --- | --- |
| `--template-config` | 原始 OpenGauss TPC-C ChaosBlade 主配置模板。 |
| `--template-worker` | 原始 TPC-C worker XML 模板。 |
| `--template-suites` | 原始 `fault-cases-generic.xml` 模板。 |
| `--output-dir` | 输出目录，默认是 `scripts/`。 |
| `--select` | 最终启用的不利画像，支持 key、生成 ID、序号或 `all`。 |
| `--interactive` | 交互式选择最终启用的不利画像。 |
| `--suite-name` | 生成的最终 `testSuite` 名称，默认 `dbchaos-generated-suite`。 |
| `--planning-start-sec` | 第一个不利开始注入的时间点，默认 120 秒。 |
| `--planning-step-sec` | 多个不利之间的注入间隔，默认 80 秒。 |
| `--during-sec` | 每个不利持续时间，默认 60 秒。 |
| `--worker-time` | worker 总运行时长，默认 `auto`。 |
| `--java-cmd` | 上游执行 DBChaos 时使用的 Java 命令，默认 `/opt/java-21/bin/java`。 |
| `--jar-path` | 上游机器上的 DBChaos jar 路径，默认 `scripts/java/DBChaos-0.0.1.jar`。 |
| `--agent` | 注入目标 agent，例如 `master:8000`，可重复传入。 |
| `--no-db-overrides` | 不把模板中的 `url/user/password` 作为 DBChaos 命令参数传入。 |

## 支持的不利画像 key

| key | 含义 |
| --- | --- |
| `plan_flip` | 执行计划跳变。 |
| `max_connection_conn_storm` | 连接风暴。 |
| `max_connection_conn_exhaustion` | 连接耗尽。 |
| `max_connection_thread_saturation` | 数据库线程池饱和。 |
| `uncommitted_txn` | 长事务持锁。 |
| `duplicate_txn_update` | 热点行更新冲突。 |
| `duplicate_txn_insert` | 重复插入/唯一约束冲突。 |
| `stack_overflow_func_recurse` | 函数递归栈溢出。 |
| `stack_overflow_proc_recurse` | 存储过程递归栈溢出。 |
| `stack_overflow_trans_recurse` | 事务中的递归栈溢出。 |
| `stack_overflow_sql_depth` | 超深 SQL 表达式。 |
| `stack_overflow_view_nest` | 深度嵌套视图。 |
| `stack_overflow_join_bomb` | 多表 join 搜索压力。 |
| `massive_rollback` | 大规模事务回滚。 |
| `memory_pressure` | 大对象写入造成内存/缓冲压力。 |
| `max_prepared` | Prepared Transaction / XA Prepare 上限挤兑。 |

## JSON 选择文件

也可以把选择和时序写成 JSON：

```json
{
  "suite_name": "dbchaos-selected-suite",
  "cases": ["plan_flip", "memory_pressure", "max_prepared"],
  "planning_start_sec": 120,
  "planning_step_sec": 90,
  "during_sec": 60
}
```

执行：

```bash
python scripts/generate_chaosblade_configs.py \
  --template-config "/path/to/opengauss_tpccbbh_config_chaosblade.xml" \
  --template-worker "/path/to/tpccbbh-worker.xml" \
  --template-suites "/path/to/fault-cases-generic.xml" \
  --selection-file scripts/selection.json
```
