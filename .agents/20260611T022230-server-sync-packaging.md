# Server Sync And Packaging

## 背景

用户要求不要只停留在本地修改，需要把本地与服务器仓库同步到同一版本，并直接在服务器完成打包。

## 本次操作

1. 在本地分支 `codex/json-registry-migration` 上完成提交：
   - `0872a1b refactor: 改用 JSON 统一管理案例注册`
2. 推送到远程：
   - `origin/codex/json-registry-migration`
3. 检查服务器仓库 `/home/baibh/DBChaos`：
   - 原分支：`20260610`
   - 原提交：`6fbfe79`
   - 仅有一个脏文件：`DBChaos-0.0.1.jar`
4. 在服务器上先对 jar 修改做 `git stash push`，避免切分支时报错。
5. 服务器切换到新分支并对齐远程：
   - `codex/json-registry-migration`
   - 提交对齐到：`0872a1b`
6. 直接在服务器执行：
   - `./build_for_linux.sh`

## 验证结果

- Maven 构建成功，输出日志中显示：
  - `Compiling 19 source files`
  - `BUILD SUCCESS`
- 服务器可运行新 jar，并能识别新增联动 Case：
  - `java -jar DBChaos-0.0.1.jar quota --help`
  - 输出中已出现 `test_case`

## 产物位置

服务器构建脚本会生成并复制两份产物：

- `/home/baibh/DBChaos/target/DBChaos-0.0.1.jar`
- `/home/baibh/DBChaos/DBChaos-0.0.1.jar`

## 说明

- 服务器上之前的 `DBChaos-0.0.1.jar` 改动没有被强行覆盖，而是先用 stash 暂存后再切换分支。
- 本次同步完成后，本地与服务器源码分支都以 `codex/json-registry-migration` 为准。
