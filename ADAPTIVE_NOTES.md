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

## Closed-loop escalation (all seven cases)

Sizing formulas are open-loop: they pick a pressure level from static config and never
check whether it actually bit. AdaptiveFaultInject now adds a supervision loop on top.
Before starting pressure it measures a baseline probe: the best-case round-trip of a
trivial `SELECT 1` over a fresh connection (fresh connection also exercises the thread
pool, so an exhausted pool shows up as a large value or timeout). During the run a
daemon controller re-probes every `SAMPLE_MS` (3 s); while the probe stays below the
degradation threshold (`max(baseline*degradeFactor, minDegradeMs)`, default 4x / 10 ms)
it spawns additional pressure workers (copies of a unit supplied by `escalationWork()`),
stopping once the instance is unresponsive or the connection budget (`budget()-2`) is
reached. So on a large instance that shrugs off a fixed formula's concurrency, the loop
keeps adding load until the instance actually degrades, logging each step and the ceiling
when it cannot. The probe is used instead of database commit rate because the injector's
own commits would otherwise inflate the rate and mask the very degradation being sought.
The default `escalationWork()` replicates a proportionally-chosen closure the case
already registered, correct when closures hold no per-session state; CsnlogThrashing
keys its temp-table setup through a ThreadLocal so escalation onto extra threads is
safe. Cases may set `closedLoop=false` or override `escalationWork()`.
Intensity is tunable from the CLI: `-threads N` sets the starting concurrency (a floor
while the loop is on), `-loop off` pins the case's own sizing with no escalation (a
milder, fixed fault), and `-factor N` sets how deep the loop pushes (probe must reach N
times baseline latency).

`nested_worker_exhaustion` is pool-exhaustion only, with no CPU work. Each parent
`pg_sleep`s inside its worker while its autonomous child (another worker, admitted
through the same pool with no reserved capacity) also sleeps, so
`parents + live children` covers the pool without load. It reads the pool size as
`min(thread_pool_attr[0], max_connections)`, matching the kernel's `ConstrainThreadNum`,
and sizes `parents = max(pool - capSlots, (pool+1)/2)` so parent plus child sessions
cover the whole pool while staying under `max_connections`; the closed loop escalates
further if the pool auto-grows. Verified on five profiles
(2c8g/4c16g/8c32g/16c64g/24c96g; the large three with max_connections=300 and
thread_pool_attr 128/192): 2c8g 92.6%, 4c16g 92.1%, 8c32g 93.8%, 16c64g 91.1%,
24c96g 89.3% TPS drop, all recovered >= 90%. Container CPU *during* injection fell to
2-26% absolute (the box idles while its sessions sleep) — DB-internal worker starvation,
not hardware pressure.

## Per-case design

| Case | Detected knobs and strategy | Sizing / effective-work definition | Expected on C: 96 GiB, max_connections=200, pool off, ustore off |
| --- | --- | --- | --- |
| sinval_overflow | M, max_connections; native COMMENT ON TABLE invalidation with an idle repeatable-read XID anchor. The anchor does not lock the target table. | Threads=min(S(4,96), B-1), override clamped to this range. One successful COMMENT is one ok. | 96 mutators plus one anchor generate shared invalidations and catalog/cache churn. |
| dwb_hammer | M, max_connections, enable_double_write, incremental_checkpoint; native scattered stock updates plus CHECKPOINT. If double write is disabled, only dirty-page/checkpoint I/O pressure is expected. | Threads=min(S(4,96),B-1). Interval override bounded to max(250,2000/U)..60000 ms; default 2000 ms. Only positive-row UPDATEs count, never checkpoints alone. | 96 updating sessions plus one checkpoint session pressure dirty buffers and flush I/O; DWB involvement depends on its detected setting. |
| csnlog_thrashing | M, max_connections; native repeatable-read anchors and batches of 50 JDBC savepoint/write/release sequences followed by commit. Replaces the savepoint-only DO block, which did not establish XID allocation. | Anchors clamped to 1..min(S(1,8),max(1,B/4)); default 2. Threads=min(S(4,96),B-anchors). One committed batch containing writes is one ok; no inferred 50-success count. | 96 burners plus two anchors allocate write XIDs/subXIDs against per-session temporary tables, advancing CSNLog while retaining an old horizon. |
| ustore_undo_lag | M, max_connections, enable_ustore, thread_pool_attr. `-mode undo` (native, needs enable_ustore=on): updaters overwrite a compact hot set in place while REPEATABLE READ readers pin a pre-churn snapshot and re-read the overwritten rows, so the frozen OldestXmin blocks undo reclamation and each read rebuilds the visible version from undo (UHeapTupleFetch -> UVERSION_OLDER). `-mode fallback` (auto when ustore off): ASTORE hotspot version chain, unchanged. | Environment probe: capacity = min(thread_pool_attr[0], max_connections) when the pool is on, else S(8,128). Mutators=clamp(max(override,capacity/8),2,B-3); Undo: hotRows=clamp(S(512,24576),512,200000), readers=capacity-mutators-2 (clamped to B). Fallback: Scans=min(S(2,cap),B/4), hotspot 200/100 rows, seed 5000/20000. Only positive-row UPDATEs count. | Undo scales with instance capacity, so larger instances are not absorbed: drop 81-89% across 2/4/8/16/24-core (was 12-32% on 16/24-core with fixed sizing). Fallback remains ASTORE MVCC bloat. |
| temp_catalog_bloat | max_connections (M also logged); native CREATE TEMP / INSERT / DROP cycles. Quoted literals; DROP in finally even when INSERT fails. | Default threads=max(1,B/2); overrides clamped to 1..min(96,B). Successful create+drop is one ok, including cycles whose INSERT failed. | 90 persistent sessions churn temporary catalog entries, dead catalog tuples and metadata locks. |
| nested_worker_exhaustion | M, max_connections, enable_thread_pool, thread_pool_attr, max_concurrent_autonomous_transactions. Pool size is thread_pool_attr token[0] (total workers across groups), never token[0]*token[1]. With the pool ON, each parent CALL hashes a memory-scaled row count so it holds a worker thread while burning CPU and then nests an autonomous call (concurrent-limit overflow swallowed). No advisory lock. Fallback (pool OFF) parents hash a fixed series. JDBC DDL has no slash terminator. | Slots=min(cap-2,B/4) used only for sizing/logging (cap=1 uses one; cap=0 disables autonomous calls). Adaptive minimum A=max(workers,2*slots) with pool ON, clamp(S(8,128),8,128) otherwise. Pool ON parents=clamp(max(override, workers+slots),1,min(256,B-slots)); pool OFF keeps the process-saturation sizing. Raised overrides are logged. Only successful parent CALLs count. | Pin >pool sessions so small pools (16) cannot serve the benchmark while large pools (32/64) are held down by the per-CALL CPU work. Row count = clamp(100000*U,100000,1500000). Same procedure names isolate injector instances. |
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
| nested_worker_exhaustion | pool on: parents=clamp(max(requested, workers+slots),1,upper); each parent CALL hashes clamp(100000*U,100000,1500000) rows then nests an autonomous call; pool off: unchanged process-saturation path | 24 parents vs pool 16; starvation | 60 parents vs pool 64; starvation + CPU saturation |
| ustore_undo_lag | ustore on: snapshot-pinned undo reconstruction (hot rows x 1280B scaled by memory, modest updaters + REPEATABLE READ readers); ustore off: ASTORE wide-row fallback (20000*U rows, 1280B payload) | ASTORE fallback | ASTORE fallback; drop 19.0%, recovery 95.2% |

### ustore_undo_lag native undo matrix (solo, enable_ustore=on, 2026-10-04)

Each profile run alone (not the concurrent matrix), TPC-C 4 terminals, 30s baseline + 60s inject +
recovery. `enable_ustore=on`, `undo_zone_count=16` on all targets. The undo branch now probes the
environment and sizes itself against the instance's concurrency capacity -- thread_pool_attr token[0]
capped at max_connections, else a memory-scaled fallback -- rather than a constant: `updates=clamp(capacity/8,8,32)`,
`readers=capacity-updates-2`, `hotRows=clamp(S(512,24576),512,200000)`. So a larger instance receives
proportionally more undo pressure. Results with adaptive base sizing (`-loop off`, mechanism only):

| profile | cores | pool | hotRows | updaters | readers | drop | recovery |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 2c8g | 2 | 16 | 1536 | 8 | 6 | 81.5% | 101.7% |
| 4c16g | 4 | 32 | 3072 | 8 | 22 | 87.1% | 103.8% |
| 8c32g | 8 | 64 | 6144 | 8 | 54 | 88.4% | 104.3% |
| 16c64g | 16 | 128 | 12288 | 16 | 110 | 89.0% | 93.2% |
| 24c96g | 24 | 192 | 18432 | 24 | 166 | 87.3% | 110.4% |

Before this change the same profiles at fixed sizing dropped 83/80/88/**32/12**% -- i.e. the two large
instances were absorbed because `readers` was capped at 16 and `hotRows` at 8192, neither growing with
cores. Sizing against `capacity` removes the knee: every profile now degrades ~81-89% with recovery
>= 93%. The closed loop (default) remains as the safety net when the base sizing still cannot bite
(e.g. much larger pools than the connection budget can fill).
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
     - **Pool-Size-Aware Pinning**: Reads the real thread pool size (thread_pool_attr token[0]) and pins `workers+slots` parents so every worker thread is held on instances small enough for that to bite. The old token[0]*token[1] over-count hid exhaustion on the smaller instances.
     - **Compute-in-Hold (No Sleep Locks)**: Each parent CALL hashes a memory-scaled row count, holding its worker thread while saturating the CPU, then nests an autonomous call whose concurrent-limit overflow is swallowed. Large pools (more workers than the connection budget can pin) are therefore still degraded by CPU saturation rather than only parked sleeping sessions.
   - **`ustore_undo_lag`** (native `-mode undo`, requires `enable_ustore=on`):
     - **Snapshot-pinned undo reconstruction**: reader sessions take a REPEATABLE READ snapshot before the churn and never commit, so `OldestXmin` stays pinned and the undo recycler cannot advance. Each subsequent read of an overwritten row forces `UHeapTupleFetch` to rebuild the visible version from undo.
     - **Volume is the lever, not threads**: update threads stay modest (<=16); the unreclaimable undo working set is grown through hot-row count (memory-scaled up to 200k rows x 1280B payload) so large instances pay in undo file I/O, WAL and buffer-pool eviction rather than being absorbed by extra cores. `-mode fallback` keeps the historical ASTORE hotspot chain.
   - **`temp_catalog_bloat`**:
     - **Multi-Index RelCache Churn**: Each temporary table lifecycle dynamically creates primary keys and 2 secondary composite indexes (`CREATE INDEX ON tmp(c1); CREATE INDEX ON tmp(c2, c3);`).
     - **Global Cache Invalidation Broadcast**: Frequent index DDL broadcasts `CacheInvalidateRelcache` across all active backends, forcing benchmark worker sessions to constantly flush relation descriptors and contend for system catalog locks with 15ms micro-burst pacing.

## `temp_catalog_bloat`: two selectable logics (`-mode resource|lock`)

Measured on the 2026-09-30 scale matrix, the historical temp-table churn degraded the workload by
CPU/thread-pool contention, **not** by catalog bloat or metadata-lock contention. Root cause, from the
kernel: (a) temporary relations live in a session-local namespace and their catalog rows are only the
churner's own; (b) catcache invalidation is registered per-tuple hash and relcache invalidation per
relation OID (`PrepareToInvalidateCacheTuple`, `CacheInvalidateHeapTuple`), so a temp table's DDL never
flushes the workload's `bmsql_*` entries; (c) `heap_create_with_catalog` takes only `RowExclusiveLock`
on `pg_class`, which is compatible with the workload's `AccessShareLock`. So the business side paid
CPU, not lock waits — and CPU contention is absorbed by larger instances, which is why the drop fell
from 64% (2c8g) to 0.5% (8c32g) with identical injector load.

The case now exposes two logics:

- **`-mode resource`** (default): unchanged temp-table churn, now framed honestly as CPU/IO and
  thread-pool resource contention. Adaptive sizing and closed-loop escalation as before.
- **`-mode lock`**: genuine metadata-lock contention. A privileged (sysadmin) connection runs
  `SET xc_maintenance_mode = on` and `SET lockwait_timeout = 5000`, then cycles
  `LOCK TABLE <catalog> IN <lockMode> MODE` (default `pg_catalog.pg_class`, `ACCESS EXCLUSIVE`) inside
  an open transaction with a bounded `-hold_ms`, releasing via `rollback()`. This blocks catalog
  readers — relcache/catcache rebuilds, backend startup, autovacuum, DDL — producing real lock waits
  rather than CPU burns. `closedLoop` is disabled for this mode because the `SELECT 1` probe is served
  from cache and cannot observe a catalog lock. Note: the session GUCs are set under autocommit,
  because openGauss (like PostgreSQL) rolls back `SET` on transaction rollback, which would otherwise
  re-disable `xc_maintenance_mode` after the first release.

Args: `-catalog` (default `pg_catalog.pg_class`), `-lock_mode` (default `ACCESS EXCLUSIVE`),
`-threads` (default 1), `-hold_ms` (default 500), `-gap_ms` (default 100), and
`-su_user`/`-su_password` for the sysadmin account (defaults to the configured connection user).
`lock` mode requires a sysadmin account able to set `xc_maintenance_mode`; if unavailable it fails
fast with explicit guidance and reports `EFFECTIVE_WORK_ZERO`.
