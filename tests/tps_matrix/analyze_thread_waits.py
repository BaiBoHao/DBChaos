#!/usr/bin/env python3
"""Summarize diagnostic server snapshots and business completion records, without changing formal reports."""
import argparse,collections,csv,datetime,json,math,re,statistics
from pathlib import Path

def numbers(s):return {k:int(v) for k,v in re.findall(r'(\w+):\s*(\d+)',s)}
def span(a):return [min(a),max(a)] if a else None

def summarize(folder,extra):
 result=json.loads((folder/'result.json').read_text());raw=[]
 for p in [folder/'server-samples.jsonl']:
  if p.exists():raw.extend(json.loads(x) for x in p.read_text().splitlines())
 raw.extend(x for x in extra if x['profile']==folder.name)
 start=result['fault_launch_ms'];stop=result['fault_return_ms'];metrics={}
 for phase in ['baseline','fault','recovery']:
  if phase=='fault':lo,hi=start+10000,stop-5000
  else:lo,hi=result[phase+'_start_ms'],result[phase+'_end_ms']
  samples=[x for x in raw if x['start_epoch_ms']>=lo and x.get('end_epoch_ms',x['start_epoch_ms'])<=hi and x.get('rows')]
  info=[]
  for s in samples:
   pools=[x for x in s['rows'] if x['kind']=='pool'];acts=[x for x in s['rows'] if x['kind']=='activity']
   if not pools:continue
   worker=numbers(pools[0]['worker_info']);sessions=numbers(pools[0]['session_info'])
   sleep=[x for x in acts if x['application_name']=='tps_injector' and x['query']=='pg_sleep']
   bound=[x for x in sleep if (x.get('lwtid') or 0)>0]
   info.append(dict(epoch_ms=s['start_epoch_ms'],actual=worker['actual'],idle=worker['idle'],waiting=sessions['waiting'],running=sessions['running'],bound_sleep=len(bound),unbound_sleep=len(sleep)-len(bound),business_bound=sum(x['application_name']=='tps_business' and (x.get('lwtid') or 0)>0 for x in acts),sleep_wait_events=dict(collections.Counter(x.get('wait_event') for x in bound)),business_lock_waits=sum(x['application_name']=='tps_business' and x.get('waiting') for x in acts)))
  metrics[phase]={'samples':len(info),'actual_threads':span([x['actual'] for x in info]),'idle_threads':span([x['idle'] for x in info]),'waiting_sessions':span([x['waiting'] for x in info]),'bound_sleep':span([x['bound_sleep'] for x in info]),'unbound_sleep':span([x['unbound_sleep'] for x in info]),'fully_busy_samples':sum(x['idle']==0 for x in info),'samples_sleep_bound_equals_actual':sum(x['bound_sleep']==x['actual'] for x in info),'business_bound':span([x['business_bound'] for x in info]),'business_lock_wait_samples':sum(x['business_lock_waits']>0 for x in info),'example':info[len(info)//2] if info else None}
 # Business TPS: full 10s windows within each diagnostic phase only; no formal stability claim.
 business=folder/'business/data'
 ri=next(csv.DictReader((business/'runInfo.csv').open()))
 epoch=datetime.datetime.fromisoformat(ri['sessionStart']).replace(tzinfo=datetime.timezone(datetime.timedelta(hours=8))).timestamp()*1000
 events=[]
 for row in csv.DictReader((business/'result.csv').open()):
  if row['ttype']=='DELIVERY_BG':continue
  events.append((epoch+int(row['elapsed']),row.get('error') not in ('','0',None),float(row['latency'])))
 last=max(x[0] for x in events)
 for phase in ['baseline','fault','recovery']:
  lo,hi=(start+10000,stop-5000) if phase=='fault' else (result[phase+'_start_ms'],result[phase+'_end_ms'])
  windows=[]
  for i in range(int((hi-lo)//10000)):
   left=lo+i*10000;right=left+10000
   if right>last:continue
   selected=[x for x in events if left<=x[0]<right];ok=[x for x in selected if not x[1]];err=len(selected)-len(ok)
   windows.append({'start_ms':left,'end_ms':right,'successes':len(ok),'errors':err,'tps':len(ok)/10})
  rates=[w['tps'] for w in windows];selected=[x for x in events if windows and windows[0]['start_ms']<=x[0]<windows[-1]['end_ms']];lat=sorted(x[2] for x in selected if not x[1]);successes=sum(w['successes'] for w in windows);errors=sum(w['errors'] for w in windows)
  metrics[phase]['business']=dict(windows=len(windows),mean_tps=statistics.mean(rates) if rates else None,tps_range=span(rates),successes=successes,errors=errors,p95_ms=lat[math.ceil(len(lat)*.95)-1] if lat else None,error_pct=errors/(successes+errors)*100 if successes+errors else None,window_details=windows)
 b=metrics['baseline']['business']['mean_tps'];f=metrics['fault']['business']['mean_tps'];metrics['diagnostic_mean_drop_pct']=(b-f)/b*100 if b and f is not None else None
 metrics['profile']=folder.name;metrics['source']=str(folder);metrics['injector_exit']=result['injector_exit'];metrics['remaining_test_sessions']=result.get('remaining_test_sessions');metrics['sampling_errors']=sum('error'in x for x in raw)
 return metrics

def main():
 ap=argparse.ArgumentParser();ap.add_argument('roots',nargs='+',type=Path);ap.add_argument('--output',type=Path,required=True);a=ap.parse_args();summary=[]
 for root in a.roots:
  extra=[json.loads(x) for x in (root/'management-samples.jsonl').read_text().splitlines()] if (root/'management-samples.jsonl').exists() else []
  for folder in sorted(root.glob('*')):
   if folder.is_dir() and (folder/'result.json').exists():summary.append(summarize(folder,extra))
 a.output.write_text(json.dumps(summary,ensure_ascii=False,indent=2))
 for s in summary:
  print(s['profile'],'drop',s['diagnostic_mean_drop_pct'])
  for phase in ['baseline','fault','recovery']:
   x=s[phase];print(phase,'samples',x['samples'],'threads',x['actual_threads'],'idle',x['idle_threads'],'waiting',x['waiting_sessions'],'sleep',x['bound_sleep'],'all_busy',x['fully_busy_samples'],'TPS',x['business']['mean_tps'])
if __name__=='__main__':main()
