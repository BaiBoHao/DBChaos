#!/usr/bin/env python3
"""Render the completed matrix without treating injector exit zero as fault success."""
import collections
import json
from pathlib import Path
import sys

out = Path(sys.argv[1]).resolve()
manifest = json.loads((out / 'manifest.json').read_text())
results = json.loads((out / 'summary.json').read_text())
legacy = manifest.get('source_sha256', {}).get('src/chaos/inject/MaxConnectionInject.java') == '91dc4a563dbe2fde1efcb318912069d2cbca4e7acf3a63f0a1019cce923a900d'
pool_note = ('- 历史27轮使用的旧版本不支持数字格式thread_pool_attr，且普通账号读取配置被拒绝（42501），实际回退不能只归因于解析格式。'
             'thread_saturation按64回退基数加32发起96个工作线程，而非按实际16/32/64分别计算。'
             '排队、建连失败和动态线程池扩展会影响实际执行并发。') if legacy else (
             '- 线程池信息可能受账号权限限制，实际目标并发、配置可见性和故障效果请结合本轮ConnectionReport及ConnectionAnalysis解释。')
cleanup_note = ('- 历史版本在awaitTermination到期后没有统一取消所有JDBC任务，非daemon线程可延长JVM存活；100秒超限由脚本终止。') if legacy else (
                '- 当前注入器报告清理是否完成；进程超限仍由外层脚本记录。撤销未确认时不能给出可靠恢复结论。')

telemetry_note = ('- 故障期间仅采集Docker统计和JDBC可用性探针，不重复轮询管理SQL，避免观测连接排队。'
                  if 'Docker stats only' in manifest.get('monitoring', '') else
                  '- telemetry还会打开低频管理SQL连接；docker exec超时后gsql未必立即退出，可能增加排队连接，轮次结束会清除。')
duration_note = ('- 阶段按请求开始时间划分；恢复从进程结束起算。历史版本每条sleep目标30秒，排队和超时可延长整个进程。'
                 if legacy else
                 '- 阶段按请求开始时间划分；恢复从进程结束起算。修复版在建连准备后持续施压30秒，每条sleep最多1秒，准备和撤销时间另计。')
groups = collections.defaultdict(list)
for r in results:
    groups[r['profile'], r['mode']].append(r)


def aggregate(rows, kind, phase):
    stats = [r.get('probes', {}).get(kind, {}).get(phase, {}) for r in rows]
    return sum(x.get('successes', 0) for x in stats), sum(x.get('attempts', 0) for x in stats)


def outcome(row):
    if row.get('timeout'): return '注入进程超时'
    if row.get('exit_code') == 0 and row.get('error'): return '注入返回0，后置观测失败'
    return row['status']


def ratio(value):
    success, total = value
    return f'{success}/{total} ({100*success/total:.1f}%)' if total else '无数据'


lines = ['# session max_connection 三规格实测报告', '',
         f"运行编号：{manifest['run_id']}。已记录{len(results)}轮，计划27轮。", '',
         f"数据库镜像：`{manifest['image']}`，镜像ID `{manifest['image_id']}`。", '',
         '数据库实际为openGauss-lite 7.0.0-RC2。全部测试串行执行，使用普通数据库账号；'
         '未运行TPC-C，以下成功率来自低频SELECT 1可用性探针。', '',
         '## 环境和方法', '',
         '| 规格 | CPU配额 | 内存限额 | max_process_memory | shared_buffers | 初始线程池 |',
         '| --- | ---: | ---: | ---: | ---: | ---: |']
for p in manifest['profiles']:
    lines.append(f"| {p['profile']} | {p['cpu']} | {p['memory_gib']}GiB | "
                 f"{p['max_process_memory_gib']}GiB | {p['shared_buffers_gib']}GiB | {p['pool_workers']} |")
lines.extend(['', '容器名称（结束后停止并保留）：', ''])
for p in manifest['profiles']:
    lines.append(f"- `{p['container']}`")
lines.extend(['', 'max_connections=100；每个故障目标持续30秒，轮次之间停止/启动数据库。'
              'conn_storm和conn_exhaustion的-count=100；thread_saturation的-count=32表示额外并发。'
              '初始线程池规模随CPU变化，因此本实验同时改变CPU、内存和线程池配置，不能单独归因于其中一项。', '',
              '每轮基线采集8秒，注入进程退出后至少采集15秒；后置SQL等待可能延长恢复采样。已有连接探针目标5次/秒，新连接探针目标1次/秒；'
              '遇到超时时实际采样频率降低。已有连接关闭后允许重连，其成功率包含这一恢复行为。', '',
              '请求了内存加Swap上限等于内存上限，但Docker实际inspect的MemorySwap为-1；'
              '实验前宿主机Swap总量为0。本轮未验证cgroup Swap控制生效，不能把请求值当作实际配置。', '',
              '## 三轮汇总', '',
              '表中比例为三轮请求数汇总的成功率，不是三轮成功率的算术平均。', '',
              '| 规格 | 模式 | 完成/记录轮数 | 故障中新连接成功 | 故障中已有连接成功 | 恢复期新连接成功 | 恢复期已有连接成功 |',
              '| --- | --- | --- | --- | --- | --- | --- |'])
for (profile, mode), rows in groups.items():
    complete = sum(r['status'] == 'completed' for r in rows)
    cells = [ratio(aggregate(rows, k, ph)) for k, ph in
             [('fresh', 'fault'), ('persistent', 'fault'), ('fresh', 'recovery'), ('persistent', 'recovery')]]
    lines.append(f'| {profile} | {mode} | {complete}/{len(rows)} | ' + ' | '.join(cells) + ' |')
lines.extend(['', '## 故障期成功探针速率', '',
              '超时会降低实际采样频率，不能仅按请求成功比例判断影响。下表按故障期墙钟时长计算成功样本/秒；'
              '它是限速探针的完成速率，不是数据库最大QPS，也不是TPC-C TPS。正常目标分别为新连接约1/s、已有连接约5/s。'
              '样本仍按请求开始时间归属阶段，跨阶段请求存在边界误差。', '',
              '| 规格 | 模式 | 新连接成功样本/秒 | 已有连接成功样本/秒 |',
              '| --- | --- | ---: | ---: |'])
for (profile, mode), rows in groups.items():
    seconds = sum((r.get('fault_end_ms', 0)-r.get('fault_start_ms', 0))/1000 for r in rows if 'fault_end_ms' in r)
    values = [aggregate(rows, kind, 'fault')[0]/seconds if seconds else 0 for kind in ('fresh', 'persistent')]
    lines.append(f'| {profile} | {mode} | {values[0]:.3f} | {values[1]:.3f} |')
lines.extend(['', '## 逐轮结果', '',
              '| 规格 | 模式 | 轮次 | 状态 | 注入进程耗时秒 | 故障中新连接成功 | 新连接SQLState | OOMKilled |',
              '| --- | --- | ---: | --- | ---: | --- | --- | --- |'])
for r in results:
    states = r.get('probes', {}).get('fresh', {}).get('fault', {}).get('sqlstates', [])
    lines.append(f"| {r['profile']} | {r['mode']} | {r['round']} | {outcome(r)} | {r.get('elapsed_s', '-')} | "
                 + ratio(aggregate([r], 'fresh', 'fault')) + ' | ' + ', '.join(states)
                 + f" | {r.get('oom_killed', '-')} |")
lines.extend(['', '## 解释和限制', '',
              '- 线程饱和后置SQL超时是数据库未能响应的观测结果；相应轮次仍计作实际故障执行，不能简单当作未运行。缺失OOM字段表示该轮未采集到状态，不表示发生OOM。',
              telemetry_note,
              cleanup_note,
              '- completed表示进程退出0并完成后置状态采集，不等于目标故障已经被独立证明。'
              '异常数量可能代表目标行为，应结合每轮injector.log和telemetry.jsonl。',
              pool_note,
              '- 成功请求p95在summary.json中；失败请求不能被低p95掩盖。'
              '低频探针不足以推导TPC-C吞吐下降或完整业务韧性结论。',
              duration_note,
              '- CPU为Docker配额，没有绑定独占物理核心；磁盘和宿主机与其他现有工作负载共享。'
              '轮次重启重置进程与连接，没有宣称清空宿主机文件缓存。',
              '- 动态端口每次启动可能改变，每轮result.json记录该轮端口；复用容器时需重新docker port查询。',
              '- 容器测试结束后停止并保留，私有凭据见本目录.private/credentials.json，权限限制且不应公开。', '',
              '初始化排错记录位于setup-attempts/，不计入正式27轮。'
              '原始数据见summary.json、summary.csv及规格/模式/round-N目录。', ''])
(out / 'report.md').write_text('\n'.join(lines))
print(out / 'report.md')
