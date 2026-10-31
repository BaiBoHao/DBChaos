#!/usr/bin/env python3
"""Diagnostic rerun: sample server thread-pool and wait states alongside original workload.
Does not replace the original 27 runs or restore/drop databases. Uses retained dedicated
work databases and original measurement bytecode. Monitoring uses the dedicated 5433 management port; ordinary 5432 can itself
queue behind saturated workers. Warmup is diagnostic, not the formal stability gate.
"""
import argparse,csv,datetime,importlib.util,json,math,os,re,signal,statistics,subprocess,threading,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
SPEC=importlib.util.spec_from_file_location('tps_runner',Path(__file__).with_name('run.py'))
r=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(r)
SQL="""
SELECT row_to_json(t) FROM (SELECT 'pool' AS kind,* FROM dbe_perf.local_threadpool_status) t;
SELECT row_to_json(t) FROM (
SELECT 'wait' AS kind,a.application_name,w.thread_name,w.wait_status,w.wait_event,w.block_sessionid,count(*) AS n
FROM pg_thread_wait_status w LEFT JOIN pg_stat_activity a ON a.sessionid=w.sessionid
WHERE w.thread_name='ThreadPoolWorker' OR a.application_name IN ('tps_business','tps_injector')
GROUP BY 2,3,4,5,6) t;
SELECT row_to_json(t) FROM (
SELECT 'activity' AS kind,a.application_name,a.pid,a.sessionid,a.state,a.waiting,a.enqueue,
extract(epoch from (clock_timestamp()-a.query_start))*1000 AS query_age_ms,
CASE WHEN a.query LIKE '%pg_sleep(%' THEN 'pg_sleep' ELSE left(a.query,100) END AS query,
w.thread_name,w.lwtid,w.wait_status,w.wait_event,w.block_sessionid
FROM pg_stat_activity a LEFT JOIN pg_thread_wait_status w ON w.sessionid=a.sessionid
WHERE a.application_name IN ('tps_business','tps_injector')) t;
"""
def dump(p,x):p.write_text(json.dumps(x,ensure_ascii=False,indent=2))
def now():return round(time.time()*1000)
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--profiles',nargs='+',default=['2c8g','4c16g','8c32g']);ap.add_argument('--out',type=Path);ap.add_argument('--after',type=Path,help='Wait for an earlier diagnostic result before starting');a=ap.parse_args()
 if a.after:
  deadline=time.monotonic()+600
  while not a.after.exists():
   if time.monotonic()>deadline:raise RuntimeError('Preceding diagnostic did not finish within 600 seconds')
   time.sleep(1)
 old=ROOT/'docs/testing/reports/tps-resilience-20261019-233413'
 out=a.out or ROOT/'docs/testing/reports'/('thread-wait-confirmation-'+time.strftime('%Y%m%d-%H%M%S'));out=out.resolve();out.mkdir(parents=True)
 (out/'.private').mkdir(mode=0o700);m=json.loads((old/'manifest.json').read_text());cal=json.loads((old/'calibration.json').read_text())
 manifest=dict(created=r.stamp(),source_report=str(old),kind='diagnostic mechanism rerun; not formal stability-gated repeat',warmup_s=30,baseline_s=30,fault_duration_s=120,recovery_s=30,sampling_interval_s=3,monitoring='transient local administrator gsql on dedicated management port 5433; adds query overhead',work_database_policy='Retained dedicated work database, no restore/drop; repeated transactions change data',results=[])
 dump(out/'manifest.json',manifest);print('OUTPUT '+str(out),flush=True)
 for p in r.PROFILES:
  if p['profile'] not in a.profiles:continue
  name=p['container'];profile=p['profile'];folder=out/profile;folder.mkdir();terminals=cal[profile]['selected_terminals'];business=injector=None;bf=ff=None;sampler=None;event=threading.Event();phase={'name':'warmup'}
  result=dict(profile=profile,terminals=terminals,pool_workers=p['pool_workers'],started=r.stamp())
  try:
   print('START '+profile,flush=True)
   was_running=r.docker('inspect','-f','{{.State.Running}}',name).strip()=='true';result['was_running']=was_running
   port=r.start(p)
   result['settings']=r.sql(name,"SELECT name,setting FROM pg_settings WHERE name IN ('enable_thread_pool','thread_pool_attr','max_connections') ORDER BY name;")
   # Same workload and original fault bytecode, with results written to this new folder.
   prop=r.properties(out,m,p,port,m['work_db'],profile,terminals,minutes=8)
   bf=(folder/'business.log').open('w')
   cmd=['java','-Xms256m','-Xmx1g','-Duser.timezone=Asia/Shanghai','-Ddbchaos.validate.disconnect=false','-cp',r.bcp(old),'-Dprop='+str(prop),'-DrunID=1','jTPCC']
   business=subprocess.Popen(cmd,cwd=old/'benchmark',stdout=bf,stderr=subprocess.STDOUT)
   deadline=time.monotonic()+60
   while not (folder/'business/data/result.csv').exists():
    if business.poll() is not None:raise RuntimeError('Business exited at startup')
    if time.monotonic()>deadline:raise RuntimeError('Business readiness timeout')
    time.sleep(.3)
   def sample():
    with (folder/'server-samples.jsonl').open('w') as f:
     while not event.is_set():
      row=dict(start_epoch_ms=now(),phase=phase['name'])
      try:
       raw=r.docker('exec','-i','-u','omm','-e','LD_LIBRARY_PATH=/usr/local/opengauss/lib',name,'/usr/local/opengauss/bin/gsql','-p','5433','-d',m['work_db'],'-t','-A','-v','ON_ERROR_STOP=1',input=SQL,timeout=12)
       row['rows']=[json.loads(x) for x in raw.splitlines() if x.startswith('{')]
      except Exception as e:row['error']=str(e).replace(r.PASSWORD,'[REDACTED]')
      row['end_epoch_ms']=now();f.write(json.dumps(row,ensure_ascii=False)+'\n');f.flush();event.wait(3)
   sampler=threading.Thread(target=sample,daemon=True);sampler.start()
   time.sleep(30);phase['name']='baseline';result['baseline_start_ms']=now();time.sleep(30);result['baseline_end_ms']=now()
   private=r.fault_config(out,m,p,port,profile+'/thread_saturation/diagnostic')
   cp=':'.join([str(private),str(old/m.get('injector_classes_dir','injector-classes')),str(ROOT/'resources'),str(ROOT/'lib/*')])
   args=['java','-Xmx512m','-Duser.timezone=Asia/Shanghai','-cp',cp,'chaos.Main','--db','opengauss','session','max_connection','-mode','thread_saturation','-count','32','-duration','120000']
   result['injector_command']=args;phase['name']='fault';result['fault_launch_ms']=now();ff=(folder/'injector.log').open('w')
   injector=subprocess.Popen(args,stdout=ff,stderr=subprocess.STDOUT)
   result['injector_exit']=injector.wait(timeout=160);result['fault_return_ms']=now();phase['name']='recovery';result['recovery_start_ms']=now();time.sleep(30);result['recovery_end_ms']=now()
   result['business_alive']=business.poll() is None
   print('FINISH '+profile+' exit='+str(result['injector_exit']),flush=True)
  except Exception as e:
   result['error']=str(e).replace(r.PASSWORD,'[REDACTED]');print('ERROR '+profile+' '+result['error'],flush=True)
  finally:
   if injector is not None and injector.poll() is None:
    injector.terminate()
    try:injector.wait(timeout=15)
    except subprocess.TimeoutExpired:injector.kill();injector.wait()
   event.set()
   if sampler is not None:sampler.join(timeout=15)
   if business is not None:r.end_business(business,bf)
   elif bf is not None:bf.close()
   if ff is not None:ff.close()
   try:
    result['final_pool']=r.sql(name,'SELECT * FROM dbe_perf.local_threadpool_status;',timeout=15)
    result['remaining_test_sessions']=r.sql(name,"SELECT application_name,count(*) FROM pg_stat_activity WHERE application_name IN ('tps_business','tps_injector') GROUP BY 1;",timeout=15)
   except Exception as e:result['cleanup_check_error']=str(e).replace(r.PASSWORD,'[REDACTED]')
   # All selected containers were retained stopped before this diagnostic task.
   r.stop(p)
   result['finished']=r.stamp();dump(folder/'result.json',result);manifest['results'].append(result);dump(out/'manifest.json',manifest)
 print('DONE '+str(out),flush=True)
if __name__=='__main__':main()
