#!/usr/bin/env python3
"""Two real CLI experiments: lock the business hotspot, then an unrelated control table."""
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/testing/reports/resilience-opengauss-20261019' / time.strftime('%H%M%S')
OUT.mkdir(parents=True)
NAME = 'dbchaos-resilience-' + secrets.token_hex(4)
IMAGE = 'opengauss/opengauss:7.0.0-RC2.B015'
PASSWORD = 'Resil@' + secrets.token_hex(10) + '9Z'


def command(args, **kw):
    return subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          timeout=kw.pop('timeout', 30), **kw)


def docker(*args, **kw):
    return command(['docker', *args], **kw)


def sql(query):
    r = docker('exec', '-i', '-u', 'omm', '-e', 'LD_LIBRARY_PATH=/usr/local/opengauss/lib', NAME,
               '/usr/local/opengauss/bin/gsql', '-v', 'ON_ERROR_STOP=1', '-d', 'resilience', '-t', '-A', input=query)
    if r.returncode:
        raise RuntimeError(r.stdout.replace(PASSWORD, '[REDACTED]'))
    return r.stdout.strip()


created = False
results = []
try:
    env = dict(os.environ, GS_PASSWORD=PASSWORD, GS_USERNAME='resilience_admin', GS_DB='resilience',
               OTHER_PG_CONF='shared_buffers=128MB\\nmax_process_memory=2GB\\nmax_connections=50')
    r = docker('run', '--pull=never', '-d', '--name', NAME, '--label', 'dbchaos.experiment=resilience-report',
               '--cpus', '2', '--memory', '4g', '--memory-swap', '4g', '--shm-size', '256m', '-p', '127.0.0.1::5432',
               '-e', 'GS_PASSWORD', '-e', 'GS_USERNAME', '-e', 'GS_DB', '-e', 'OTHER_PG_CONF', IMAGE, env=env, timeout=90)
    if r.returncode:
        raise RuntimeError(r.stdout)
    created = True
    print('Created isolated container ' + NAME, flush=True)
    stable = 0
    for _ in range(90):
        try:
            stable = stable + 1 if sql('SELECT 1;') == '1' else 0
            if stable >= 5:
                break
        except RuntimeError:
            stable = 0
        state = docker('inspect', '--format', '{{.State.Running}}', NAME)
        if state.stdout.strip() == 'false':
            raise RuntimeError('isolated database exited during initialization; see container.txt')
        time.sleep(2)
    else:
        raise RuntimeError('database did not become ready')
    port = json.loads(docker('inspect', '--format', '{{json .NetworkSettings.Ports}}', NAME).stdout)['5432/tcp'][0]['HostPort']
    environment = {'image': IMAGE, 'version': sql('SELECT version();'), 'container': NAME,
                   'cpu': 2, 'memory_gib': 4, 'workload': 'one JDBC client, rate ceiling 100 UPDATE/s; not TPC-C',
                   'source_sha256': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                                     for p in (ROOT / 'src').rglob('*.java')}}
    (OUT / 'environment.json').write_text(json.dumps(environment, ensure_ascii=False, indent=2))
    sql('CREATE TABLE public.business_hotspot (id int PRIMARY KEY, val int);'
        'INSERT INTO public.business_hotspot VALUES (1,0);'
        'CREATE TABLE public.unrelated_hotspot (id int PRIMARY KEY, val int);'
        'INSERT INTO public.unrelated_hotspot VALUES (1,0);'
        'GRANT ALL ON public.business_hotspot, public.unrelated_hotspot TO resilience_admin;')
    with tempfile.TemporaryDirectory(prefix='dbchaos-resilience-') as tempdir:
        temp = Path(tempdir)
        classes = temp / 'classes'
        classes.mkdir()
        build = command(['javac', '--release', '8', '-encoding', 'UTF-8', '-cp', str(ROOT / 'lib/*'), '-d', str(classes),
                         *map(str, (ROOT / 'src').rglob('*.java')), str(Path(__file__).with_name('BusinessFeed.java'))])
        if build.returncode:
            raise RuntimeError(build.stdout)
        config = temp / 'db.properties'
        config.write_text('type=opengauss\nurl=jdbc:opengauss://127.0.0.1:' + port
                          + '/resilience?connectTimeout=3&socketTimeout=10&ApplicationName=resilience_fault\n'
                          + 'user=resilience_admin\npassword=' + PASSWORD + '\n')
        config.chmod(0o600)
        cp = ':'.join([str(temp), str(classes), str(ROOT / 'resources'), str(ROOT / 'lib/*')])
        for label, table in [('business_lock', 'business_hotspot'), ('unrelated_lock', 'unrelated_hotspot')]:
            folder = OUT / label
            feed = folder / 'business'
            feed.mkdir(parents=True)
            report_dir = folder / 'report'
            load_log = (folder / 'load.txt').open('w')
            load = subprocess.Popen(['java', '-cp', cp, 'BusinessFeed', str(config), str(feed)], stdout=load_log, stderr=subprocess.STDOUT)
            try:
                for _ in range(100):
                    if (feed / 'ready').exists():
                        break
                    if load.poll() is not None:
                        raise RuntimeError('business workload exited before readiness')
                    time.sleep(.1)
                else:
                    raise RuntimeError('business workload not ready')
                args = ['java', '-Xmx128m', '-cp', cp, 'chaos.Main', '--db', 'opengauss', 'txn', 'uncommitted_txn',
                        '-duration', '8000', '-table', 'public.' + table, '-holders', '1', '-rows', '1',
                        '--wait-stable', str(feed), '--baseline-window', '1', '--baseline-stable-windows', '3',
                        '--baseline-min-wait', '4', '--baseline-max-wait', '20', '--baseline-poll', '100',
                        '--baseline-tps-cv', '5', '--baseline-p95-swing', '1000', '--baseline-json', str(folder / 'baseline.json'),
                        '--recovery-timeout', '15', '--report-dir', str(report_dir)]
                health = []
                with (folder / 'injector.txt').open('w') as log:
                    proc = subprocess.Popen(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                    for line in proc.stdout:
                        log.write(line.replace(PASSWORD, '[REDACTED]')); log.flush()
                        if '成功锁定' in line:
                            health.append({'epoch_ms': round(time.time()*1000), 'select_1': sql('SELECT 1;')})
                    status = proc.wait(timeout=5)
                if load.poll() is not None:
                    raise RuntimeError('workload stopped before recovery observation ended')
                metrics = json.loads((report_dir / 'resilience.json').read_text())
                failures = []
                if status != 0:
                    failures.append('CLI exit ' + str(status))
                for key in ['P0', 'Pd', 'Pr', 't0_epoch_ms', 'td_epoch_ms', 'tr_epoch_ms']:
                    if metrics[key] is None:
                        failures.append('missing metric ' + key)
                if not metrics['recovered'] or not metrics['evidence_complete']:
                    failures.append('recovery/evidence not confirmed')
                if not health or any(h['select_1'] != '1' for h in health):
                    failures.append('independent database probe not healthy during fault')
                if not metrics['database_probe_fault_windows_all_responsive']:
                    failures.append('built-in database health evidence missing')
                if label == 'business_lock' and (metrics['max_drop_pct'] < 90 or metrics['Pd'] != 0):
                    failures.append('business hotspot not degraded as expected')
                if label == 'unrelated_lock' and metrics['max_drop_pct'] > 20:
                    failures.append('unrelated control showed excessive impact')
                remaining = sql("SELECT count(*) FROM pg_stat_activity WHERE application_name='resilience_fault';")
                if remaining != '0':
                    failures.append('injector/probe connections remain: ' + remaining)
                (folder / 'health-evidence.json').write_text(json.dumps({'during_fault': health, 'remaining_injector_connections': remaining}, indent=2))
                results.append({'scenario': label, 'passed': not failures, 'failures': failures, 'metrics': metrics})
                print(label + ': ' + ('PASS' if not failures else 'FAIL ' + '; '.join(failures)), flush=True)
            finally:
                (feed / 'stop').touch()
                try:
                    load.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    load.terminate(); load.wait(timeout=5)
                load_log.close()
    (OUT / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
    print('Evidence: ' + str(OUT), flush=True)
finally:
    if created:
        logs = docker('logs', NAME)
        (OUT / 'container.txt').write_text(logs.stdout.replace(PASSWORD, '[REDACTED]'))
        removed = docker('rm', '-f', '-v', NAME)
        (OUT / 'cleanup.json').write_text(json.dumps({'container': NAME, 'removed': removed.returncode == 0}))
        print('Isolated container removed: ' + str(removed.returncode == 0), flush=True)
if len(results) != 2 or not all(r['passed'] for r in results):
    raise SystemExit(1)
