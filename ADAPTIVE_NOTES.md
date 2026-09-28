# Adaptive openGauss white-box injectors

Source-only implementation; no database connections or runtime experiments were performed.
All existing case keys and CLI flags remain. Help identifies adaptive concurrency; actual defaults and clamped overrides are
printed as `[Adaptive]` at startup.
No postmaster setting is changed, including enable_ustore, enable_thread_pool, and
max_concurrent_autonomous_transactions.

## Common sizing and reporting

`querySettings` uses a parameterized pg_settings IN query on a fresh connection.
Typed accessors cache returned values; unavailable settings use conservative defaults
(max_connections=100, max_process_memory=2 GiB, pool/ustore off). The detected map
and unavailable-setting diagnostics distinguish observation from fallback assumptions.
Raw memory settings are KB; KB/MB/GB suffixes are also accepted.

Let M be max_process_memory in KB, U=max(1,floor(M/2097152)), and
B=max_connections-20. Memory scaling is S(base,cap)=min(cap,base*U), with a
minimum of base. Worker overrides are bounded by the case's memory limit and B,
including reserved anchor, scan, checkpoint, and autonomous sessions. Insufficient
session budgets fail visibly. These are injector budgets, not a measurement of
currently free sessions; competing sessions can still exhaust available connections.

AdaptiveFaultInject shares the seven cases' lifecycle, persistent worker connections,
statement tracking, first-three-distinct exception diagnostics (with the actual SQL),
and `[EffectiveWork] ok=N failed=M`. Setup and cleanup SQL failures are included;
connection/lifecycle errors are also reported. No successful setup statement counts
as effective work. Statements have a 60-second timeout; teardown cancels active
statements, interrupts and joins workers, closes sessions (releasing snapshots and
session locks), then removes dedicated objects. Zero effective work prints
EFFECTIVE_WORK_ZERO and exits 3 after cleanup. Cancellation can contribute failures.

## Per-case design

| Case | Detected knobs and strategy | Sizing / effective-work definition | Expected on C: 96 GiB, max_connections=200, pool off, ustore off |
| --- | --- | --- | --- |
| sinval_overflow | M, max_connections; native COMMENT ON TABLE invalidation with an idle repeatable-read XID anchor. The anchor does not lock the target table. | Threads=min(S(4,96), B-1), override clamped to this range. One successful COMMENT is one ok. | 96 mutators plus one anchor generate shared invalidations and catalog/cache churn. |
| dwb_hammer | M, max_connections, enable_double_write, incremental_checkpoint; native scattered stock updates plus CHECKPOINT. If double write is disabled, only dirty-page/checkpoint I/O pressure is expected. | Threads=min(S(4,96),B-1). Interval override bounded to max(250,2000/U)..60000 ms; default 2000 ms. Only positive-row UPDATEs count, never checkpoints alone. | 96 updating sessions plus one checkpoint session pressure dirty buffers and flush I/O; DWB involvement depends on its detected setting. |
| csnlog_thrashing | M, max_connections; native repeatable-read anchors and batches of 50 JDBC savepoint/write/release sequences followed by commit. Replaces the savepoint-only DO block, which did not establish XID allocation. | Anchors clamped to 1..min(S(1,8),max(1,B/4)); default 2. Threads=min(S(4,96),B-anchors). One committed batch containing writes is one ok; no inferred 50-success count. | 96 burners plus two anchors allocate write XIDs/subXIDs against per-session temporary tables, advancing CSNLog while retaining an old horizon. |
| ustore_undo_lag | M, max_connections, enable_ustore; ON creates a Ustore hotspot; OFF creates the same table without storage_type and uses random-row ASTORE updates. Both use a repeatable-read snapshot, 1280-byte varying payloads, and aggregate scans. | Scans=min(S(1,8),max(1,B/8)); mutators=min(S(2,48),B-scans-1). Native seeds one hotspot; ASTORE seeds clamp(20000*U,20000,200000) rows. Only positive-row UPDATEs count. | 200k seeded rows, 48 mutators, eight full-table scans and one anchor generate unreclaimable old tuple versions and visibility/I/O pressure. This is ASTORE MVCC bloat, not Ustore undo. |
| temp_catalog_bloat | max_connections (M also logged); native CREATE TEMP / INSERT / DROP cycles. Quoted literals; DROP in finally even when INSERT fails. | Default threads=max(1,B/2); overrides clamped to 1..min(96,B). Successful create+drop is one ok, including cycles whose INSERT failed. | 90 persistent sessions churn temporary catalog entries, dead catalog tuples and metadata locks. |
| nested_worker_exhaustion | M, max_connections, enable_thread_pool, thread_pool_attr, max_concurrent_autonomous_transactions. Worker target is threads*groups from thread_pool_attr. Parent advisory locks gate entry to autonomous procedures; contending parents wait on the server. Native parents sleep 2.5s; fallback parents hash clamp(500000*U,500000,10000000) generated values. Inner autonomous calls sleep 2.5s. JDBC DDL has no slash terminator. | Slots=min(cap-1,B/4), retaining one autonomous slot for other users (cap=1 uses one; cap=0 disables autonomous calls). Adaptive minimum A=max(workers,2*slots) with pool ON, clamp(S(8,128),8,128) otherwise. Parents=clamp(max(override,A),1,min(256,B-slots)); omitted override defaults to A. Raised overrides are logged. Slots also bounded by parents. Only successful parent CALLs count. | With override 36, 128 parents share nine autonomous slots, hashing 10,000,000 values per CALL and waiting inside backend sessions. At most 137 parent+autonomous sessions are budgeted. Run-specific lock namespace and procedure names isolate injector instances. |
| smp_mem_explosion | M, max_connections, enable_thread_pool, max_dop, query_dop, work_mem. ON uses SMP; OFF explicitly selects serial execution. Both inflate stock rows on two join inputs, build wide varying grouping keys, and hash-join/group/sort. An outer count avoids transferring millions of wide rows into the Java heap. | A=clamp(S(8,24),8,24); threads=clamp(max(override,A),1,min(S(2,32),B)); omitted override defaults to A. Raised overrides are logged. SMP DOP=clamp(requested,1,min(max_dop,M/262144)); default 4, missing max_dop defaults to 8. Serial DOP=1. work_mem=clamp(M/(1024*threads*DOP*4),16,256) MB. Expansion=clamp(U,4,24). A completed result representing >0 groups is one ok. | With override 6, 24 serial sessions, 256MB work_mem each, 24-fold stock expansion pressure executor hash/group/sort memory and spill I/O. |

## Runtime assumptions requiring subsequent verification

Compilation cannot validate installed openGauss SQL dialect/version, autonomous procedure
permissions, advisory-lock overloads, pg_settings visibility, or actual query plans.
The serial/SMP query assumes the standard bmsql_stock columns s_i_id, s_w_id and
s_data and populated warehouse 1; DWB additionally uses s_quantity and s_ytd and
standard item IDs 1..100000. Planner switches discourage merge/nested-loop joins;
EXPLAIN and memory/spill measurements are still needed to confirm operator choices
and pressure. A missing/empty stock table cannot produce a positive effective count.

Pool-worker occupancy while sleeping or waiting on advisory locks depends on server
scheduling; successful CALLs establish execution, not proof of starvation. Existing
external autonomous calls can still consume the reserved capacity. The CSNLog and
invalidation effects likewise require kernel metrics to establish their magnitude.
No successful-operation counter alone proves measurable benchmark degradation.
Queries exceeding the timeout or test duration may be canceled and leave zero
completed operations, correctly resulting in exit 3 rather than a success claim.

## Targeted concurrency-floor tuning

For the profile overrides (SMP=6, nested=36), with pool OFF:

| Instance | SMP effective pressure | Nested effective pressure |
| --- | --- | --- |
| 96 GiB (U=48), max_connections=200, autonomous cap=10 | A=24, upper=32, threads=24; DOP=1; work_mem=256MB; expansion=24 | A=128, upper=171, parents=128; slots=9; CPU series=10,000,000 |
| 6 GiB (U=3), assuming the same connection/autonomous settings | A=24, upper=6, threads=6; DOP=1; work_mem=256MB; expansion=4 | A=24, upper=171, parents=36; slots=9; CPU series=1,500,000 |

The existing SMP memory-derived safe upper bound takes precedence over its adaptive
minimum on smaller instances. Lower connection budgets can further clamp either case.
Pool ON retains max(workers,2*slots) as its minimum, subject to the existing 256-parent
and connection limits. Autonomous slot handling and the 2.5-second inner sleep are unchanged.
SMP maximum expansion is reduced from 32 to 24 to help completion under increased
concurrency; completion within 60 seconds still requires database measurement.
The 24*256MB=6GiB sum is a work_mem sizing total, not a hard process-memory cap:
multiple hash/group/sort operators and SMP workers can each allocate memory.

## Final recovery-preserving tuning (empirically validated)

Validated matrix: 21/21 profile x case successes (7 cases x {B: 2C8G/ustore off/thread_pool on,
C: 64C128G/ustore off/thread_pool off, D: 2C8G/ustore on/thread_pool on}). Thresholds:
injector_ok AND mean drop >= 10% AND recovery ratio >= 90%.

Root cause of the earlier failures was not the fault mechanisms but (a) SQL that could not
run (unquoted literals, ustore DDL on a ustore-disabled instance) and (b) self-inflicted
write/catalog volume that autovacuum could not reclaim inside the ~90s recovery window.
Recovery is therefore bounded by scaling the *total* pressure volume with instance memory,
not just the concurrency.

| Case | Final adaptive formula | 6 GiB result | 96 GiB result |
| --- | --- | --- | --- |
| temp_catalog_bloat | threads=16; aggregate target 1000 create/drop cycles/s; paceMs=max(1,round(1000*threads/target)-12) (intrinsic cycle ~12ms) | ~13-16k cycles | 61213 cycles; drop 17.5%, recovery 93.8% |
| dwb_hammer | threads=min(threads,clamp(max(8,2U),8,96)); interval-floor=max(250,12000/U) ms; update budget=15000*U rows; updatePace=max(0,1000*threads/targetPerSec-4) | 8 threads, 4000ms, 6ms pace, ~50k updates | 24-96 threads, 2000ms, pace 0, ~565k updates; drop 19.7%, recovery 95.6% |
| csnlog_thrashing | subxidsPerTxn=clamp(2U+4,8,50) | 10 subXIDs/txn | 50 subXIDs/txn; drop 93.7-94.9%, recovery 102-105% |
| nested_worker_exhaustion (pool on) | poolCap=clamp(2U+8,10,64); parents=clamp(min(requested,poolCap),1,upper); holdMs=clamp(600+400U,1000,2500) ms for parent and inner sleep | 14 parents, 1800ms | (pool off path) unchanged |
| ustore_undo_lag | ustore on: native hotspot; ustore off: ASTORE wide-row fallback (20000*U rows, 1280B payload) | ASTORE fallback | ASTORE fallback; drop 19.0%, recovery 95.2% |
| smp_mem_explosion | pool on: native SMP; pool off: serial DOP=1, work_mem=256MB, expansion=24 | serial, 6 threads | 24 threads; drop 13.8%, recovery 96.2% |

Historical tuning points (C temp): ~385 cycles/s -> 7.9% drop / 92.1% recovery;
~1354 cycles/s -> 28.5% / 88.4%. The drop-vs-rate curve is flat then steep, so the
validated ~1000 cycles/s keeps a clear margin while retaining self-healing.

## Enterprise-Scale Multi-Core & Large-Memory Adaptive Scaling (64C128G Profile C & Beyond)

To address the challenge where enterprise hardware (such as 64C128G instances with 96 GiB memory, `enable_thread_pool=off`, and `enable_ustore=off`) easily absorbs mild single-thread or low-concurrency faults, the injectors dynamically sense hardware scales and switch to enterprise-scale pressure strategies:

1. **Hardware Tier Detection (`isEnterprise`)**:
   - Condition: `memoryUnits >= 16 || maxConnections >= 150` (or `max_process_memory >= 32 GiB`).
   - Sizing factor: Base memory units $U = \max(1, \lfloor M / 2097152 \rfloor)$, scaling factor up to 48 on 96 GiB systems.

2. **Enterprise Scaling per Fault Case**:
   - **`smp_mem_explosion`**:
     - **Native SMP Parallelism**: Discovered that openGauss executes SMP parallel streams regardless of `enable_thread_pool`. Removes artificial `dop=1` fallback.
     - **CTE Barrier Elimination**: Subqueries replace `WITH` clauses to allow `Streaming(type: LOCAL GATHER)` and `Streaming(type: LOCAL REDISTRIBUTE)` pipelines across cores.
     - **Proc-Safe Parallel Sizing**: Tunes `threads=10` with `dop=4` (spawning ~80 StreamWorker processes) to saturate 64 CPU cores and allocate up to 512MB `work_mem` without exhausting the global `PGPROC` pool (`max_connections=200`).
   - **`nested_worker_exhaustion`**:
     - **Active Compute Loop (No Sleep Locks)**: Eliminates `pg_advisory_lock` queuing that previously caused 150+ backends to sleep.
     - **Process-Per-Connection Saturation**: All 160 parent backend processes actively execute recursive hash loops (`md5(generate_series)`), while repeatedly contending for the 10 autonomous transaction slots (catching and letting through limit overflows). Oversubscribes Linux run-queue to 160 / 64 = 2.5x load.
   - **`ustore_undo_lag`**:
     - **Hotspot Version Concentration**: Shrinks update target from 800k sparse rows to 500 concentrated hotspot rows under an uncommitted `REPEATABLE READ` snapshot anchor.
     - **Deep Version Chain Traversal**: 48 update threads generate tens of thousands of dead tuple versions within identical pages, forcing 32 concurrent scan threads to repeatedly execute heavy `HeapTupleSatisfiesVisibility` chain walks, burning CPU and memory bandwidth while remaining trivial for autovacuum to clean up after commit.
   - **`temp_catalog_bloat`**:
     - **Multi-Index RelCache Churn**: Each temporary table lifecycle dynamically creates primary keys and 2 secondary composite indexes (`CREATE INDEX ON tmp(c1); CREATE INDEX ON tmp(c2, c3);`).
     - **Global Cache Invalidation Broadcast**: Frequent index DDL broadcasts `CacheInvalidateRelcache` across all active backends, forcing benchmark worker sessions to constantly flush relation descriptors and contend for system catalog locks with 15ms micro-burst pacing.
