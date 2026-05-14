# DBChaos Config Generator

This directory contains `scripts/generate_configs.py`.

The script converts DBChaos fault injections into the XML files expected by the upstream TPC-C + ChaosBlade workflow.

It generates three files:

- `opengauss_tpcc_config_chaosblade.xml`: main config with all DBChaos `<faultCases>`.
- `tpcc_worker.xml`: worker config that points to the selected `testSuite`.
- `fault-cases-generic.xml`: final suite instance used during the TPC-C run.

If an output file does not exist, the script creates the file and its parent directory automatically.
Generated XML may contain database connection settings inherited from the template, so these files are ignored by `.gitignore`.

## Quick Start

Run from `scripts/`:

```bash
cd /home/baibh/DBChaos/scripts
python3 generate_configs.py --list
```

Run from the repository root:

```bash
cd /home/baibh/DBChaos
python3 scripts/generate_configs.py --list
```

Generate all DBChaos cases:

```bash
cd /home/baibh/DBChaos/scripts
python3 generate_configs.py   --template-config "opengauss_tpccbbh_config_chaosblade.xml"   --template-worker "tpccbbh-worker.xml"   --template-suites "fault-cases-generic.xml"   --select all
```

Generate only selected cases:

```bash
cd /home/baibh/DBChaos/scripts
python3 generate_configs.py   --template-config "opengauss_tpccbbh_config_chaosblade.xml"   --template-worker "tpccbbh-worker.xml"   --template-suites "fault-cases-generic.xml"   --select plan_flip,memory_pressure,max_connection_conn_storm
```

Interactive selection:

```bash
cd /home/baibh/DBChaos/scripts
python3 generate_configs.py   --template-config "opengauss_tpccbbh_config_chaosblade.xml"   --template-worker "tpccbbh-worker.xml"   --template-suites "fault-cases-generic.xml"   --interactive
```

## Common Options

| Option | Meaning |
| --- | --- |
| `--template-config` | OpenGauss TPC-C ChaosBlade config template. |
| `--template-worker` | TPC-C worker XML template. |
| `--template-suites` | `fault-cases-generic.xml` template. |
| `--output-dir` | Output directory. Default is the current script directory. |
| `--output-config` | Output config filename. Default is `opengauss_tpcc_config_chaosblade.xml`. |
| `--output-worker` | Output worker filename. Default is `tpcc_worker.xml`. |
| `--output-suites` | Output suite filename. Default is `fault-cases-generic.xml`. |
| `--select` | Final selected fault cases. Supports keys, generated IDs, list numbers, or `all`. |
| `--interactive` | Select final cases interactively. |
| `--suite-name` | Final generated `testSuite` name. Default is `dbchaos-generated-suite`. |
| `--planning-start-sec` | Start time of the first case. Default is 120 seconds. |
| `--planning-step-sec` | Interval between cases. Default is 80 seconds. |
| `--during-sec` | Duration of each case. Default is 60 seconds. |
| `--worker-time` | Total worker run time. Default is `auto`. |
| `--java-cmd` | Java command used by the upstream runner. Default is `/opt/java-21/bin/java`. |
| `--jar-path` | DBChaos jar path on the upstream machine. Default is `scripts/java/DBChaos-0.0.1.jar`. |
| `--agent` | Target agent such as `master:8000`. Repeatable. |
| `--no-db-overrides` | Do not append `-url`, `-user`, and `-password` from the template. |

## Supported Case Keys

| Key | Meaning |
| --- | --- |
| `plan_flip` | Query plan flip. |
| `max_connection_conn_storm` | Connection storm. |
| `max_connection_conn_exhaustion` | Connection exhaustion. |
| `max_connection_thread_saturation` | Database thread pool saturation. |
| `uncommitted_txn` | Long transaction lock holding. |
| `duplicate_txn_update` | Hot-row update conflict. |
| `duplicate_txn_insert` | Duplicate insert or unique conflict. |
| `stack_overflow_func_recurse` | Function recursion stack overflow. |
| `stack_overflow_proc_recurse` | Procedure recursion stack overflow. |
| `stack_overflow_trans_recurse` | Transaction recursion stack overflow. |
| `stack_overflow_sql_depth` | Deep SQL expression stack overflow. |
| `stack_overflow_view_nest` | Nested view stack overflow. |
| `stack_overflow_join_bomb` | Join search stress. |
| `massive_rollback` | Massive transaction rollback. |
| `memory_pressure` | Memory or buffer pressure through large payload inserts. |
| `max_prepared` | Prepared transaction or XA prepare limit pressure. |

## JSON Selection File

You can also define the final suite in JSON:

```json
{
  "suite_name": "dbchaos-selected-suite",
  "cases": ["plan_flip", "memory_pressure", "max_prepared"],
  "planning_start_sec": 120,
  "planning_step_sec": 90,
  "during_sec": 60
}
```

Run with a JSON selection file:

```bash
cd /home/baibh/DBChaos/scripts
python3 generate_configs.py   --template-config "opengauss_tpccbbh_config_chaosblade.xml"   --template-worker "tpccbbh-worker.xml"   --template-suites "fault-cases-generic.xml"   --selection-file "selection.json"
```
