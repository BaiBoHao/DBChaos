#!/usr/bin/env python3
"""Short real-JDBC report checks on the three containers retained by the earlier matrix."""
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
PREVIOUS = ROOT / 'docs/testing/reports/session-matrix-20261019-201649-5f2f'
spec = importlib.util.spec_from_file_location('session_matrix_runner', Path(__file__).with_name('run.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
OUT = ROOT / 'docs/testing/reports' / ('connection-report-smoke-' + time.strftime('%Y%m%d-%H%M%S'))
OUT.mkdir(parents=True)
private = OUT / '.private'
private.mkdir(mode=0o700)
credentials = json.loads((PREVIOUS / '.private/credentials.json').read_text())
runner.PASSWORD = credentials['password']
classes = OUT / 'classes'
classes.mkdir()
build = runner.command(['javac', '--release', '8', '-encoding', 'UTF-8', '-cp', str(ROOT/'lib/*'), '-d', str(classes),
                        *map(str,(ROOT/'src').rglob('*.java'))], timeout=60)
(OUT/'build.log').write_text(build.stdout)
if build.returncode:
    raise RuntimeError('Compilation failed')
manifest = json.loads((PREVIOUS/'manifest.json').read_text())
results = []
print('OUTPUT ' + str(OUT), flush=True)
for profile in manifest['profiles']:
    name = profile['container']
    label = runner.docker('inspect', '-f', '{{index .Config.Labels "dbchaos.experiment"}}', name).stdout.strip()
    if label != manifest['run_id']:
        raise RuntimeError('Refusing to modify an unrelated container')
    try:
        runner.docker('start', name)
        runner.ready(name)
        port = json.loads(runner.docker('inspect','-f','{{json .NetworkSettings.Ports}}',name).stdout)['5432/tcp'][0]['HostPort']
        folder = private/profile['profile']
        folder.mkdir(mode=0o700)
        config = folder/'db.properties'
        config.write_text(f'type=opengauss\nurl=jdbc:opengauss://127.0.0.1:{port}/chaoslab?connectTimeout=4&socketTimeout=4\n'
                          + 'user=' + credentials['user'] + '\npassword=' + runner.PASSWORD + '\n')
        config.chmod(0o600)
        cp = ':'.join([str(folder),str(classes),str(ROOT/'resources'),str(ROOT/'lib/*')])
        for mode in ['conn_storm','conn_exhaustion','thread_saturation']:
            count = '1' if mode=='thread_saturation' else '3'
            result = runner.command(['java','-Xmx256m','-cp',cp,'chaos.Main','--db','opengauss','session','max_connection',
                                     '-mode',mode,'-count',count,'-duration','1000'],timeout=25)
            log = profile['profile']+'-'+mode+'.log'
            (OUT/log).write_text(result.stdout.replace(runner.PASSWORD,'[REDACTED]'))
            counters = re.findall(r'(建连|SQL) 尝试=(\d+) 成功=(\d+) 失败=(\d+) 未完成=(\d+)',result.stdout)
            record = {'profile':profile['profile'],'mode':mode,'exit_code':result.returncode,'log':log,
                      'report_present':'[ConnectionAnalysis]' in result.stdout,
                      'counters_consistent':len(counters)==2 and all(int(a)==int(s)+int(f)+int(p) for _,a,s,f,p in counters),
                      'cleanup_complete':'清理完成=true' in result.stdout,
                      'healthy_after':runner.sql(name,'SELECT 1;')=='1'}
            if mode=='thread_saturation':
                record['pool_read_correctly']=(('配置线程池='+str(profile['pool_workers'])) in result.stdout and '配置来源=只读监控函数' in result.stdout and '42501' not in result.stdout)
            results.append(record)
            (OUT/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
            print(json.dumps(record,ensure_ascii=False),flush=True)
    finally:
        runner.docker('stop','-t','20',name,timeout=35)
assert len(results)==9 and all(r['exit_code']==0 and r['counters_consistent'] and r['cleanup_complete'] and
                              r['healthy_after'] and r.get('pool_read_correctly',True) for r in results)
print('PASSED 9 real-JDBC report checks',flush=True)
