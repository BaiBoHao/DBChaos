# Scripts

常用脚本入口如下：

- `probe/check_db_connection.ps1`
  Windows 本地环境下的数据库连接探测脚本

- `probe/check_db_connection.sh`
  Linux / macOS 环境下的数据库连接探测脚本

- `probe/preflight_check.sh`
  注入前的环境预检查脚本，可顺带检查 demo 依赖

- `config_generator/`
  DBChaos 到 TPC-C / ChaosBlade 的配置生成目录  
  说明见：[config_generator/README.md](./config_generator/README.md)

- `scaffold/`
  新增不利 Case 的模板与脚手架目录  
  说明见：[scaffold/README.md](./scaffold/README.md)
