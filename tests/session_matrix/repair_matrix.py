#!/usr/bin/env python3
"""Install a one-setting read-only bridge, then retest the retained ordinary-user containers."""
import hashlib
import importlib.util
import json
import os
import re
import sys
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PREVIOUS = ROOT/'docs/testing/reports/session-matrix-20261019-201649-5f2f'
spec = importlib.util.spec_from_file_location('session_matrix_runner', Path(__file__).with_name('run.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
OUT = ROOT/'docs/testing/reports'/('connection-repair-'+time.strftime('%Y%m%d-%H%M%S'))
OUT.mkdir(parents=True)
private = OUT/'.private'
private.mkdir(mode=0o700)
credentials = json.loads((PREVIOUS/'.private/credentials.json').read_text())
(private/'credentials.json').write_text(json.dumps(credentials))
(private/'credentials.json').chmod(0o600)
runner.PASSWORD = credentials['password']
runner.OUT = OUT
runner.RESULTS = []
manifest = json.loads((PREVIOUS/'manifest.json').read_text())
manifest['previous_run_id'] = manifest['run_id']
manifest['run_id'] = OUT.name
manifest['source_sha256'] = {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in (ROOT/'src').rglob('*.java')}
manifest['preparation'] = []
manifest['monitoring'] = 'SELECT 1 persistent/fresh probes; Docker stats only during pressure; no repeated gsql polling'
print('OUTPUT '+str(OUT),flush=True)

def stats_only(name, folder, stop):
    with (folder/'telemetry.jsonl').open('w') as f:
        while not stop.is_set():
            row = {'epoch_ms':round(time.time()*1000)}
            try:
                row['docker'] = json.loads(runner.docker('stats','--no-stream','--format','{{json .}}',name,timeout=8).stdout)
            except Exception as e:
                row['error'] = str(e)
            f.write(json.dumps(row)+'\n'); f.flush()
            stop.wait(3)

runner.telemetry = stats_only
classes = OUT/'classes'
classes.mkdir()
build = runner.command(['javac','--release','8','-encoding','UTF-8','-cp',str(ROOT/'lib/*'),'-d',str(classes),
                        *map(str,(ROOT/'src').rglob('*.java')),str(Path(__file__).with_name('SessionProbe.java'))],timeout=60)
(OUT/'build.log').write_text(build.stdout)
if build.returncode:
    raise RuntimeError('Compilation failed')
role = credentials['user']
if not re.fullmatch(r'[A-Za-z_][A-Za-z_0-9]*',role):
    raise RuntimeError('Unsafe test role identifier')
install = (ROOT/'scripts/probe/install_connection_monitor.sql').read_text().replace(':"monitor_user"','"'+role+'"')
for p in manifest['profiles']:
    name = p['container']
    actual_label = runner.docker('inspect','-f','{{index .Config.Labels "dbchaos.experiment"}}',name).stdout.strip()
    if actual_label != manifest['previous_run_id']:
        raise RuntimeError('Refusing to modify an unrelated container')
    try:
        runner.docker('start',name)
        runner.ready(name)
        response = runner.sql(name,install)
        (OUT/(p['profile']+'-install.log')).write_text(response)
        value = runner.sql(name,'SELECT dbchaos_monitor.thread_pool_attr();')
        role_state = runner.sql(name,"SELECT rolname,rolsuper,rolsystemadmin FROM pg_roles WHERE rolname='"+role+"';")
        if role_state != role+'|f|f':
            raise RuntimeError('Test role unexpectedly has administrator privileges')
        if str(p['pool_workers'])+',1,(nobind)' not in value:
            raise RuntimeError('Read-only bridge returned unexpected configuration: '+value)
        manifest['preparation'].append({'profile':p['profile'],'role':role_state,'installed_function_value':value})
        (OUT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
        folder = private/p['profile']
        folder.mkdir(mode=0o700)
        source = PREVIOUS/'.private'/p['profile']
        for filename in ['db.properties','probe.properties']:
            (folder/filename).write_text((source/filename).read_text())
            (folder/filename).chmod(0o600)
        print('PREPARED '+p['profile']+' ordinary role unchanged; bridge installed (ordinary JDBC verified in fault runs)',flush=True)
    finally:
        runner.docker('stop','-t','20',name,timeout=35)

if '--prepare-only' in sys.argv:
    print('Prepared all three containers',flush=True)
    sys.exit(0)

for p in manifest['profiles']:
    config = private/p['profile']/'db.properties'
    cp = ':'.join([str(config.parent),str(classes),str(ROOT/'resources'),str(ROOT/'lib/*')])
    for repetition in range(1,4):
        for mode in runner.MODES:
            runner.experiment(p['profile'],p['container'],mode,repetition,cp,config)

assert len(runner.RESULTS)==27
checks = []
for result in runner.RESULTS:
    path = OUT/result['profile']/result['mode']/('round-'+str(result['round']))
    log = (path/'injector.log').read_text()
    fresh = result.get('probes',{}).get('fresh',{}).get('recovery',{})
    persistent = result.get('probes',{}).get('persistent',{}).get('recovery',{})
    row = {'profile':result['profile'],'mode':result['mode'],'round':result['round'],
           'exit_zero':result.get('exit_code')==0,'cleanup_complete':'清理完成=true' in log,
           'no_configuration_errors':'42501' not in log and '只读监控函数不可用' not in log,
           'fresh_recovered':fresh.get('attempts',0)>0 and fresh.get('successes')==fresh.get('attempts'),
           'persistent_recovered':persistent.get('attempts',0)>0 and persistent.get('successes')==persistent.get('attempts')}
    if result['mode']=='thread_saturation':
        pool = next(p['pool_workers'] for p in manifest['profiles'] if p['profile']==result['profile'])
        row['pool_read_correctly']='配置线程池='+str(pool) in log and '配置来源=只读监控函数' in log
    checks.append(row)
(OUT/'verification.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2))
print('FINISHED 27 repair tests '+str(OUT),flush=True)
if not all(all(v for k,v in row.items() if k not in ['profile','mode','round']) for row in checks):
    raise RuntimeError('Some repair verification checks did not pass; inspect verification.json')
