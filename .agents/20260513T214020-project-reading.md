# High-Level Project Reading

## Background

The user asked for an initial read of DBChaos before asking project questions and exploring ideas. DBChaos is positioned as a database resilience and chaos-engineering tool that injects adverse SQL/database-kernel primitives.

## Project Shape

- Maven Java project targeting Java 8 bytecode.
- Entry point: `src/chaos/Main.java`.
- Core abstraction: `src/chaos/core/BaseFaultInject.java`.
- Fault implementations: `src/chaos/inject/*.java`.
- Runtime resources: `resources/chaos.properties` and `resources/db.properties`.
- Demo integration: `demo/demo.sh` coordinates BenchmarkSQL plus DBChaos injection.
- Utility plotting code exists under `utils/`.

## Fault Profiles Read

- `plan_flip`: creates a sandbox table, alternates skewed and balanced data, monitors planner/statistics refresh and query latency.
- `max_connection`: supports connection storm, connection exhaustion, and thread saturation through concurrent JDBC sessions and sleep SQL.
- `uncommitted_txn`: holds row locks through long transactions using `SELECT ... FOR UPDATE` on user-specified tables.
- `duplicate_txn`: creates a hot-row sandbox table and drives concurrent update/insert conflicts, tracking timeouts, deadlocks, and uniqueness conflicts.
- `stack_overflow`: triggers recursive functions/procedures, deep expressions, nested views, and join-plan stress.
- `massive_rollback`: creates a temporary workload table and drives commit/rollback pressure while comparing client and database transaction counters.
- `memory_pressure`: inserts large BLOB/BYTEA payloads to pressure buffer/cache/memory paths.
- `max_prepared`: prepares many 2PC/XA transactions and rolls them back after a hold period.

## Validation

- `mvn -q -DskipTests package` could not run because `mvn` is not installed in the current shell.
- `javac -encoding UTF-8 -cp lib/*` with JDK 11 compiled the source successfully into ignored `target/codex-compile-check`.
- `javac --release 8` failed because `Main.java` uses `String.repeat`, which is not available on Java 8 despite the POM targeting Java 8.
- CLI help starts successfully using compiled classes plus `resources` and `lib/*`.
- CLI route checks showed `duplicate_txn`, `max_prepared`, and `memory` are advertised or implemented inconsistently with `Main.createInjector`; `memory_pressure` reaches `MemoryPressureFault`, whose own help still says `memory`.

## Initial Risks And Questions

- The routing table in `Main.createInjector` is behind the implemented fault classes.
- Documentation, help text, and command keywords need normalization.
- Per-fault `-h` currently triggers global help before dispatching to the specific injector help.
- `PlanFlipInject` accepts `-threads` but `startQueryLoad` currently uses a hard-coded internal loop of 16.
- Some profiles create/drop tables or functions directly in the configured database, so stronger sandbox/schema naming, cleanup guarantees, and dry-run/safety guards may be worth discussing before broader use.
- Runtime credentials exist in properties files; avoid exposing them in future reports or commits.

## Files Touched

- `.agents/index.md`
- `.agents/20260513T214020-project-reading.md`
