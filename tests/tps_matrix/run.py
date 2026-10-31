#!/usr/bin/env python3
"""Real BenchmarkSQL TPS experiments on the three retained, isolated session containers.

Only this run's dedicated seed/work databases are created or replaced. Source benchmark
is copied into the report. No other running containers or global properties are changed.
"""
import argparse,csv,datetime,hashlib,json,math,os,re,shutil,signal,statistics,subprocess,threading,time,traceback
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
PREVIOUS=ROOT/'docs/testing/reports/connection-repair-20261019-214350'
BENCH=Path('/home/kyh/benchmarksql-5.0')
PROFILES=json.loads((PREVIOUS/'manifest.json').read_text())['profiles']
CREDS=json.loads((PREVIOUS/'.private/credentials.json').read_text())
PASSWORD=CREDS['password']
REPORT_OUT=None

def dump(path,value):
 path=Path(path);tmp=path.with_suffix(path.suffix+'.tmp');tmp.write_text(json.dumps(value,ensure_ascii=False,indent=2));tmp.replace(path)
def stamp():return datetime.datetime.now().astimezone().isoformat(timespec='seconds')
def log(msg):
 line=stamp()+' '+msg
 print(line,flush=True)
 if REPORT_OUT is not None:
  with (REPORT_OUT/'execution.log').open('a') as f:f.write(line+'\n')
def cmd(args,timeout=30,**kwargs):
 r=subprocess.run(list(map(str,args)),text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout,**kwargs)
 if r.returncode:raise RuntimeError(r.stdout.replace(PASSWORD,'[REDACTED]')[-6000:])
 return r.stdout

def docker(*args,**kw):return cmd(['docker',*args],**kw)
def sql(name,query,db='chaoslab',timeout=60):
 return docker('exec','-i','-u','omm','-e','LD_LIBRARY_PATH=/usr/local/opengauss/lib',name,'/usr/local/opengauss/bin/gsql','-v','ON_ERROR_STOP=1','-d',db,'-t','-A',input=query,timeout=timeout).strip()
def start(p):
 docker('start',p['container'])
 for _ in range(90):
  try:
   if sql(p['container'],'SELECT 1;',timeout=8)=='1':break
  except Exception:pass
  time.sleep(2)
 else:raise RuntimeError('Database not ready')
 return json.loads(docker('inspect','-f','{{json .NetworkSettings.Ports}}',p['container']))['5432/tcp'][0]['HostPort']
def stop(p):docker('stop','-t','20',p['container'],timeout=40)

def init(out):
 (out/'.private').mkdir(mode=0o700,exist_ok=True);(out/'.private').chmod(0o700)
 work=out/'benchmark';work.mkdir(exist_ok=True)
 if not (work/'classes').exists():
  shutil.copytree(BENCH/'src',work/'src',dirs_exist_ok=True)
  from patch_benchmark import instrument
  instrument(work/'src')
  (work/'classes').mkdir()
  cp=':'.join([str(x) for x in (BENCH/'lib').rglob('*.jar')]+[str(ROOT/'lib/opengauss-jdbc-7.0.0-RC2.jar')])
  result=cmd(['javac','--release','8','-encoding','UTF-8','-cp',cp,'-d',work/'classes',*sorted((work/'src').rglob('*.java'))],timeout=120)
  (out/'benchmark-build.log').write_text(result)
  shutil.copy(BENCH/'run/log4j.properties',work/'log4j.properties')
  (work/'log4j.properties').write_text((work/'log4j.properties').read_text().replace('INFO, CONSOLE, E','INFO, CONSOLE'))
 classes=out/'injector-classes'
 if not classes.exists():
  classes.mkdir()
  (out/'injector-build.log').write_text(cmd(['javac','--release','8','-encoding','UTF-8','-cp',str(ROOT/'lib/*'),'-d',classes,*sorted((ROOT/'src').rglob('*.java'))],timeout=120))
 if not (out/'manifest.json').exists():
  suffix=out.name.split('tps-resilience-')[1].replace('-','')
  manifest=dict(run_id=out.name,created=stamp(),profiles=PROFILES,seed_db='tps_seed_'+suffix,work_db='tps_work_'+suffix,business_user='tps_biz_'+suffix,warehouses=4,fault_duration_s=120,baseline_min_wait_s=180,baseline_window_s=10,stable_windows=6,tps_cv_pct=5,p95_swing_pct=10,recovery_ratio_pct=95,recovery_timeout_s=180,benchmark_max_minutes=15,workload='BenchmarkSQL TPC-C-like mix; successful transactions/s excluding DELIVERY_BG, error!=0 excluded',connection_policy='Persistent business sessions with reconnect-on-failure (1s backoff); no proactive rotation; reconnect attempts logged separately from transaction errors',source_sha256={str(x.relative_to(ROOT)):hashlib.sha256(x.read_bytes()).hexdigest() for x in (ROOT/'src').rglob('*.java')},benchmark_original_sha256=hashlib.sha256((BENCH/'src/client/jTPCC.java').read_bytes()).hexdigest(),benchmark_instrumentation='resultAppend synchronized + flush each record; redacted run.properties; SQLExceptions set error flag; disconnected business sessions retry connection once per second without exiting JVM; SQL transaction logic unchanged')
  dump(out/'manifest.json',manifest)
 return json.loads((out/'manifest.json').read_text())

def reinstrument(out,m):
 from patch_benchmark import instrument
 work=out/'benchmark'
 shutil.copytree(BENCH/'src',work/'src',dirs_exist_ok=True)
 instrument(work/'src')
 cp=':'.join([str(x) for x in (BENCH/'lib').rglob('*.jar')]+[str(ROOT/'lib/opengauss-jdbc-7.0.0-RC2.jar')])
 (out/'benchmark-instrumentation-build.log').write_text(cmd(['javac','--release','8','-encoding','UTF-8','-cp',cp,'-d',work/'classes',*sorted((work/'src').rglob('*.java'))],timeout=120))
 m['benchmark_instrumentation']='Synchronized result writer, per-record flush, redacted properties; SQLExceptions mark error=1; business disconnect retries with 1s backoff, reconnect attempts are not successful transactions. SQL transaction paths unchanged.'
 m['connection_policy']='Persistent business sessions, reconnect only on failure; no proactive rotation.'
 m['instrumentation_source_sha256']={str(x.relative_to(work/'src')):hashlib.sha256(x.read_bytes()).hexdigest() for x in (work/'src').rglob('*.java')}
 dump(out/'manifest.json',m)
 log('Business instrumentation compiled; only isolated benchmark copy modified')

def refresh_injector(out,m):
 classes=out/'injector-classes-abort-v2';classes.mkdir(exist_ok=True)
 snapshot=out/'injector-source-abort-v2';shutil.copytree(ROOT/'src',snapshot,dirs_exist_ok=True)
 (out/'injector-abort-v2-build.log').write_text(cmd(['javac','--release','8','-encoding','UTF-8','-cp',str(ROOT/'lib/*'),'-d',classes,*sorted(snapshot.rglob('*.java'))],timeout=120))
 m.setdefault('source_sha256_before_abort_fix',m['source_sha256'])
 m['source_sha256']={str(x.relative_to(ROOT)):hashlib.sha256(x.read_bytes()).hexdigest() for x in (ROOT/'src').rglob('*.java')}
 m['injector_classes_dir']=classes.name
 m['injector_cleanup_revision']='Abort JDBC transport before close; avoid cancellation requiring another admitted connection; unfinished work still fails teardown.'
 m['injector_timeouts']={'login_s':4,'storm_socket_s':4,'other_mode_socket_s':10,'connect_s':4}
 dump(out/'manifest.json',m);log('Built isolated abort-v2 injector; original injector classes retained')

def bcp(out):return ':'.join([str(out/'benchmark/classes'),str(BENCH/'lib/*'),str(ROOT/'lib/opengauss-jdbc-7.0.0-RC2.jar')])
def properties(out,m,p,port,db,label,terminals=8,minutes=15):
 private=out/'.private'/label.replace('/','_');private.mkdir(mode=0o700,exist_ok=True)
 prop=private/'benchmark.properties'
 values=dict(db='postgres',driver='org.opengauss.Driver',conn=f'jdbc:opengauss://127.0.0.1:{port}/{db}?connectTimeout=10&socketTimeout=30&ApplicationName=tps_business',user=m['business_user'],password=PASSWORD,warehouses=4,loadWorkers=min(4,p['cpu']),terminals=terminals,runTxnsPerTerminal=0,runMins=minutes,limitTxnsPerMin=0,newOrderWeight=45,paymentWeight=43,orderStatusWeight=4,deliveryWeight=4,stockLevelWeight=4,terminalWarehouseFixed='false',useStoredProcedures='false',resultDirectory=str(out/label/'business'))
 prop.write_text(''.join(f'{k}={v}\n' for k,v in values.items()));prop.chmod(0o600)
 return prop

def prepare(out,m):
 state=json.loads((out/'preparation.json').read_text()) if (out/'preparation.json').exists() else {}
 for p in PROFILES:
  profile=p['profile'];name=p['container']
  if state.get(profile,{}).get('complete'):continue
  log('PREPARE '+profile);port=start(p);folder=out/profile/'setup';folder.mkdir(parents=True,exist_ok=True)
  try:
   user=m['business_user'];db=m['seed_db']
   if sql(name,f"SELECT count(*) FROM pg_roles WHERE rolname='{user}';")=='0':sql(name,f"CREATE USER {user} WITH PASSWORD '{PASSWORD}';")
   if sql(name,f"SELECT count(*) FROM pg_database WHERE datname='{db}';")=='0':sql(name,f'CREATE DATABASE {db} OWNER {user};')
   sql(name,f'GRANT USAGE,CREATE ON SCHEMA public TO {user};',db=db)
   prop=properties(out,m,p,port,db,profile+'/setup')
   phases=state.setdefault(profile,{})
   for step in ['tableCreates','extraCommandsBeforeLoad','LOAD','indexCreates','foreignKeys','extraHistID','buildFinish']:
    if phases.get(step):continue
    log(profile+' setup '+step)
    with (folder/(step+'.log')).open('w') as f:
     if step=='LOAD':args=['java','-Xmx1g','-cp',bcp(out),'-Dprop='+str(prop),'LoadData']
     else:
      script=BENCH/'run/sql.postgres'/(step+'.sql')
      if not script.exists():script=BENCH/'run/sql.common'/(step+'.sql')
      args=['java','-Xmx512m','-cp',bcp(out),'-Dprop='+str(prop),'-DcommandFile='+str(script),'ExecJDBC']
     r=subprocess.run(args,cwd=out/'benchmark',stdout=f,stderr=subprocess.STDOUT,timeout=2400)
    text=(folder/(step+'.log')).read_text()
    if r.returncode or re.search(r'(ERROR:|Exception|FATAL)',text):raise RuntimeError(profile+' setup failed '+step+': '+text[-3500:].replace(PASSWORD,'[REDACTED]'))
    phases[step]=True;dump(out/'preparation.json',state)
   monitor=(ROOT/'scripts/probe/install_connection_monitor.sql').read_text().replace(':\"monitor_user\"','\"'+CREDS['user']+'\"')
   sql(name,monitor,db=db)
   counts=sql(name,"SELECT 'warehouse',count(*) FROM bmsql_warehouse UNION ALL SELECT 'stock',count(*) FROM bmsql_stock UNION ALL SELECT 'customer',count(*) FROM bmsql_customer UNION ALL SELECT 'order_line',count(*) FROM bmsql_order_line;",db=db)
   phases.update(complete=True,counts=counts,role=sql(name,f"SELECT rolname,rolsuper,rolsystemadmin FROM pg_roles WHERE rolname='{user}';"));dump(out/'preparation.json',state)
   log('PREPARED '+profile+' '+counts.replace('\n',' '))
  finally:stop(p)

def refresh_work(out,m,p,port):
 name=p['container'];work=m['work_db'];seed=m['seed_db']
 monitor=(ROOT/'scripts/probe/install_connection_monitor.sql').read_text().replace(':\"monitor_user\"','\"'+CREDS['user']+'\"')
 sql(name,monitor,db=seed)
 if sql(name,f"SELECT count(*) FROM pg_database WHERE datname='{work}';")!='0':
  sql(name,f"SELECT pg_terminate_session(pid,sessionid) FROM pg_stat_activity WHERE datname='{work}';")
  sql(name,f'DROP DATABASE {work};',timeout=120)
 backup='/tmp/'+seed+'.dump'
 try:docker('exec',name,'test','-f',backup)
 except RuntimeError:
  docker('exec','-u','omm','-e','LD_LIBRARY_PATH=/usr/local/opengauss/lib',name,'/usr/local/opengauss/bin/gs_dump','-F','c','-f',backup,seed,timeout=300)
 sql(name,f'CREATE DATABASE {work} OWNER {m["business_user"]};',timeout=180)
 docker('exec','-u','omm','-e','LD_LIBRARY_PATH=/usr/local/opengauss/lib',name,'/usr/local/opengauss/bin/gs_restore','-d',work,backup,timeout=300)
 sql(name,f'GRANT CONNECT ON DATABASE {work} TO {CREDS["user"]};')
 sql(name,'ANALYZE;',db=work,timeout=120)
 return work

def begin_business(out,m,p,port,label,terminals,minutes=15):
 folder=out/label;folder.mkdir(parents=True,exist_ok=True)
 prop=properties(out,m,p,port,m['work_db'],label,terminals,minutes)
 logfile=(folder/'business.log').open('w')
 proc=subprocess.Popen(['java','-Xms256m','-Xmx1g','-Duser.timezone=Asia/Shanghai','-Ddbchaos.validate.disconnect='+str(label.startswith('validation/')).lower(),'-cp',bcp(out),'-Dprop='+str(prop),'-DrunID=1','jTPCC'],cwd=out/'benchmark',stdout=logfile,stderr=subprocess.STDOUT,start_new_session=True)
 deadline=time.monotonic()+60
 while time.monotonic()<deadline:
  if proc.poll() is not None:raise RuntimeError('Benchmark exited during startup: '+str(folder/'business.log'))
  file=folder/'business/data/result.csv';info=folder/'business/data/runInfo.csv'
  if file.exists() and info.exists() and len(info.read_text().splitlines())>=2 and file.stat().st_size>100:break
  time.sleep(.5)
 else:
  proc.terminate();raise RuntimeError('Benchmark readiness timed out')
 return proc,logfile,prop

def end_business(proc,logfile):
 if proc.poll() is None:
  proc.terminate()
  try:proc.wait(timeout=15)
  except subprocess.TimeoutExpired:proc.kill();proc.wait(timeout=10)
 logfile.close()

def business_windows(folder,ignore_s=60):
 path=folder/'business/data/result.csv';buckets={};all_rows=0
 with path.open() as f:
  for row in csv.DictReader(f):
   if not row.get('error') or row.get('error')=='0':error=False
   else:error=True
   if row.get('ttype')=='DELIVERY_BG':continue
   elapsed=int(row['elapsed']);idx=elapsed//10000
   b=buckets.setdefault(idx,[0,0,[]]);b[int(error)]+=1
   if not error:b[2].append(float(row['latency']))
   all_rows+=1
 last=max(buckets) if buckets else 0;result=[]
 for i in range(ignore_s//10,last):
  ok,err,lat=buckets.get(i,[0,0,[]]);lat.sort()
  result.append(dict(start_s=i*10,tps=ok/10,errors=err,p95_ms=lat[max(0,math.ceil(.95*len(lat))-1)] if lat else 0))
 return result

def window_summary(windows):
 if not windows:return dict(success_tps=0,tps_cv_pct=None,p95_swing_pct=None,errors=None,stable=False,windows=[])
 rates=[w['tps'] for w in windows];p95s=[w['p95_ms'] for w in windows];mean=statistics.mean(rates);p95mean=statistics.mean(p95s)
 cv=statistics.pstdev(rates)/mean*100 if mean else math.inf
 swing=(max(p95s)-min(p95s))/p95mean*100 if p95mean else math.inf
 errors=sum(w['errors'] for w in windows)
 return dict(success_tps=mean,tps_cv_pct=cv,p95_swing_pct=swing,errors=errors,stable=len(windows)>=6 and mean>0 and cv<=5 and swing<=10 and errors==0,windows=windows)

def telemetry(out,p,label,event):
 with (out/label/'telemetry.jsonl').open('w') as f:
  while not event.is_set():
   row={'epoch_ms':round(time.time()*1000)}
   try:row['docker']=json.loads(docker('stats','--no-stream','--format','{{json .}}',p['container'],timeout=10))
   except Exception as e:row['error']=str(e).replace(PASSWORD,'[REDACTED]')
   f.write(json.dumps(row)+'\n');f.flush();event.wait(3)

def calibrate(out,m):
 path=out/'calibration.json';results=json.loads(path.read_text()) if path.exists() else {}
 for p in PROFILES:
  profile=p['profile']
  if results.get(profile,{}).get('selected_terminals'):continue
  log('CALIBRATE '+profile);port=start(p)
  try:
   entry=results.setdefault(profile,{'candidates':[]})
   for terminals in (4,8,16,32):
    if any(x['terminals']==terminals for x in entry['candidates']):continue
    refresh_work(out,m,p,port);label=f'{profile}/calibration/terminals-{terminals}'
    log('CALIBRATION '+label)
    proc,handle,prop=begin_business(out,m,p,port,label,terminals,minutes=2)
    event=threading.Event();sampler=threading.Thread(target=telemetry,args=(out,p,label,event),daemon=True);sampler.start()
    try:
     exit_code=proc.wait(timeout=180)
     if exit_code:raise RuntimeError('Benchmark calibration failed: '+label)
    finally:end_business(proc,handle);event.set();sampler.join(timeout=15)
    windows=business_windows(out/label,ignore_s=60)
    stats=window_summary(windows[-6:]);stats.update(terminals=terminals,exit_code=exit_code)
    entry['candidates'].append(stats);dump(path,results)
    log(f"CALIBRATED {profile} terminals={terminals} TPS={stats['success_tps']:.2f} CV={stats['tps_cv_pct']:.2f}% p95Swing={stats['p95_swing_pct']:.2f}% errors={stats['errors']}")
   good=[x for x in entry['candidates'] if x['stable']]
   if good:
    high=max(x['success_tps'] for x in good);chosen=min((x for x in good if x['success_tps']>=.8*high),key=lambda x:x['terminals'])
    reason='Smallest stable concurrency achieving >=80% of best stable observed TPS; TPS CV<=5%, p95 swing<=10%, zero errors.'
   else:
    # No thresholds are relaxed: this candidate still must pass the formal gate.
    valid=[x for x in entry['candidates'] if x['errors']==0 and x['success_tps']>0]
    if not valid:raise RuntimeError('No error-free calibration candidate for '+profile)
    chosen=min(valid,key=lambda x:x['tps_cv_pct']);reason='No candidate met both stability thresholds during calibration; choose lowest CV error-free candidate, formal gate remains strict and may abort.'
   entry.update(selected_terminals=chosen['terminals'],selection_reason=reason);dump(path,results)
   log('SELECTED '+profile+' terminals='+str(chosen['terminals'])+' '+reason)
  finally:stop(p)
 return results

def fault_config(out,m,p,port,label):
 private=out/'.private'/label.replace('/','_');private.mkdir(mode=0o700,exist_ok=True)
 config=private/'db.properties'
 socket_timeout=4 if '/conn_storm/' in label else 10
 config.write_text(f'type=opengauss\nurl=jdbc:opengauss://127.0.0.1:{port}/{m["work_db"]}?connectTimeout=4&loginTimeout=4&socketTimeoutInConnecting=4&socketTimeout={socket_timeout}&ApplicationName=tps_injector\nuser={CREDS["user"]}\npassword={PASSWORD}\n');config.chmod(0o600)
 return private

def record_result(out,result):
 file=out/'runs.json';rows=json.loads(file.read_text()) if file.exists() else []
 rows=[r for r in rows if r['label']!=result['label']];rows.append(result);dump(file,rows)
 try:
  with (out/'report-generation.log').open('a') as reportlog:
   subprocess.run(['python3',str(ROOT/'tests/tps_matrix/analyze.py'),str(out)],stdout=reportlog,stderr=subprocess.STDOUT,env=dict(os.environ,MPLCONFIGDIR='/tmp/dbchaos-matplotlib'),timeout=120)
 except Exception as e:log('Report generation warning: '+str(e))

def experiment(out,m,p,calibration,mode,repetition):
 profile=p['profile'];label=f'{profile}/{mode}/round-{repetition}';folder=out/label
 if (folder/'result.json').exists():
  result=json.loads((folder/'result.json').read_text())
  if result.get('status') in ('completed','baseline_failed','business_failed','injector_failed'):return result
 folder.mkdir(parents=True,exist_ok=True);log('START '+label)
 result=dict(label=label,profile=profile,mode=mode,round=repetition,status='running',started=stamp(),terminals=calibration[profile]['selected_terminals'])
 proc=handle=injector=None;event=threading.Event();sampler=None
 try:
  port=start(p);refresh_work(out,m,p,port)
  result['db_settings']=sql(p['container'],"SELECT name,setting FROM pg_settings WHERE name IN ('max_connections','thread_pool_attr','enable_thread_pool','shared_buffers','max_process_memory') ORDER BY name;")
  private=fault_config(out,m,p,port,label)
  max_wait=int(m.get('baseline_max_wait_s',360))
  proc,handle,prop=begin_business(out,m,p,port,label,result['terminals'],minutes=int(m.get('benchmark_max_minutes',15)))
  sampler=threading.Thread(target=telemetry,args=(out,p,label,event),daemon=True);sampler.start()
  cp=':'.join([str(private),str(out/m.get('injector_classes_dir','injector-classes')),str(ROOT/'resources'),str(ROOT/'lib/*')])
  args=['java','-Xmx512m','-Duser.timezone=Asia/Shanghai','-cp',cp,'chaos.Main','--db','opengauss']
  monitor=['--baseline-window','10','--baseline-stable-windows','6','--baseline-tps-cv','5','--baseline-p95-swing','10','--baseline-min-wait','180','--baseline-max-wait',str(max_wait),'--baseline-json',str(folder/'baseline.json')]
  if mode=='control':
   # No fake fault: baseline command is followed by a fixed 300s no-fault control observation.
   args+=['monitor','baseline','-result-dir',str(folder/'business'),'-window','10','-stable-windows','6','-tps-cv','5','-p95-swing','10','-min-wait','180','-max-wait',str(max_wait),'-json',str(folder/'baseline.json'),'-on-timeout','fail']
  else:
   args+=['session','max_connection','-mode',mode,'-count','32' if mode=='thread_saturation' else '100','-duration','120000','--wait-stable',str(folder/'business'),*monitor,'--recovery-ratio','95','--recovery-timeout','180','--report-dir',str(folder/'report')]
  result['injector_command']=args
  with (folder/'injector.log').open('w') as loghandle:
   injector=subprocess.Popen(args,stdout=loghandle,stderr=subprocess.STDOUT)
   deadline=time.monotonic()+max_wait+360
   while injector.poll() is None:
    if proc.poll() is not None:
     result['business_exit_before_report']=proc.returncode
     # Do not manufacture recovery; let monitor finish with the resulting lack of coverage.
    if time.monotonic()>deadline:injector.kill();result['external_timeout']=True;break
    time.sleep(1)
   result['injector_exit_code']=injector.wait(timeout=15)
  result['business_alive_at_report']=proc.poll() is None
  if mode=='control' and result['injector_exit_code']==0:
   result['control_start_epoch_ms']=round(time.time()*1000)
   log('CONTROL baseline stable; observe 300s '+profile)
   until=time.monotonic()+300
   while time.monotonic()<until:
    if proc.poll() is not None:raise RuntimeError('Control business exited early')
    time.sleep(1)
   result['control_end_epoch_ms']=round(time.time()*1000)
   dump(folder/'control-windows.json',business_windows(folder,ignore_s=60))
  metrics=folder/'report/resilience.json'
  if metrics.exists():result['resilience']=json.loads(metrics.read_text())
  result['status']='completed' if result['injector_exit_code']==0 and result['business_alive_at_report'] else 'business_failed' if not result['business_alive_at_report'] else 'baseline_failed' if result['injector_exit_code']==3 and not metrics.exists() else 'injector_failed'
  result['business_stop_reason']='Controlled client termination after report/observation; never used as fault boundary or recovery.'
 except Exception as e:
  result.update(status='error',error=str(e).replace(PASSWORD,'[REDACTED]'));log('ERROR '+label+' '+result['error'])
 finally:
  if injector is not None and injector.poll() is None:injector.kill();injector.wait(timeout=10)
  if proc is not None:end_business(proc,handle)
  event.set()
  if sampler:sampler.join(timeout=15)
  try:
   result['remaining_business_sessions']=sql(p['container'],f"SELECT count(*) FROM pg_stat_activity WHERE datname='{m['work_db']}' AND application_name='tps_business';")
   result['container_state']=json.loads(docker('inspect','-f','{{json .State}}',p['container']))
   stop(p)
  except Exception as e:result['cleanup_error']=str(e).replace(PASSWORD,'[REDACTED]')
  result['finished']=stamp();dump(folder/'result.json',result);record_result(out,result)
  log('DONE '+label+' '+result['status']+(' '+json.dumps({k:result['resilience'].get(k) for k in ['P0','Pd','Pr','max_drop_pct','mean_drop_pct','recovered','evidence_complete']},ensure_ascii=False) if 'resilience' in result else ''))
 return result

def validate_client(out,m):
 p=PROFILES[0];port=start(p);label='validation/client-reconnect';proc=handle=None
 try:
  refresh_work(out,m,p,port)
  proc,handle,prop=begin_business(out,m,p,port,label,4,minutes=2)
  time.sleep(15)
  killed='validation-only client socket close after 5s; no server-session signal dependency'
  rows=list(csv.DictReader((out/label/'business/data/result.csv').open()))
  errors=[r for r in rows if r['ttype']!='DELIVERY_BG' and r['error']!='0']
  logs=(out/label/'business.log').read_text()
  result=dict(termination_result=killed,business_alive=proc.poll() is None,error_transactions=len(errors),reconnected='BUSINESS_RECONNECTED' in logs,successes_after_first_error=bool(errors) and any(r['error']=='0' and int(r['elapsed'])>int(errors[0]['elapsed']) for r in rows),example_errors=errors[:5])
  result['passed']=result['business_alive'] and result['error_transactions']>0 and result['reconnected'] and result['successes_after_first_error']
  dump(out/'client-validation.json',result)
  log('CLIENT VALIDATION '+json.dumps(result,ensure_ascii=False))
  if not result['passed']:raise RuntimeError('Client failure/reconnect validation did not pass')
 finally:
  if proc is not None:end_business(proc,handle)
  stop(p)

def formal(out,m):
 calibration=json.loads((out/'calibration.json').read_text())
 for p in PROFILES:
  control=experiment(out,m,p,calibration,'control',1)
  if control['status']!='completed':raise RuntimeError('Control did not pass baseline gate for '+p['profile']+'; investigate before injecting faults')
  for repetition in range(1,4):
   # Rotate order across rounds to reduce systematic order effects.
   modes=['conn_storm','conn_exhaustion','thread_saturation'];shift=repetition-1;modes=modes[shift:]+modes[:shift]
   for mode in modes:
    result=experiment(out,m,p,calibration,mode,repetition)
    if result['status']!='completed':raise RuntimeError('Round failed; investigate before continuing: '+result['label'])
 dump(out/'execution-complete.json',{'finished':stamp(),'planned_fault_rounds':27,'planned_controls':3})

def supervise(out,m):
 deadline=time.monotonic()+3600
 while True:
  path=out/'calibration.json'
  calibration=json.loads(path.read_text()) if path.exists() else {}
  completed=sum(bool(x.get('selected_terminals')) for x in calibration.values())
  if completed==len(PROFILES):break
  if time.monotonic()>deadline:raise RuntimeError('Calibration did not finish within supervisor timeout')
  log(f'Waiting for calibration: {completed}/{len(PROFILES)} profiles selected')
  time.sleep(50)
 # The existing calibration process writes selection just before stopping the last container.
 for p in PROFILES:
  for _ in range(30):
   if docker('inspect','-f','{{.State.Running}}',p['container']).strip()=='false':break
   time.sleep(1)
  else:raise RuntimeError('Calibration container is still running; refusing concurrent experiments')
 validate_client(out,m)
 formal(out,m)
 log('FINISHED all planned fault/control rounds; review report validity and missing evidence')

if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--out',type=Path);parser.add_argument('--stage',choices=['prepare','calibrate','formal','all','instrument','validate','supervise','refresh-injector'],default='prepare');args=parser.parse_args()
 out=args.out or ROOT/'docs/testing/reports'/('tps-resilience-'+time.strftime('%Y%m%d-%H%M%S'))
 out=out.resolve();out.mkdir(parents=True,exist_ok=True);REPORT_OUT=out;log('OUTPUT '+str(out))
 m=init(out)
 if args.stage=='supervise':supervise(out,m)
 if args.stage=='instrument':reinstrument(out,m)
 if args.stage=='refresh-injector':refresh_injector(out,m)
 if args.stage in ('prepare','all'):prepare(out,m)
 if args.stage in ('calibrate','all'):calibrate(out,m)
 if args.stage in ('validate','all'):validate_client(out,m)
 if args.stage in ('formal','all'):formal(out,m)
