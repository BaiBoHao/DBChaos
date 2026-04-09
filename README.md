# DBChaos: 基于内核原语构造的数据库韧性故障画像工具


![](./static/licensed-image.jpg)


## 1. 项目定位 (Project Positioning)
**DBChaos** 是一款专为分布式数据库与云原生数据库设计的韧性测试与故障画像（Fault Profiling）工具。不同于传统的仅通过外部高并发请求模拟负载的工具，DBChaos 深入数据库**内核原语**层级，通过**内核机制诱导**技术，精准触发数据库内部的逻辑边界与性能拐点。

该工具旨在帮助架构师与 DBA 评估数据库在极端并发、资源挤兑及逻辑异常情况下的**自愈能力（Self-healing）**与**隔离韧性（Isolation Resilience）**。

---

## 2. 核心技术亮点 (Technical Highlights)
* **机制级故障触发 (Mechanism-level Injection)**：通过动态干预 `autovacuum` 阈值、修改执行计划代价模型参数等手段，诱导数据库产生非预期的后台任务或计划跳变。
* **跨语系语法泛化 (Multi-dialect Adaptation)**：底层封装了一套标准的故障注入接口，完美适配 PostgreSQL、openGauss 及 MySQL (OceanBase) 等主流数据库语系。
* **震荡式画像构造 (Oscillation Profiling)**：支持周期性地在“稳定态”与“故障态”之间切换，用于观测系统在持续扰动下的性能收敛特征。
* **智能体式交互体验 (Agent-style CLI)**：具备彩色终端输出与实时内核状态监测功能，提供直观的故障生效证据链。

---

## 3. 故障画像目录 (Fault Taxonomy)

| 分类 | 故障画像 (Fault Profile) | 技术原理 (Technical Principle) |
| :--- | :--- | :--- |
| **查询优化器** | `plan_flip` | 通过注入偏斜数据并诱导 `autovacuum` 自动执行，强制触发执行计划从 Index Scan 向 Seq Scan 跳变。 |
| **并发与锁** | `uncommitted_txn` | 构造长事务持有行级排他锁（X-Lock），配合 `statement_timeout` 模拟高并发下的锁挤兑场景。 |
| **并发与锁** | `duplicate_txn` | 在热点行（Hot Row）上构造极高频次的并发写冲突，探测死锁检测器与锁管理器的响应边界。 |
| **执行分配** | `stack_overflow` | 诱导内核函数或存储过程进行深度递归，挑战 `max_stack_depth` 限制以触发内核级异常。 |
| **资源限制** | `max_conn` / `max_prepared` | 挤兑连接数上限及二阶段提交（2PC）预处理上限，模拟连接风暴与分布式事务挂起。 |
| **资源压力** | `memory` / `massive_rollback` | 注入大规模内存占用请求或频繁触发事务回滚，观测 Undo Log/WAL 日志及 Buffer Pool 的抗压能力。 |

---

## 4. 系统架构 (Architecture)
项目采用解耦的**插件化架构**设计：
* **Core 层**：负责全局配置加载（`db.properties`）、多驱动适配及 JDBC 会话池管理。
* **Inject 层**：通过继承 `BaseFaultInject` 实现具体的故障逻辑，支持独立的参数解析与指标采集。
* **UI/CLI 层**：基于 `chaos.properties` 进行元数据驱动展示，支持 ANSI 彩色高亮与欢迎横幅。



---

## 5. 快速开始 (Getting Started)

### 环境依赖
* JDK 1.8+
* Maven 3.6+
* 目标数据库: openGauss, PostgreSQL 或 MySQL 兼容引擎

### 构建项目
使用内置脚本进行自动化构建：
```bash
chmod +x build.sh
./build.sh DBChaos
```

### 启动注入
以 **openGauss** 上的执行计划跳变故障为例：
```bash
java -jar DBChaos.jar opengauss plan_flip -threads 16 -duration 300000 -interval 60000
```

---

## 6. 开发者 (Developer)
* **Author**: 西北工业大学
* **Project**: resilienceChaos