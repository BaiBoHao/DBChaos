#!/usr/bin/env python3
"""Independently verify real transaction windows and produce TPS plots/formula report."""
import argparse,csv,datetime,json,math,statistics,re
from collections import defaultdict
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

def read(path):return json.loads(path.read_text())
def number(v):return 'N/A' if v is None else f'{v:.3f}'
def pct(v):return number(v)+('%' if v is not None else '')
def iso(ms):return 'N/A' if ms is None else datetime.datetime.fromtimestamp(ms/1000,datetime.timezone.utc).isoformat(timespec='milliseconds')
def session_start(info):
 value=info['sessionStart']
 try:parsed=datetime.datetime.fromisoformat(value)
 except ValueError:
  # BenchmarkSQL sometimes emits hundredths of a second (e.g. .63),
  # which older Python builds do not accept through fromisoformat.
  parsed=datetime.datetime.strptime(value,'%Y-%m-%d %H:%M:%S.%f')
 if parsed.tzinfo is None:parsed=parsed.replace(tzinfo=datetime.timezone(datetime.timedelta(hours=8)))
 return parsed
def events(folder):
 info=list(csv.DictReader((folder/'business/data/runInfo.csv').open()))[0]
 start=round(session_start(info).timestamp()*1000)
 rows=[]
 with (folder/'business/data/result.csv').open() as f:
  for row in csv.DictReader(f):
   if row['ttype']=='DELIVERY_BG':continue
   rows.append((start+int(row['elapsed']),row['error'] in ('','0'),float(row['latency'])))
 return rows

def control_summary(root,r):
 folder=root/r['label'];path=folder/'control-windows.json'
 if not path.exists():return None
 windows=read(path);info=list(csv.DictReader((folder/'business/data/runInfo.csv').open()))[0]
 start=round(session_start(info).timestamp()*1000)
 ws=[w for w in windows if start+w['start_s']*1000>=r['control_start_epoch_ms'] and start+(w['start_s']+10)*1000<=r['control_end_epoch_ms']]
 rates=[w['tps'] for w in ws]
 if not rates:return None
 baseline=read(folder/'baseline.json');p0=statistics.mean(w['tps'] for w in baseline['window_series'])
 return dict(profile=r['profile'],baseline_tps=p0,complete_windows=len(rates),mean_tps=statistics.mean(rates),min_tps=min(rates),max_tps=max(rates),cv_pct=statistics.pstdev(rates)/statistics.mean(rates)*100,max_apparent_drop_pct=(p0-min(rates))/p0*100,mean_apparent_drop_pct=(p0-statistics.mean(rates))/p0*100)

def covered_recovery_pr(runtime,series):
 m=dict(runtime)
 if m.get('Pr') is not None or m.get('recovered') or not m.get('evidence_complete') or m.get('execution_failed'):
  return m,[]
 eligible=[s for s in series if s['phase']=='recovery' and s['complete']=='true' and s['source_covered']=='true']
 tail=eligible[-m['stable_windows']:]
 if not tail:return m,[]
 rates=[float(s['successful_tps']) for s in tail]
 pr=statistics.mean(rates);m['Pr']=pr;m['restored_baseline_pct']=pr/m['P0']*100
 m['regained_loss_pct']=(pr-m['Pd'])/(m['P0']-m['Pd'])*100 if m.get('Pd') is not None and m['P0']>m['Pd'] else None
 adjacent=all(int(tail[i]['start_epoch_ms'])==int(tail[i-1]['end_epoch_ms']) for i in range(1,len(tail)))
 m['recovery_stable']=len(tail)==m['stable_windows'] and adjacent and min(rates)>0 and statistics.pstdev(rates)/pr*100<=m['cv_limit_pct']
 # Deliberately retain runtime tr/recovered: a descriptive Pr is not proof of recovery.
 return m,tail

def session_epoch(folder):
 info=list(csv.DictReader((folder/'business/data/runInfo.csv').open()))[0]
 return round(session_start(info).timestamp()*1000)

def transaction_type_metrics(folder,series):
 """Recount raw BenchmarkSQL rows by transaction type inside covered windows."""
 from bisect import bisect_right
 selected={phase:[s for s in series if s['phase']==phase and s['complete']=='true' and s['source_covered']=='true'] for phase in ('baseline','fault')}
 intervals=[]
 for phase,windows in selected.items():
  intervals.extend((int(w['start_epoch_ms']),int(w['end_epoch_ms']),phase) for w in windows)
 intervals.sort();starts=[x[0] for x in intervals]
 counts=defaultdict(lambda:[0,0,[]])
 epoch=session_epoch(folder)
 with (folder/'business/data/result.csv').open() as stream:
  for row in csv.DictReader(stream):
   kind=row['ttype'].upper()
   if kind=='DELIVERY_BG':continue
   at=epoch+int(row['elapsed']);idx=bisect_right(starts,at)-1
   if idx<0:continue
   lo,hi,phase=intervals[idx]
   if at>=hi:continue
   ok=row['error'] in ('','0');entry=counts[phase,kind]
   entry[0 if ok else 1]+=1
   if ok:entry[2].append(float(row['latency']))
 rows=[]
 for phase in ('baseline','fault'):
  seconds=sum(int(w['end_epoch_ms'])-int(w['start_epoch_ms']) for w in selected[phase])/1000
  for (p,kind),(successes,errors,lats) in sorted(counts.items()):
   if p!=phase:continue
   rows.append(dict(phase=phase,ttype=kind,window_seconds=seconds,successes=successes,errors=errors,
      successful_tps=successes/seconds if seconds else None,
      error_pct=errors/(successes+errors)*100 if successes+errors else None,
      successful_p95_ms=sorted(lats)[math.ceil(.95*len(lats))-1] if lats else None))
 return rows

def matched_control(case_folder,series,m,control):
 """Descriptive no-fault reference at equal business age; never invent fault timestamps."""
 from bisect import bisect_left
 if not control:return None
 ce,success,latest=control;epoch=session_epoch(case_folder)
 def rate(lo,hi):
  a,b=ce+lo-epoch,ce+hi-epoch
  if b>latest or a<ce or b<=a:return None
  return (bisect_left(success,b)-bisect_left(success,a))/((b-a)/1000)
 baseline=[s for s in series if s['phase']=='baseline' and s['complete']=='true' and s['source_covered']=='true']
 fault=[s for s in series if s['phase']=='fault' and s['complete']=='true' and s['source_covered']=='true']
 if not baseline or not fault:return None
 b=rate(int(baseline[0]['start_epoch_ms']),int(baseline[-1]['end_epoch_ms']))
 rates=[rate(int(s['start_epoch_ms']),int(s['end_epoch_ms'])) for s in fault]
 if b is None or b<=0 or any(x is None for x in rates):return None
 r=dict(reference_only=True,baseline_tps=b,fault_period_mean_tps=statistics.mean(rates),fault_period_min_tps=min(rates),fault_period_mean_drop_pct=(b-statistics.mean(rates))/b*100)
 recovery=[s for s in series if s['phase']=='recovery' and s['complete']=='true' and s['source_covered']=='true']
 if m.get('tr_epoch_ms') is not None:recovery=[s for s in recovery if int(s['end_epoch_ms'])<=m['tr_epoch_ms']]
 tail=recovery[-m['stable_windows']:]
 pr=rate(int(tail[0]['start_epoch_ms']),int(tail[-1]['end_epoch_ms'])) if tail else None
 r['recovery_period_tps']=pr;r['recovery_period_baseline_ratio_pct']=pr/b*100 if pr is not None else None
 return r

def analyze(root):
 results=read(root/'runs.json') if (root/'runs.json').exists() else []
 run_manifest=read(root/'manifest.json')
 controls={};control_sources={}
 for r in results:
  if r['mode']=='control':
   c=control_summary(root,r)
   if c:
    controls[r['profile']]=c
    ev=events(root/r['label']);control_sources[r['profile']]=(session_epoch(root/r['label']),sorted(t for t,ok,lat in ev if ok),max(t for t,ok,lat in ev))
 (root/'control-summary.json').write_text(json.dumps(controls,ensure_ascii=False,indent=2))
 verification=[];summary=[];group=defaultdict(list);transaction_rows=[]
 fig,axes=plt.subplots(3,3,figsize=(16,11),squeeze=False,sharey=True)
 profiles=['2c8g','4c16g','8c32g'];modes=['conn_storm','conn_exhaustion','thread_saturation']
 for result in results:
  if result['mode']=='control':continue
  folder=root/result['label'];m=result.get('resilience');item={k:result.get(k) for k in ['profile','mode','round','status','terminals']}
  if not m:summary.append(item);continue
  runtime_m=dict(m)
  series=list(csv.DictReader((folder/'report/throughput.csv').open()))
  m,derived_windows=covered_recovery_pr(m,series)
  item['matched_control']=matched_control(folder,series,m,control_sources.get(result['profile']))
  item['Pr_source']='covered_window_recalculation' if derived_windows else 'runtime'
  item['Pr_runtime']=runtime_m.get('Pr')
  if derived_windows:
   corrected=dict(m,Pr_source='covered_window_recalculation',Pr_runtime=runtime_m.get('Pr'),used_recovery_windows=derived_windows,original_runtime_file='report/resilience.json',note='Original runtime JSON preserved; Pr and its ratios recalculated from latest covered complete recovery windows per documentation; tr/recovered unchanged.')
   (folder/'derived-resilience.json').write_text(json.dumps(corrected,ensure_ascii=False,indent=2))
  injector_text=(folder/'injector.log').read_text()
  item['injector_cleanup_complete']='清理完成=true' in injector_text
  item['injector_effect_evidence']=[line for line in injector_text.splitlines() if line.startswith('[ConnectionAnalysis]') or line.startswith('[ConnectionReport] 本地连接峰值=')]
  item.update({k:m.get(k) for k in ['P0','Pd','Pr','t0_epoch_ms','td_epoch_ms','tr_epoch_ms','fault_withdrawal_epoch_ms','max_drop_pct','mean_drop_pct','restored_baseline_pct','regained_loss_pct','fault_error_pct','recovered','evidence_complete','recovery_stable','monitoring_error']})
  item['recovery_duration_s']=(m['tr_epoch_ms']-m['fault_withdrawal_epoch_ms'])/1000 if m.get('tr_epoch_ms') is not None else None
  transaction_events=events(folder);checks=[]
  txn_metrics=transaction_type_metrics(folder,series)
  for tm in txn_metrics:transaction_rows.append(dict(profile=result['profile'],mode=result['mode'],round=result['round'],**tm))
  for s in series:
   if s['complete']!='true' or s['source_covered']!='true':continue
   lo,hi=int(s['start_epoch_ms']),int(s['end_epoch_ms'])
   found=[x for x in transaction_events if lo<=x[0]<hi];ok=sum(x[1] for x in found);err=len(found)-ok
   checks.append(dict(phase=s['phase'],start=lo,success_count_matches=ok==int(s['successes']),error_count_matches=err==int(s['errors'])))
  fault=[s for s in series if s['phase']=='fault' and s['complete']=='true' and s['source_covered']=='true']
  fmean=statistics.mean(float(s['successful_tps']) for s in fault) if fault else None
  item['fault_mean_tps']=fmean
  telemetry=[]
  if (folder/'telemetry.jsonl').exists():
   for line in (folder/'telemetry.jsonl').read_text().splitlines():
    try:
     t=json.loads(line)
     if 'docker' in t:telemetry.append(t)
    except (ValueError,KeyError):pass
  cpus=int(result['profile'].split('c')[0]);cpu_stats={}
  for phase in ('baseline','fault','recovery'):
   phase_rows=[s for s in series if s['phase']==phase and s['complete']=='true' and s['source_covered']=='true']
   if phase=='recovery':
    if m.get('tr_epoch_ms') is not None:phase_rows=[s for s in phase_rows if int(s['end_epoch_ms'])<=m['tr_epoch_ms']]
    phase_rows=phase_rows[-m['stable_windows']:]
   samples=[]
   if phase_rows:
    lo=min(int(s['start_epoch_ms']) for s in phase_rows);hi=max(int(s['end_epoch_ms']) for s in phase_rows)
    for t in telemetry:
     if lo<=t['epoch_ms']<hi:
      try:samples.append(float(t['docker']['CPUPerc'].rstrip('%'))/cpus)
      except (ValueError,KeyError):pass
   cpu_stats[phase]=statistics.mean(samples) if samples else None
  item['cpu_quota_usage_pct']=cpu_stats
  rec_windows=[s for s in series if s['phase']=='recovery' and s['complete']=='true' and s['source_covered']=='true']
  threshold_windows=[s for s in rec_windows if float(s['successful_tps'])>=.95*m['P0']]
  item['first_target_window_end_delay_s']=(int(threshold_windows[0]['end_epoch_ms'])-m['fault_withdrawal_epoch_ms'])/1000 if threshold_windows else None
  all_rec=[s for s in series if s['phase']=='recovery' and s['complete']=='true']
  eligible_rec=[s for s in all_rec if s['source_covered']=='true']
  if m.get('tr_epoch_ms') is not None:eligible_rec=[s for s in eligible_rec if int(s['end_epoch_ms'])<=m['tr_epoch_ms']]
  rec_tail=eligible_rec[-m['stable_windows']:];rec_rates=[float(s['successful_tps']) for s in rec_tail]
  item['recovery_full_windows']=len(all_rec);item['recovery_covered_windows']=sum(s['source_covered']=='true' for s in all_rec)
  item['recovery_tail_cv_pct']=statistics.pstdev(rec_rates)/statistics.mean(rec_rates)*100 if rec_rates and statistics.mean(rec_rates)>0 else None
  item['recovery_tail_min_baseline_ratio_pct']=min(rec_rates)/m['P0']*100 if rec_rates else None
  reasons=[]
  if not m.get('recovered'):
   if len(rec_rates)<m['stable_windows']:reasons.append('末段已覆盖窗口不足N个')
   if rec_rates and min(rec_rates)<m['P0']*.95:reasons.append('末段存在低于95%P0的窗口')
   if item['recovery_tail_cv_pct'] is not None and item['recovery_tail_cv_pct']>m['cv_limit_pct']:reasons.append('末段TPS CV超过稳定门限')
   if not reasons:reasons.append('运行时未确认恢复，须结合原始覆盖与连续性证据判断')
  item['recovery_criteria_observation']='；'.join(reasons) if reasons else '已确认连续N窗达到阈值且稳定'
  item['fault_success_transactions']=sum(int(s['successes']) for s in fault)
  item['fault_error_transactions']=sum(int(s['errors']) for s in fault)
  item['fault_complete_windows']=len(fault)
  item['fault_zero_success_windows']=sum(float(s['successful_tps'])==0 for s in fault)
  for phase in ('baseline','fault','recovery'):
   selected=[s for s in series if s['phase']==phase and s['complete']=='true' and s['source_covered']=='true']
   latencies=[]
   if selected:
    lo=min(int(s['start_epoch_ms']) for s in selected);hi=max(int(s['end_epoch_ms']) for s in selected)
    latencies=sorted(x[2] for x in transaction_events if x[1] and lo<=x[0]<hi)
   item[phase+'_success_p95_ms']=latencies[math.ceil(.95*len(latencies))-1] if latencies else None
  complete=all(x['success_count_matches'] and x['error_count_matches'] for x in checks)
  verify=dict(label=result['label'],all_complete_window_counts_match_raw=complete,checked_windows=len(checks),windows=checks)
  for phase in ('baseline','fault'):
   source=[s for s in series if s['phase']==phase and s['complete']=='true' and s['source_covered']=='true']
   raw=[x for x in txn_metrics if x['phase']==phase]
   verify[phase+'_transaction_type_counts_match_windows']=(sum(x['successes'] for x in raw)==sum(int(s['successes']) for s in source)
      and sum(x['errors'] for x in raw)==sum(int(s['errors']) for s in source))
  business_log=(folder/'business.log').read_text()
  item['business_reconnect_failures']=business_log.count('BUSINESS_RECONNECT_FAILED')
  item['business_reconnections']=business_log.count('BUSINESS_RECONNECTED')
  verify['validation_switch_disabled_matches']='VALIDATION_CLIENT_SOCKET_CLOSED' not in business_log and 'VALIDATION_SOCKET_CLOSE_FAILED' not in business_log
  if m['Pd'] is not None and fault:verify['Pd_matches_windows']=abs(min(float(s['successful_tps']) for s in fault)-m['Pd'])<.0011
  if m['max_drop_pct'] is not None:verify['max_drop_formula_matches']=abs((m['P0']-m['Pd'])/m['P0']*100-m['max_drop_pct'])<.02
  if fmean is not None and m['mean_drop_pct'] is not None:verify['mean_drop_formula_matches']=abs((m['P0']-fmean)/m['P0']*100-m['mean_drop_pct'])<.02
  if m['Pr'] is not None:verify['recovery_ratio_formula_matches']=abs(m['Pr']/m['P0']*100-m['restored_baseline_pct'])<.02
  baseline_path=folder/'baseline.json'
  p0_exact=m['P0']
  if baseline_path.exists():
   baseline=read(baseline_path)
   if baseline.get('window_series'):
    p0_exact=statistics.mean(w['tps'] for w in baseline['window_series'])
    verify['P0_matches_baseline']=abs(p0_exact-m['P0'])<.0011
  if fault and m.get('td_epoch_ms') is not None:
   minimum=min(float(s['successful_tps']) for s in fault)
   first=next(s for s in fault if float(s['successful_tps'])==minimum)
   verify['td_matches_first_minimum']=int(first['end_epoch_ms'])==m['td_epoch_ms']
   ok=sum(int(s['successes']) for s in fault);err=sum(int(s['errors']) for s in fault)
   if ok+err and m.get('fault_error_pct') is not None:verify['error_rate_formula_matches']=abs(err/(ok+err)*100-m['fault_error_pct'])<.0011
  recovery=[s for s in series if s['phase']=='recovery' and s['complete']=='true' and s['source_covered']=='true']
  if m.get('tr_epoch_ms') is not None:recovery=[s for s in recovery if int(s['end_epoch_ms'])<=m['tr_epoch_ms']]
  tail=recovery[-m.get('stable_windows',6):]
  if tail and all(s['source_covered']=='true' for s in tail) and m.get('Pr') is not None:
   pr_exact=statistics.mean(float(s['successful_tps']) for s in tail)
   verify['Pr_matches_recovery_windows']=abs(pr_exact-m['Pr'])<.0011
   if m.get('regained_loss_pct') is not None and m.get('Pd') is not None and p0_exact>m['Pd']:
    verify['regained_loss_formula_matches']=abs((pr_exact-m['Pd'])/(p0_exact-m['Pd'])*100-m['regained_loss_pct'])<.0011
  if m.get('tr_epoch_ms') is not None:
   rates=[float(s['successful_tps']) for s in tail]
   stable=len(rates)==m['stable_windows'] and min(rates)>=m['recovery_target_pct']/100*p0_exact and statistics.pstdev(rates)/statistics.mean(rates)*100<=m['cv_limit_pct']
   verify['tr_recovery_criteria_matches']=stable and int(tail[-1]['end_epoch_ms'])==m['tr_epoch_ms']
  verify['all_checks_pass']=all(v for k,v in verify.items() if k.endswith('_matches') or k=='all_complete_window_counts_match_raw' or k.startswith('P0_matches_') or k.startswith('Pr_matches_') or k.startswith('td_matches_'))
  verification.append(verify);summary.append(item);group[result['profile'],result['mode']].append(item)
  if m.get('t0_epoch_ms') is None:continue
  ax=axes[profiles.index(result['profile'])][modes.index(result['mode'])]
  x=[(int(s['start_epoch_ms'])+int(s['end_epoch_ms']))/2000-m['t0_epoch_ms']/1000 for s in series if s['complete']=='true' and s['source_covered']=='true']
  y=[float(s['successful_tps'])/m['P0']*100 for s in series if s['complete']=='true' and s['source_covered']=='true']
  ax.plot(x,y,marker='.',markersize=3,label=f"Round {result['round']}")
  ax.axvline((m['fault_withdrawal_epoch_ms']-m['t0_epoch_ms'])/1000,color='tab:red',alpha=.25)
 for i,p in enumerate(profiles):
  for j,mode in enumerate(modes):
   ax=axes[i][j];ax.set_title(p+' / '+mode);ax.axhline(100,color='gray',linestyle='--',alpha=.5);ax.axhline(95,color='green',linestyle=':',alpha=.5);ax.axvline(0,color='black',linestyle='--');ax.set_xlabel('Seconds relative to fault start');ax.set_ylabel('Successful business TPS / P0 (%)');ax.grid(alpha=.2);ax.set_ylim(bottom=0)
   if ax.get_legend_handles_labels()[0]:ax.legend()
 fig.suptitle('Real BenchmarkSQL business TPS: fault start=0, red=withdrawal; complete covered 10s windows')
 fig.tight_layout();fig.savefig(root/'tps-curves.png',dpi=150);plt.close(fig)
 figc,axc=plt.subplots(3,1,figsize=(12,9),squeeze=False)
 from bisect import bisect_left
 for i,profile in enumerate(profiles):
  ax=axc[i][0];ctrl=control_sources.get(profile)
  if ctrl:
   epoch,success,latest=ctrl;n=int((latest-epoch)//10000)
   xs=[j*10+5 for j in range(n)];ys=[(bisect_left(success,epoch+(j+1)*10000)-bisect_left(success,epoch+j*10000))/10 for j in range(n)]
   ax.plot(xs,ys,label='Successful business TPS')
   control_result=next(r for r in results if r['profile']==profile and r['mode']=='control')
   ax.axvline((control_result['control_start_epoch_ms']-epoch)/1000,color='green',linestyle='--',label='Start of post-baseline observation')
   ax.legend();ax.set_ylim(bottom=0)
  ax.set_title(profile+' / no-fault control');ax.set_xlabel('Seconds since business session start');ax.set_ylabel('Successful TPS');ax.grid(alpha=.2)
 figc.tight_layout();figc.savefig(root/'control-tps-curves.png',dpi=150);plt.close(figc)
 txn_agg=defaultdict(lambda:{'baseline_seconds':0.0,'baseline_successes':0,'baseline_errors':0,'fault_seconds':0.0,'fault_successes':0,'fault_errors':0})
 for row in transaction_rows:
  key=(row['profile'],row['mode'],row['ttype']);phase=row['phase'];a=txn_agg[key]
  a[phase+'_seconds']+=row['window_seconds'];a[phase+'_successes']+=row['successes'];a[phase+'_errors']+=row['errors']
 txn_summary=[]
 for (profile,mode,kind),a in sorted(txn_agg.items()):
  bs=a['baseline_successes']/a['baseline_seconds'] if a['baseline_seconds'] else None
  fs=a['fault_successes']/a['fault_seconds'] if a['fault_seconds'] else None
  total=a['fault_successes']+a['fault_errors']
  txn_summary.append(dict(profile=profile,mode=mode,ttype=kind,baseline_seconds=a['baseline_seconds'],baseline_successes=a['baseline_successes'],baseline_tps=bs,
      fault_seconds=a['fault_seconds'],fault_successes=a['fault_successes'],fault_tps=fs,mean_drop_pct=(bs-fs)/bs*100 if bs and fs is not None else None,
      fault_errors=a['fault_errors'],fault_error_pct=a['fault_errors']/total*100 if total else None))
 for filename,rows in [('transaction-type-detail.csv',transaction_rows),('transaction-type-summary.csv',txn_summary)]:
  if rows:
   with (root/filename).open('w') as stream:
    writer=csv.DictWriter(stream,fieldnames=list(rows[0]));writer.writeheader();writer.writerows(rows)
 (root/'verification.json').write_text(json.dumps(verification,ensure_ascii=False,indent=2))
 (root/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))
 if summary:
  fields=list(dict.fromkeys(k for r in summary for k in r))
  with (root/'summary.csv').open('w') as f:
   w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows(summary)
 lines=['# 真实业务 TPS 故障实验与韧性指标分析','','本报告使用 BenchmarkSQL 真实事务完成记录计算成功 TPS，不使用 SELECT 1 速率替代。运行中报告只包含已完成或已失败轮次；正式计划为三档配置、三种故障、各三轮，共27轮，另每档一轮无故障对照。','','## 方法与边界','','4仓库，事务比例 NEW_ORDER/PAYMENT/ORDER_STATUS/DELIVERY/STOCK_LEVEL=45/43/4/4/4，无人为事务速率上限；采用普通业务账号和独立普通故障账号。保留业务长连接，断线后每秒重试连接，不主动轮换连接，连接耗尽还持续执行心跳SQL，实测影响同时包含连接槽位与CPU/调度竞争，不能仅按连接准入解释。每轮从该规格同一份种子库备份恢复，跨规格种子由独立随机加载生成；CPU、内存、线程池均随规格变化，不能单独归因。容器未绑定独占CPU，宿主机与存储共享；期间存在本地编译、离线打包和报告分析等背景活动，影响未被单独量化，不据单轮细微差异作强因果结论。','','零TPS窗口指一个完整且有原始事务时间戳覆盖的10秒窗口内成功业务事务数为0；它说明该窗口没有成功事务完成，单独一个窗口不能证明数据库宕机。应结合错误事务、相邻窗口、连接状态及持续时长判断。未覆盖窗口不记作零TPS。报告中的“零成功窗口x/y”只统计完整且已覆盖的故障窗口。','','注入前最少预热180秒，10秒窗口，连续6窗口TPS CV≤5%、p95波动≤10%才放行；超时不强行注入。故障目标120秒，撤销后最多180秒，连续6窗口TPS各≥95%P0且CV≤5%才确认恢复。结果日志逐事务刷新，SQL异常设置error=1；断线时记录失败事务并重连，重连尝试不当作成功事务。按runInfo.sessionStart+elapsed对时。业务在报告结束前持续运行，报告结束后才受控停止。校准每档仅2分钟，用于选择本次可用并发，不代表数据库最大容量；每个正式轮次仍独立等待稳定。该负载为TPC-C类混合事务，不是认证TPC-C成绩。下降比例为负表示高于基线；恢复和弥补比例允许超过100%，不代表数据库容量提高。tr受10秒分桶和连续6窗判据限制，最早为撤销后60秒，60/70/80秒相近主要反映判据粒度，不是即时恢复时刻。Pr因超时边界未覆盖而缺失但已有足够覆盖数据时，按文档补算并另存derived-resilience.json，原始运行时JSON保留，且不据此伪造tr或确认恢复。','','## 负载校准','']
 lines=[line.replace('注入前最少预热180秒，',f"注入前最少预热180秒，当前最长等待{run_manifest.get('baseline_max_wait_s',360)}秒，") for line in lines]
 if run_manifest.get('protocol_history'):
  lines += ['等待预算说明：最初上限360秒；4c16g首个无故障对照仅p95稳定性未通过后，将后续等待上限延长至600秒、业务最长运行延长至20分钟。稳定门限、10秒窗口、并发、事务比例、120秒故障和180秒恢复观测均不变。已完成2c8g轮次都在原360秒内通过，未使用更大等待预算，因此无需重跑。实际参数以每轮injector_command及run.properties为准。', '']
 cal=read(root/'calibration.json') if (root/'calibration.json').exists() else {}
 lines+=['| 规格 | 终端数 | 成功TPS | TPS CV | p95波动 | 错误数 | 校准稳定 |','| --- | ---: | ---: | ---: | ---: | ---: | --- |']
 for p,c in cal.items():
  for x in c['candidates']:lines.append(f"| {p} | {x['terminals']} | {number(x['success_tps'])} | {pct(x['tps_cv_pct'])} | {pct(x['p95_swing_pct'])} | {x['errors']} | {x['stable']} |")
 for p,c in cal.items():
  lines+=['',f"{p} 正式终端数：{c.get('selected_terminals','未选定')}；选择依据：{c.get('selection_reason','校准中')}。",'']
 lines+=['','## 逐轮结果','','公式展示数值保留三位小数，实际计算使用未舍入值；原始JSON/CSV及补算文件保留更高精度。','','| 配置 | 故障 | 轮次 | 状态 | P0 TPS | Pd TPS | 故障均值 TPS | Pr TPS | 最大下降 | 平均下降 | 恢复至基线 | 弥补损失 | 撤销后恢复秒 | 错误率 | 证据完整 |','| --- | --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |']
 for r in summary:lines.append('| '+' | '.join([r['profile'],r['mode'],str(r['round']),r['status']]+[number(r.get(k)) for k in ['P0','Pd','fault_mean_tps','Pr']]+[pct(r.get(k)) for k in ['max_drop_pct','mean_drop_pct','restored_baseline_pct','regained_loss_pct']]+[number(r.get('recovery_duration_s')),pct(r.get('fault_error_pct')),str(r.get('evidence_complete','N/A'))])+' |')
 lines += ['', '## 配置与故障汇总', '', '| 配置 | 故障 | 有效轮数 | 最大下降范围 | 平均下降的轮次均值 | 确认恢复 |', '| --- | --- | ---: | --- | ---: | ---: |']
 for profile in profiles:
  for mode in modes:
   rs=[r for r in group[profile,mode] if r.get('evidence_complete')]
   drops=[r['max_drop_pct'] for r in rs if r.get('max_drop_pct') is not None]
   means=[r['mean_drop_pct'] for r in rs if r.get('mean_drop_pct') is not None]
   dr=f'{min(drops):.2f}%～{max(drops):.2f}%' if drops else 'N/A'
   lines.append(f"| {profile} | {mode} | {len(rs)}/3 | {dr} | {pct(statistics.mean(means) if means else None)} | {sum(bool(r.get('recovered')) for r in rs)}/{len(rs)} |")
 lines += ['', '## TPS曲线', '', '![真实业务TPS相对基线曲线](tps-curves.png)', '', '![无故障全程TPS曲线](control-tps-curves.png)', '']
 lines+=['','## 每个配置与故障的公式代入与分析','']
 for p in profiles:
  for mode in modes:
   rs=group[p,mode];lines+=['',f'### {p} / {mode}','']
   if not rs:lines+=['尚无可分析的已完成业务观测。'];continue
   for r in rs:
    b,d,a,f=r.get('P0'),r.get('Pd'),r.get('Pr'),r.get('fault_mean_tps')
    lines += [f"第{r['round']}轮：t0={iso(r.get('t0_epoch_ms'))}，td={iso(r.get('td_epoch_ms'))}，tr={iso(r.get('tr_epoch_ms'))}，撤销={iso(r.get('fault_withdrawal_epoch_ms'))}。",'']
    if r.get('Pr_source')=='covered_window_recalculation':
     lines += ['Pr说明：原始运行时JSON因末尾未覆盖窗口而留空；本报告按文档使用最近最多N个已覆盖完整窗口补算Pr及恢复/弥补比例，详见本轮derived-resilience.json。原始report/resilience.json不改写，tr和是否恢复保持原判定。', '']
    if b and d is not None and f is not None:
     lines += [f"- 最大下降 = ({number(b)}−{number(d)})/{number(b)}×100% = **{pct(r.get('max_drop_pct'))}**。",f"- 平均下降 = ({number(b)}−{number(f)})/{number(b)}×100% = **{pct(r.get('mean_drop_pct'))}**。"]
    else:lines+=['- 最大与平均下降：N/A，完整故障窗口或基线证据不足。']
    if b and a is not None:lines += [f"- 恢复至基线 = {number(a)}/{number(b)}×100% = **{pct(r.get('restored_baseline_pct'))}**。"]
    else:lines+=['- 恢复至基线：N/A。']
    if b and d is not None and a is not None and b>d:lines += [f"- 已弥补损失 = ({number(a)}−{number(d)})/({number(b)}−{number(d)})×100% = **{pct(r.get('regained_loss_pct'))}**。"]
    else:lines+=['- 已弥补损失：N/A，无可确认损失或证据不足。']
    if r.get('recovery_duration_s') is not None:lines += [f"- 撤销后恢复耗时 = ({r['tr_epoch_ms']}−{r['fault_withdrawal_epoch_ms']})/1000 = **{number(r['recovery_duration_s'])} 秒**。"]
    else:lines+=['- 撤销后恢复耗时：N/A，观测期内未确认连续窗口满足恢复判据。']
    lines += ['', f"故障完整窗口成功事务{r.get('fault_success_transactions',0)}笔、错误事务{r.get('fault_error_transactions',0)}笔，零成功窗口{r.get('fault_zero_success_windows',0)}/{r.get('fault_complete_windows',0)}。已覆盖完整窗口内成功事务p95：基线{number(r.get('baseline_success_p95_ms'))} ms → 故障{number(r.get('fault_success_p95_ms'))} ms → 恢复{number(r.get('recovery_success_p95_ms'))} ms。整轮业务重连失败{r.get('business_reconnect_failures',0)}次、成功重连{r.get('business_reconnections',0)}次；这些建连尝试不计作成功业务事务。", '']
   for r in rs:
    cpu=r.get('cpu_quota_usage_pct',{})
    lines += [f"第{r['round']}轮Docker采样CPU占配置限额均值：基线{pct(cpu.get('baseline'))} → 故障{pct(cpu.get('fault'))} → 恢复统计段{pct(cpu.get('recovery'))}。采样为区间统计，时间戳有命令延迟，只作资源关联证据。首个达到95%P0的完整恢复窗口结束于撤销后{number(r.get('first_target_window_end_delay_s'))}秒（仅单窗达标，不等于确认恢复）。", '']
    lines += [f"恢复完整/已覆盖窗口数{r.get('recovery_full_windows')}/{r.get('recovery_covered_windows')}；用于末段/已恢复组的窗口CV={pct(r.get('recovery_tail_cv_pct'))}，其中最低窗口达到P0的{pct(r.get('recovery_tail_min_baseline_ratio_pct'))}；判据观测：{r.get('recovery_criteria_observation')}。", '']
    c=r.get('matched_control')
    if c:
     lines += [f"第{r['round']}轮同业务运行年龄的无故障参照：前置时段{c['baseline_tps']:.3f} TPS，相同故障观察时段均值{c['fault_period_mean_tps']:.3f} TPS、最低{c['fault_period_min_tps']:.3f} TPS，对照平均变化按同样比例公式为下降{c['fault_period_mean_drop_pct']:.2f}%；对应恢复观察时段均值{number(c.get('recovery_period_tps'))} TPS，为此前参照值的{pct(c.get('recovery_period_baseline_ratio_pct'))}。", '']
     if not r.get('recovered') and c.get('recovery_period_baseline_ratio_pct') is not None and c['recovery_period_baseline_ratio_pct']<95:
      lines += ['同龄无故障时段也低于此前水平，因此不能将未回到原P0的全部差额归因于故障残留；本轮仍按正式判据报告未确认恢复。该参照只有一轮，事务和终端随机分配未配对，且故障期间累计事务量不同，不是严格因果估计。', '']
    lines += [f"第{r['round']}轮注入器清理完成：{r.get('injector_cleanup_complete')}；有效压力证据：", '']
    lines += ['- '+line for line in r.get('injector_effect_evidence',[])];lines += ['']
   valid=[r for r in rs if r.get('evidence_complete') and r.get('max_drop_pct') is not None]
   if valid:
    drops=[r['max_drop_pct'] for r in valid];means=[r['mean_drop_pct'] for r in valid]
    lines += [f"有效证据{len(valid)}/{len(rs)}轮；最大下降范围{min(drops):.2f}%～{max(drops):.2f}%，逐轮平均下降的均值{statistics.mean(means):.2f}%；确认恢复{sum(bool(r['recovered']) for r in valid)}/{len(valid)}轮。"]
    if p in controls:
     c=controls[p];lines += [f"该档无故障对照的最低窗口相对其基线下降{c['max_apparent_drop_pct']:.2f}%，平均下降{c['mean_apparent_drop_pct']:.2f}%；本组故障最大下降范围为{min(drops):.2f}%～{max(drops):.2f}%。需结合曲线、重复轮次和错误率判断；一轮对照不提供正式统计显著性检验。"]
    if mode=='conn_exhaustion':lines+=['业务复用已有连接，但压力端对持有连接持续执行SELECT 1心跳。TPS变化同时包含连接名额与CPU/调度竞争；不能把全部下降归因于连接准入，也不能由低频探针速率接近基线推断业务不受损。']
    elif mode=='thread_saturation':lines+=['结合业务窗口TPS和注入日志判断线程占用影响；低CPU的sleep排队也可能造成显著业务退化。']
    else:lines+=['连接风暴同时竞争认证、连接与CPU资源；需要结合无故障对照的自然波动，判断下降是否超出背景变化。']
 lines+=['','## 按业务事务类型拆分的 CSV 分析','','项目以BenchmarkSQL result.csv逐笔记录为源，读取事务完成时间、事务类型、延迟和error字段；throughput.csv把这些记录按已覆盖完整10秒窗口汇总。以下再次按类型复算，只纳入同一轮基线和故障阶段中complete=true且source_covered=true的窗口，排除DELIVERY_BG。TPS为三轮成功笔数总和除以对应阶段总覆盖秒数，属于按时间加权汇总；故障错误率=该类型故障错误笔数/(成功笔数+错误笔数)。逐轮明细见transaction-type-detail.csv，汇总见transaction-type-summary.csv；verification.json同时核对各类型计数之和与throughput.csv窗口计数一致。','','| 配置 | 故障 | 事务类型 | 基线成功TPS | 故障成功TPS | 平均下降 | 故障错误数 | 故障错误率 |','| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |']
 txn_names={'NEW_ORDER':'新订单','PAYMENT':'付款','ORDER_STATUS':'订单状态','DELIVERY':'发货','STOCK_LEVEL':'库存查询'}
 for row in txn_summary:
  lines.append('| '+' | '.join([row['profile'],row['mode'],txn_names.get(row['ttype'],row['ttype']),number(row['baseline_tps']),number(row['fault_tps']),pct(row['mean_drop_pct']),str(row['fault_errors']),pct(row['fault_error_pct'])])+' |')
 lines+=['','以上分类型TPS是把三轮已覆盖窗口的成功事务按总观测秒数合并，避免仅凭总体平均值误判某一事务类型。各类型负载比例和SQL成本不同，不应把类型TPS彼此当成相同工作量比较；应比较同一类型在故障前后的变化。','', '## 无故障对照','','对照没有真实故障，不伪造t0、Pd或tr。除基线放行后的300秒对照汇总外，各轮另按业务启动后的相同时间偏移选择参照时段，以检查预热及自然漂移。参照不参与正式韧性判定，只描述差异。对照原始窗口见各档 control/round-1/control-windows.json，control-tps-curves.png显示全程无故障TPS。','']
 for r in results:
  if r['mode']=='control':
   c=controls.get(r['profile'])
   if c:
    lines += [f"- {r['profile']}：基线{c['baseline_tps']:.3f} TPS；对照完整窗口{c['complete_windows']}个，TPS均值{c['mean_tps']:.3f}，范围{c['min_tps']:.3f}～{c['max_tps']:.3f}，CV={c['cv_pct']:.2f}%；最低窗口相对基线下降{c['max_apparent_drop_pct']:.2f}%，平均下降{c['mean_apparent_drop_pct']:.2f}%。"]
   else:lines += [f"- {r['profile']}：{r['status']}，没有完整对照观察段。"]
 failed_path=root/'failed-attempts/index.json'
 if failed_path.exists():
  excluded=read(failed_path)
  lines += ['', '## 未计入正式轮次的尝试', '', '原始失败数据完整保留在 failed-attempts/，不与修复后的正式轮次混合统计。']
  for x in excluded:lines += ['- '+x['archive_path']+'：'+x['reason']+'。']
  lines += ['', '首次业务满载连接风暴暴露JDBC撤销不完整：原版close/cancel路径可能阻塞，故障期业务日志也未覆盖全部窗口，不能从该次尝试确认Pd或恢复。后续使用独立abort-v2注入器先中止传输，再关闭连接；实验注入端登录/建连读取上限4秒，风暴socket读取4秒，其他模式10秒。业务端读取30秒。旧字节码、源码哈希和失败原始记录均保留；无故障对照不执行故障路径，可继续使用。', '']
 lines+=['','## 验证与文件','','原始业务记录、基线、TPS窗口、独立探针、资源统计、注入日志均保留在各轮目录。verification.json独立按原始事务完成时间复算已覆盖完整窗口计数，并核对下降、恢复公式；summary.csv/json保存逐轮指标；tps-curves.png显示三轮相对基线曲线。基线失败、业务提前退出、缺失证据的轮次保留并标记，不当作成功实验。成功事务p95仅描述返回成功的样本，不能掩盖零TPS窗口、失败事务、挂起请求或重连失败；业务错误率的分母不包含连接重试。','',f"当前已记录故障轮次{len(summary)}/27；已保存韧性指标{sum('P0' in r for r in summary)}轮；原始计数/公式验证通过{sum(r['all_checks_pass'] for r in verification)}/{len(verification)}轮。",'']
 (root/'指标计算与分析.md').write_text('\n'.join(lines))
 print(f"Report updated: {len(summary)}/27 fault rounds; verification {sum(r['all_checks_pass'] for r in verification)}/{len(verification)}")
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('root',type=Path);args=parser.parse_args();analyze(args.root.resolve())
