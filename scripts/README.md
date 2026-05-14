# DBChaos 配置生成脚本

`scripts/generate_configs.py` 用于把 DBChaos 已实现的不利注入能力转换成上游 TPC-C 与 ChaosBlade 工作流需要的 XML 配置。

它会生成三份文件：

- `opengauss_tpcc_config_chaosblade.xml`：主配置文件，包含全部 DBChaos `<faultCases>`。
- `tpcc_worker.xml`：TPC-C worker 配置，指定最终使用的 `testSuite`。
- `fault-cases-generic.xml`：最终生效的不利注入实例，`<testSuite>` 在这里定义。

如果输出目标文件不存在，脚本会自动创建目标文件以及父目录。
如果传入的模板文件不存在，脚本也会自动初始化一个最小可用骨架，然后继续生成最终 XML。

自动初始化行为如下：

- `--template-config` 缺失时：自动生成主配置骨架，并优先从 `resources/db.properties` 读取 `type`、`url`、`user`、`password`。
- `--template-worker` 缺失时：自动生成一个基础 `<works>` 结构。
- `--template-suites` 缺失时：自动生成一个基础 `<testSuites>` 结构。

生成出来的 XML 可能包含从模板或 `resources/db.properties` 继承来的数据库连接信息，所以这些输出文件默认被 `.gitignore` 忽略。

## 为什么你刚才会报错

你执行命令时人在 `/home/baibh/DBChaos/scripts`，但目录里并没有这三个模板文件：

- `opengauss_tpccbbh_config_chaosblade.xml`
- `tpccbbh-worker.xml`
- `fault-cases-generic.xml`

旧版本脚本会把它们当作“必须已经存在的输入模板”，因此在最前面就报：

```text
ERROR: template-config not found: opengauss_tpccbbh_config_chaosblade.xml
```

现在这个行为已经修复。模板缺失时，脚本会自动初始化，再继续生成。

## 快速开始

建议在 `scripts/` 目录下执行：

```bash
cd /home/baibh/DBChaos/scripts
python3 generate_configs.py --list
```

如果你想在仓库根目录执行，也可以使用：

```bash
cd /home/baibh/DBChaos
python3 scripts/generate_configs.py --list
```

生成全部 DBChaos 故障点：

```bash
cd /home/baibh/DBChaos/scripts
python3 generate_configs.py \
  --template-config "opengauss_tpccbbh_config_chaosblade.xml" \
  --template-worker "tpccbbh-worker.xml" \
  --template-suites "fault-cases-generic.xml" \
  --select all
```

只生成部分故障点：

```bash
cd /home/baibh/DBChaos/scripts
python3 generate_configs.py \
  --template-config "opengauss_tpccbbh_config_chaosblade.xml" \
  --template-worker "tpccbbh-worker.xml" \
  --template-suites "fault-cases-generic.xml" \
  --select plan_flip,memory_pressure,max_connection_conn_storm
```

交互式选择故障点：

```bash
cd /home/baibh/DBChaos/scripts
python3 generate_configs.py \
  --template-config "opengauss_tpccbbh_config_chaosblade.xml" \
  --template-worker "tpccbbh-worker.xml" \
  --template-suites "fault-cases-generic.xml" \
  --interactive
```

## 常用参数

| 参数 | 说明 |
| --- | --- |
| `--template-config` | OpenGauss TPC-C ChaosBlade 主配置模板。缺失时自动生成默认骨架。 |
| `--template-worker` | TPC-C worker XML 模板。缺失时自动生成默认骨架。 |
| `--template-suites` | `fault-cases-generic.xml` 模板。缺失时自动生成默认骨架。 |
| `--output-dir` | 输出目录，默认是当前脚本所在目录。 |
| `--output-config` | 输出主配置文件名，默认 `opengauss_tpcc_config_chaosblade.xml`。 |
| `--output-worker` | 输出 worker 文件名，默认 `tpcc_worker.xml`。 |
| `--output-suites` | 输出 suite 文件名，默认 `fault-cases-generic.xml`。 |
| `--select` | 最终启用的故障点，支持 key、生成 ID、序号或 `all`。 |
| `--interactive` | 交互式选择最终启用的故障点。 |
| `--suite-name` | 生成的最终 `testSuite` 名称，默认 `dbchaos-generated-suite`。 |
| `--planning-start-sec` | 第一个故障开始注入的时间点，默认 120 秒。 |
| `--planning-step-sec` | 多个故障之间的注入间隔，默认 80 秒。 |
| `--during-sec` | 每个故障持续时间，默认 60 秒。 |
| `--worker-time` | worker 总运行时长，默认 `auto`。 |
| `--java-cmd` | 上游执行 DBChaos 时使用的 Java 命令，默认 `/opt/java-21/bin/java`。 |
| `--jar-path` | 上游机器上的 DBChaos jar 路径，默认 `scripts/java/DBChaos-0.0.1.jar`。 |
| `--agent` | 注入目标 agent，例如 `master:8000`，可重复传入。 |
| `--no-db-overrides` | 不把模板中的 `-url`、`-user`、`-password` 传给 DBChaos。 |

## 支持的故障 key

| key | 含义 |
| --- | --- |
| `plan_flip` | 执行计划跳变。 |
| `max_connection_conn_storm` | 连接风暴。 |
| `max_connection_conn_exhaustion` | 连接耗尽。 |
| `max_connection_thread_saturation` | 数据库线程池饱和。 |
| `uncommitted_txn` | 长事务持锁。 |
| `duplicate_txn_update` | 热点行更新冲突。 |
| `duplicate_txn_insert` | 重复插入或唯一约束冲突。 |
| `stack_overflow_func_recurse` | 函数递归栈溢出。 |
| `stack_overflow_proc_recurse` | 存储过程递归栈溢出。 |
| `stack_overflow_trans_recurse` | 事务中的递归栈溢出。 |
| `stack_overflow_sql_depth` | 超深 SQL 表达式。 |
| `stack_overflow_view_nest` | 深度嵌套视图。 |
| `stack_overflow_join_bomb` | 多表 join 搜索压力。 |
| `massive_rollback` | 大规模事务回滚。 |
| `memory_pressure` | 大对象写入引发的内存或缓冲压力。 |
| `max_prepared` | Prepared Transaction 或 XA Prepare 上限挤兑。 |

## JSON 选择文件

也可以把最终 suite 选择写成 JSON 文件：

```json
{
  "suite_name": "dbchaos-selected-suite",
  "cases": ["plan_flip", "memory_pressure", "max_prepared"],
  "planning_start_sec": 120,
  "planning_step_sec": 90,
  "during_sec": 60
}
```

配合 JSON 文件执行：

```bash
cd /home/baibh/DBChaos/scripts
python3 generate_configs.py \
  --template-config "opengauss_tpccbbh_config_chaosblade.xml" \
  --template-worker "tpccbbh-worker.xml" \
  --template-suites "fault-cases-generic.xml" \
  --selection-file "selection.json"
```