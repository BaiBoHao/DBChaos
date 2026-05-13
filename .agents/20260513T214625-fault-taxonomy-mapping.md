# Fault Taxonomy Mapping

## Background

The user provided a table with fault categories and asked where the currently implemented DBChaos adverse/fault injections should be placed.

## Table Categories

The provided table groups categories roughly as:

- Infra: hardware, CPU, memory, IO, network
- Runtime state: concurrency management, system limits
- Disaster tolerance: primary/standby cluster, distributed architecture
- Database kernel: connection management, SQL parsing and optimization, storage engine, execution engine, transaction concurrency management
- Upstream

## Mapping Judgment

Primary categories for implemented DBChaos profiles:

- `max_connection` / `conn_storm`: connection management
- `max_connection` / `conn_exhaustion`: connection management; secondary system limits
- `max_connection` / `thread_saturation`: execution engine; secondary system limits and connection management
- `plan_flip`: SQL parsing and optimization; secondary execution engine
- `uncommitted_txn`: transaction concurrency management; secondary concurrency management
- `duplicate_txn`: transaction concurrency management; secondary concurrency management
- `stack_overflow` / `func_recurse`, `proc_recurse`, `sql_depth`, `view_nest`: execution engine; `sql_depth` and `view_nest` also touch SQL parsing and optimization
- `stack_overflow` / `join_bomb`: SQL parsing and optimization; secondary execution engine
- `massive_rollback`: storage engine; secondary transaction concurrency management and IO
- `memory_pressure`: memory; secondary storage engine
- `max_prepared`: transaction concurrency management; secondary system limits and distributed architecture

## Notes

- DBChaos's current implemented faults mostly live in "database kernel" rather than pure infrastructure.
- `memory_pressure` is closest to Infra/Memory because it injects large payloads from the client side, but the observed pressure is mainly inside database buffer/cache/storage paths.
- `max_prepared` is best treated as transaction concurrency management today, while it can be positioned under distributed architecture if the narrative emphasizes 2PC/XA distributed transaction semantics.
