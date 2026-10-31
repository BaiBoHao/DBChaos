#!/usr/bin/env python3
"""Create three isolated openGauss containers and run 3 modes x 3 repetitions each.

Retains only this run's containers (stopped), data and private credentials for reuse.
Never changes or removes pre-existing containers. No TPC-C workload is implied.
"""
import csv
import hashlib
import json
import os
from pathlib import Path
import secrets
import statistics
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
RUN = os.environ.get('DBCHAOS_SESSION_RUN') or (time.strftime('%Y%m%d-%H%M%S') + '-' + secrets.token_hex(2))
OUT = ROOT / 'docs/testing/reports' / ('session-matrix-' + RUN)
PRIVATE = OUT / '.private'
IMAGE = 'opengauss/opengauss:7.0.0-RC2.B015'
PROFILES = [(2, 8, 6, 1, 16), (4, 16, 12, 2, 32), (8, 32, 24, 4, 64)]
MODES = ['conn_storm', 'conn_exhaustion', 'thread_saturation']
DURATION = 30000
PASSWORD = 'Chaos@' + secrets.token_hex(12) + '9Z'
USER = 'chaos_fault'
if (PRIVATE / 'credentials.json').exists():
    PASSWORD = json.loads((PRIVATE / 'credentials.json').read_text())['password']
CREATED = []
RESULTS = []


def command(args, timeout=30, **kw):
    return subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout, **kw)


def docker(*args, timeout=30, check=True, **kw):
    r = command(['docker', *map(str, args)], timeout=timeout, **kw)
    if check and r.returncode:
        raise RuntimeError(r.stdout.replace(PASSWORD, '[REDACTED]'))
    return r


def sql(name, query, timeout=12):
    return docker('exec', '-i', '-u', 'omm', '-e', 'LD_LIBRARY_PATH=/usr/local/opengauss/lib', name,
                  '/usr/local/opengauss/bin/gsql', '-v', 'ON_ERROR_STOP=1', '-d', 'chaoslab', '-t', '-A',
                  input=query, timeout=timeout).stdout.strip()


def ready(name):
    deadline = time.monotonic() + 180
    stable = 0
    while time.monotonic() < deadline:
        try:
            stable = stable + 1 if sql(name, 'SELECT 1;') == '1' else 0
            if stable >= 3:
                return
        except (RuntimeError, subprocess.TimeoutExpired):
            stable = 0
        if docker('inspect', '-f', '{{.State.Running}}', name).stdout.strip() != 'true':
            logs = docker('logs', '--tail', '40', name).stdout
            raise RuntimeError('Container stopped during startup: ' + logs.replace(PASSWORD, '[REDACTED]'))
        time.sleep(2)
    raise RuntimeError('Database readiness timed out: ' + name)


def metrics(name):
    return sql(name, "SELECT numbackends,xact_commit,xact_rollback FROM pg_stat_database WHERE datname='chaoslab';")


def telemetry(name, folder, stop):
    with (folder / 'telemetry.jsonl').open('w') as f:
        while not stop.is_set():
            record = {'epoch_ms': round(time.time()*1000)}
            try:
                r = docker('stats', '--no-stream', '--format', '{{json .}}', name, timeout=8)
                record['docker'] = json.loads(r.stdout)
                record['database'] = metrics(name)
            except Exception as e:
                record['error'] = str(e).replace(PASSWORD, '[REDACTED]')
            f.write(json.dumps(record) + '\n'); f.flush()
            stop.wait(3)


def summarize_probe(path, start, end):
    rows = list(csv.DictReader(path.open())) if path.exists() else []
    result = {}
    for phase, predicate in [('baseline', lambda t: t < start), ('fault', lambda t: start <= t < end),
                             ('recovery', lambda t: t >= end)]:
        selected = [r for r in rows if predicate(int(r['epoch_ms']))]
        success = [float(r['latency_ms']) for r in selected if r['success'] == 'true']
        success.sort()
        result[phase] = {'attempts': len(selected), 'successes': len(success),
                         'failures': len(selected)-len(success),
                         'successful_p95_ms': success[max(0, (95*len(success)+99)//100-1)] if success else None,
                         'successful_median_ms': statistics.median(success) if success else None,
                         'sqlstates': sorted({r['sqlstate'] for r in selected if r['success'] != 'true'})}
    return result


def write_summary():
    (OUT / 'summary.json').write_text(json.dumps(RESULTS, ensure_ascii=False, indent=2))
    with (OUT / 'summary.csv').open('w') as f:
        w = csv.writer(f)
        w.writerow(['profile', 'mode', 'round', 'status', 'exit_code', 'elapsed_s', 'fresh_fault_success',
                    'fresh_fault_attempts', 'persistent_fault_success', 'persistent_fault_attempts',
                    'fresh_recovery_success', 'fresh_recovery_attempts', 'oom_killed'])
        for r in RESULTS:
            a = r.get('probes', {}).get('fresh', {})
            b = r.get('probes', {}).get('persistent', {})
            w.writerow([r['profile'], r['mode'], r['round'], r['status'], r.get('exit_code'), r.get('elapsed_s'),
                        a.get('fault', {}).get('successes'), a.get('fault', {}).get('attempts'),
                        b.get('fault', {}).get('successes'), b.get('fault', {}).get('attempts'),
                        a.get('recovery', {}).get('successes'), a.get('recovery', {}).get('attempts'), r.get('oom_killed')])


def experiment(profile, name, mode, repetition, cp, config):
    label = f'{profile}/{mode}/round-{repetition}'
    folder = OUT / label
    folder.mkdir(parents=True)
    result = {'profile': profile, 'container': name, 'mode': mode, 'round': repetition, 'status': 'error'}
    probe = proc = None
    stop = threading.Event()
    sampler = None
    print('START ' + label, flush=True)
    try:
        docker('start', name)
        ready(name)
        port = json.loads(docker('inspect', '-f', '{{json .NetworkSettings.Ports}}', name).stdout)['5432/tcp'][0]['HostPort']
        import re
        for connection_file in (config, config.with_name('probe.properties')):
            connection_file.write_text(re.sub(r'127\.0\.0\.1:\d+/', '127.0.0.1:' + port + '/', connection_file.read_text()))
        result['port'] = port
        result['database_before'] = metrics(name)
        with (folder / 'probe.log').open('w') as log:
            probe = subprocess.Popen(['java', '-Xmx128m', '-cp', cp, 'SessionProbe', str(config.with_name('probe.properties')), str(folder)],
                                     stdout=log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 20
        while not all((folder / (kind + '.ready')).exists() for kind in ('fresh', 'persistent')):
            if probe.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError('Availability probe failed to start')
            time.sleep(.2)
        sampler = threading.Thread(target=telemetry, args=(name, folder, stop), daemon=True)
        sampler.start()
        time.sleep(8)
        count = 32 if mode == 'thread_saturation' else 100
        args = ['java', '-Xms64m', '-Xmx256m', '-cp', cp, 'chaos.Main', '--db', 'opengauss',
                'session', 'max_connection', '-mode', mode, '-count', str(count), '-duration', str(DURATION)]
        result['command'] = args
        result['launch_ms'] = round(time.time()*1000)
        result['fault_start_ms'] = result['launch_ms']
        proc = subprocess.Popen(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        # Drain output in a thread so hangs are bounded without blocking the main orchestrator.
        def drain():
            with (folder / 'injector.log').open('w') as f:
                for line in proc.stdout:
                    f.write(line.replace(PASSWORD, '[REDACTED]')); f.flush()
                    if '>>> 执行模式:' in line:
                        result['fault_start_ms'] = round(time.time()*1000)
        reader = threading.Thread(target=drain, daemon=True)
        reader.start()
        try:
            result['exit_code'] = proc.wait(timeout=100)
        except subprocess.TimeoutExpired:
            proc.kill(); proc.wait(timeout=10)
            result['exit_code'] = -9
            result['timeout'] = True
        reader.join(timeout=5)
        result['fault_end_ms'] = round(time.time()*1000)
        result['elapsed_s'] = round((result['fault_end_ms']-result['launch_ms'])/1000, 3)
        time.sleep(15)
        result['database_after'] = metrics(name)
        state = json.loads(docker('inspect', '-f', '{{json .State}}', name).stdout)
        result['oom_killed'] = state['OOMKilled']
        result['container_running'] = state['Running']
        result['status'] = 'completed' if result['exit_code'] == 0 else 'injector_failed'
    except Exception as e:
        result['error'] = str(e).replace(PASSWORD, '[REDACTED]')
    finally:
        if proc is not None and proc.poll() is None:
            proc.kill(); proc.wait(timeout=10)
        (folder / 'stop').touch()
        if probe is not None:
            try: probe.wait(timeout=12)
            except subprocess.TimeoutExpired: probe.kill(); probe.wait(timeout=5)
            result['probe_exit_code'] = probe.returncode
        stop.set()
        if sampler is not None: sampler.join(timeout=22)
        if 'fault_end_ms' in result:
            result['probes'] = {kind: summarize_probe(folder / (kind + '.csv'), result['fault_start_ms'],
                                                    result['fault_end_ms']) for kind in ('fresh', 'persistent')}
        try:
            logs = docker('logs', '--tail', '150', name, check=False).stdout
            (folder / 'container.log').write_text(logs.replace(PASSWORD, '[REDACTED]'))
            docker('stop', '-t', '20', name, timeout=35)
        except Exception as e:
            result['stop_error'] = str(e)
        (folder / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
        RESULTS.append(result); write_summary()
        print('DONE ' + label + ' ' + result['status'], flush=True)


def main():
    PRIVATE.mkdir(parents=True, mode=0o700, exist_ok=True)
    PRIVATE.chmod(0o700)
    print('OUTPUT ' + str(OUT), flush=True)
    (PRIVATE / 'credentials.json').write_text(json.dumps({'user': USER, 'password': PASSWORD, 'database': 'chaoslab'}))
    (PRIVATE / 'credentials.json').chmod(0o600)
    classes = OUT / 'classes'; classes.mkdir(exist_ok=True)
    build = command(['javac', '--release', '8', '-encoding', 'UTF-8', '-cp', str(ROOT / 'lib/*'), '-d', str(classes),
                     *map(str, (ROOT / 'src').rglob('*.java')), str(Path(__file__).with_name('SessionProbe.java'))], timeout=60)
    (OUT / 'build.log').write_text(build.stdout)
    if build.returncode: raise RuntimeError('Compilation failed')
    image = json.loads(docker('image', 'inspect', IMAGE, '-f', '{{json .Id}}').stdout)
    manifest = {'run_id': RUN, 'image': IMAGE, 'image_id': image, 'duration_ms': DURATION,
                'baseline_s': 8, 'recovery_s': 15, 'rounds': 3, 'workload': 'SELECT 1 availability probes; not TPC-C',
                'probe_rates': {'persistent_per_second': 5, 'fresh_per_second': 1},
                'source_sha256': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                                  for p in (ROOT / 'src').rglob('*.java')}, 'profiles': []}
    try:
        for cpu, mem, process_mem, buffers, pool in PROFILES:
            profile = f'{cpu}c{mem}g'; name = 'dbchaos-session-' + profile + '-' + RUN
            conf = [f'shared_buffers={buffers}GB', f'max_process_memory={process_mem}GB', 'max_connections=100',
                    'enable_thread_pool=on', f"thread_pool_attr='{pool},1,(nobind)'", 'max_prepared_transactions=100']
            env = dict(os.environ, GS_PASSWORD=PASSWORD, GS_USERNAME='chaos_bootstrap', GS_DB='chaoslab',
                       OTHER_PG_CONF='\n'.join(conf))
            existing = docker('inspect', '-f', '{{index .Config.Labels "dbchaos.experiment"}}', name, check=False)
            if existing.returncode == 0:
                if existing.stdout.strip() != RUN:
                    raise RuntimeError('Refusing to reuse container with another experiment label')
                docker('start', name)
            else:
                docker('run', '--pull=never', '-d', '--name', name, '--label', 'dbchaos.experiment=' + RUN,
                       '--cpus', cpu, '--memory', f'{mem}g', '--memory-swap', f'{mem}g', '--shm-size', '1g',
                       '-p', '127.0.0.1::5432', '-e', 'GS_PASSWORD', '-e', 'GS_USERNAME', '-e', 'GS_DB',
                       '-e', 'OTHER_PG_CONF', IMAGE, env=env, timeout=90)
            CREATED.append(name)
            ready(name)
            # The image is a lite build whose appended defaults override OTHER_PG_CONF.
            # Reapply requested settings after initialization, then restart and verify.
            guc = ['exec', '-u', 'omm', '-e', 'LD_LIBRARY_PATH=/usr/local/opengauss/lib', '-e', 'GAUSSHOME=/usr/local/opengauss', name,
                   '/usr/local/opengauss/bin/gs_guc', 'set', '-D', '/var/lib/opengauss/data']
            for setting in conf:
                guc.extend(['-c', setting])
            docker(*guc)
            docker('restart', '-t', '20', name, timeout=45)
            ready(name)
            if sql(name, f"SELECT count(*) FROM pg_roles WHERE rolname='{USER}';") == '0':
                sql(name, f"CREATE USER {USER} WITH PASSWORD '{PASSWORD}'; GRANT CONNECT ON DATABASE chaoslab TO {USER};")
            monitor_sql = (ROOT / 'scripts/probe/install_connection_monitor.sql').read_text().replace(':"monitor_user"', '"' + USER + '"')
            sql(name, monitor_sql)
            settings = sql(name, "SELECT name,setting FROM pg_settings WHERE name IN ('max_connections',"
                           "'max_process_memory','shared_buffers','enable_thread_pool','thread_pool_attr',"
                           "'superuser_reserved_connections') ORDER BY name;")
            if 'enable_thread_pool|on' not in settings or f'thread_pool_attr|{pool},' not in settings:
                raise RuntimeError('Requested database configuration not applied: ' + settings)
            port = json.loads(docker('inspect', '-f', '{{json .NetworkSettings.Ports}}', name).stdout)['5432/tcp'][0]['HostPort']
            private_profile = PRIVATE / profile; private_profile.mkdir(mode=0o700, exist_ok=True)
            config = private_profile / 'db.properties'
            config.write_text(f'type=opengauss\nurl=jdbc:opengauss://127.0.0.1:{port}/chaoslab?connectTimeout=4&socketTimeout=45&ApplicationName=dbchaos_session_matrix\nuser={USER}\npassword={PASSWORD}\n')
            config.chmod(0o600)
            probe_config = config.with_name('probe.properties')
            probe_config.write_text(config.read_text().replace('socketTimeout=45', 'socketTimeout=5').replace('ApplicationName=dbchaos_session_matrix', 'ApplicationName=dbchaos_session_probe'))
            probe_config.chmod(0o600)
            host = json.loads(docker('inspect', '-f', '{{json .HostConfig}}', name).stdout)
            entry = {'profile': profile, 'container': name, 'port': port, 'cpu': cpu, 'memory_gib': mem,
                     'max_process_memory_gib': process_mem, 'shared_buffers_gib': buffers, 'pool_workers': pool,
                     'settings': settings, 'version': sql(name, 'SELECT version();'),
                     'role': sql(name, f"SELECT rolname,rolsuper,rolsystemadmin FROM pg_roles WHERE rolname='{USER}';"),
                     'limits': {k: host[k] for k in ['NanoCpus', 'Memory', 'MemorySwap', 'ShmSize']}}
            manifest['profiles'].append(entry)
            (OUT / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
            docker('stop', '-t', '20', name, timeout=35)
            print('CREATED ' + profile + ' ' + name, flush=True)
        for entry in manifest['profiles']:
            profile, name = entry['profile'], entry['container']
            config = PRIVATE / profile / 'db.properties'
            cp = ':'.join([str(config.parent), str(classes), str(ROOT / 'resources'), str(ROOT / 'lib/*')])
            for repetition in range(1, 4):
                for mode in MODES:
                    experiment(profile, name, mode, repetition, cp, config)
    finally:
        for name in CREATED:
            docker('stop', '-t', '20', name, timeout=35, check=False)
    print('FINISHED ' + str(len(RESULTS)) + ' experiments: ' + str(OUT), flush=True)


if __name__ == '__main__':
    main()
