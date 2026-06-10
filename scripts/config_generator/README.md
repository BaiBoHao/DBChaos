# DBChaos 配置生成脚本

`generate_configs.py` 用于把 DBChaos 已实现的不利注入能力转换成上游 TPC-C 与 ChaosBlade 工作流需要的 XML 配置。

当前生成的注入命令结构为：

```text
java -jar DBChaos-0.0.1.jar [--db <DB_TYPE>] <SUBSYSTEM> <CASE> [OPTIONS]
```

## 默认模板与输出

脚本默认读取以下模板文件：

- `template/opengauss_tpcc_config_chaosblade.xml`
- `template/tpcc_worker.xml`
- `template/fault-cases-generic.xml`

脚本默认输出以下结果文件：

- `output/opengauss_dbchaosTpcc_config_chaosblade.xml`
- `output/dbchaosTpcc_worker.xml`
- `output/fault-cases-generated.xml`

如果模板文件不存在，脚本会自动初始化最小骨架再继续生成。  
如果输出文件不存在，脚本会自动创建输出文件和父目录。

## 最常用命令

建议在 `scripts/config_generator/` 目录下执行：

```bash
cd /home/baibh/DBChaos/scripts/config_generator
python3 generate_configs.py --list
python3 generate_configs.py --interactive
python3 generate_configs.py --select all
python3 generate_configs.py --select plan_flip,memory_pressure,max_prepared
```

在执行不利注入前，建议先确认数据库连接是否成功。  
Windows 本地环境可在项目根目录执行：

```powershell
.\scripts\probe\check_db_connection.ps1
```

Linux / macOS 环境可在项目根目录执行：

```bash
./scripts/probe/check_db_connection.sh
```

如果你在仓库根目录执行，也可以使用：

```bash
cd /home/baibh/DBChaos
python3 scripts/config_generator/generate_configs.py --select all
```

## 常用参数

| 参数 | 说明 |
| --- | --- |
| `--select` | 最终启用的故障点，支持 key、生成 ID、序号或 `all`。 |
| `--interactive` | 交互式选择最终启用的故障点。 |
| `--suite-name` | 生成的 `testSuite` 名称，默认 `dbchaos-generated-suite`。 |
| `--planning-start-sec` | 第一个故障开始注入的时间点，默认 120 秒。 |
| `--planning-step-sec` | 多个故障之间的注入间隔，默认 80 秒。 |
| `--during-sec` | 每个故障持续时间，默认 60 秒。 |
| `--worker-time` | worker 总运行时长，默认 `auto`。 |
| `--db-type` | 覆盖 DBChaos 的数据库类型。默认读取 `resources/db.properties:type`。 |
| `--agent` | 覆盖注入目标 agent，例如 `master:8000`。 |

## 高级覆盖参数

通常不需要使用，只有在你要更换模板文件名或输出文件名时才需要：

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
| `deadlock_storm` | 交叉等待与死锁检测过载。 |
| `stack_overflow_func_recurse` | 函数递归栈溢出。 |
| `stack_overflow_proc_recurse` | 存储过程递归栈溢出。 |
| `stack_overflow_trans_recurse` | 事务中的递归栈溢出。 |
| `stack_overflow_sql_depth` | 超深 SQL 表达式。 |
| `stack_overflow_view_nest` | 深度嵌套视图。 |
| `stack_overflow_join_bomb` | 多表 Join 搜索压力。 |
| `read_amp_trap` | 膨胀后范围扫描带来的读取放大。 |
| `massive_rollback` | 大规模事务回滚。 |
| `memory_pressure` | 大对象写入引发的内存或缓冲压力。 |
| `max_prepared` | Prepared Transaction 或 XA Prepare 上限挤兑。 |
| `mvcc_bloat` | 快照钉住导致的 MVCC 版本膨胀。 |
