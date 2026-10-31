#!/usr/bin/env python3
"""Isolated real-openGauss validation; creates and removes only its own container.

Requires local Docker permission and the cached image below; never pulls images.
Logs contain real driver errors; no SQL RAISE or fabricated SQLException is used.
"""
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / 'docs/testing/reports/opengauss-report-20261019'
IMAGE = 'opengauss/opengauss:7.0.0-RC2.B015'
NAME = 'dbchaos-report-validation-' + secrets.token_hex(4)
OUTPUT.mkdir(parents=True, exist_ok=True)
PASSWORD = 'Report@' + secrets.token_hex(10) + '9Z'


def run(args, **kwargs):
    return subprocess.run(args, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=kwargs.pop('timeout', 30), **kwargs)


def docker(*args, **kwargs):
    return run(['docker', *args], **kwargs)


def sql(query):
    result = docker('exec', '-i', '-u', 'omm', '-e',
                    'LD_LIBRARY_PATH=/usr/local/opengauss/lib', NAME,
                    '/usr/local/opengauss/bin/gsql', '-v', 'ON_ERROR_STOP=1',
                    '-d', 'reportdb', '-t', '-A', input=query)
    if result.returncode:
        raise RuntimeError(result.stdout.replace(PASSWORD, '[REDACTED]'))
    return result.stdout.strip()


created = False
results = []
try:
    env = dict(os.environ, GS_PASSWORD=PASSWORD, GS_USERNAME='reportadmin', GS_DB='reportdb',
               OTHER_PG_CONF='shared_buffers=128MB\\nmax_process_memory=2GB\\nmax_connections=50')
    start = docker('run', '--pull=never', '-d', '--name', NAME,
                   '--label', 'dbchaos.experiment=report-validation',
                   '--cpus', '2', '--memory', '4g', '--memory-swap', '4g',
                   '--shm-size', '256m', '--tmpfs', '/reportspace:rw,size=16m,mode=1777',
                   '-p', '127.0.0.1::5432', '-e', 'GS_PASSWORD', '-e', 'GS_USERNAME',
                   '-e', 'GS_DB', '-e', 'OTHER_PG_CONF', IMAGE, env=env, timeout=90)
    if start.returncode:
        raise RuntimeError(start.stdout)
    created = True
    print('Created isolated container ' + NAME, flush=True)
    stable = 0
    for attempt in range(90):
        try:
            stable = stable + 1 if sql('SELECT 1;') == '1' else 0
            if stable >= 5:
                break
        except RuntimeError:
            stable = 0
        time.sleep(2)
    else:
        raise RuntimeError('Database did not become ready')
    inspect = docker('inspect', '--format', '{{json .NetworkSettings.Ports}}', NAME)
    port = json.loads(inspect.stdout)['5432/tcp'][0]['HostPort']
    version = sql('SELECT version();')
    info = {'image': IMAGE, 'version': version, 'container': NAME,
            'constraints': '2 CPU, 4 GiB RAM; isolated tablespace on 16 MiB tmpfs',
            'source_sha256': hashlib.sha256((ROOT / 'src/chaos/core/BaseFaultInject.java').read_bytes()).hexdigest()}
    (OUTPUT / 'environment.json').write_text(json.dumps(info, ensure_ascii=False, indent=2))
    mkdir = docker('exec', NAME, 'sh', '-c', 'mkdir /reportspace/og && chown omm /reportspace/og && chmod 700 /reportspace/og')
    if mkdir.returncode:
        raise RuntimeError(mkdir.stdout)
    sql("CREATE TABLESPACE report_small LOCATION '/reportspace/og';\n"
        "GRANT CREATE ON TABLESPACE report_small TO reportadmin;\n"
        "REVOKE CREATE ON SCHEMA public FROM PUBLIC;\n"
        "CREATE USER report_readonly PASSWORD '" + PASSWORD + "';\n"
        "ALTER ROLE report_readonly SET search_path = public;\n"
        "CREATE USER report_limited PASSWORD '" + PASSWORD + "' CONNECTION LIMIT 1;\n"
        "CREATE TABLE public.protected_report (id int PRIMARY KEY);\n"
        "INSERT INTO public.protected_report VALUES (1);\n"
        "GRANT ALL ON public.protected_report TO reportadmin;\n"
        "CREATE TABLE public.deadlock_report (id int PRIMARY KEY, val int);\n"
        "INSERT INTO public.deadlock_report VALUES (1,0),(2,0);\n"
        "GRANT ALL ON public.deadlock_report TO reportadmin;\n")
    with tempfile.TemporaryDirectory(prefix='dbchaos-report-') as temporary:
        temp = Path(temporary)
        classes = temp / 'classes'
        classes.mkdir()
        sources = [str(p) for p in (ROOT / 'src').rglob('*.java')]
        compile_result = run(['javac', '--release', '8', '-encoding', 'UTF-8',
                              '-cp', str(ROOT / 'lib/*'), '-d', str(classes),
                              *sources, str(Path(__file__).with_name('ReportProbe.java'))])
        if compile_result.returncode:
            raise RuntimeError(compile_result.stdout)

        def config(name, user='reportadmin', bad_password=False, bad_port=False):
            directory = temp / name
            directory.mkdir()
            path = directory / 'db.properties'
            password = PASSWORD + 'wrong' if bad_password else PASSWORD
            address = '1' if bad_port else port
            path.write_text('type=opengauss\nurl=jdbc:opengauss://127.0.0.1:' + address
                            + '/reportdb?connectTimeout=3&socketTimeout=10\nuser='
                            + user + '\npassword=' + password + '\n')
            path.chmod(0o600)
            return directory

        # name, user, exit code, report fragments, environmental suggestion expected
        scenarios = [
            ('baseline', 'reportadmin', 0, ['异常记录数=0'], False),
            ('bad_password', 'reportadmin', 1, ['执行状态=执行失败', '认证配置'], True),
            ('connection_refused', 'reportadmin', 1, ['执行状态=执行失败', '网络连通性'], True),
            ('worker_permission', 'report_readonly', 0, ['42501', '存在错误记录', '权限'], True),
            ('connection_limit', 'report_limited', 1, ['53300', '连接限额'], True),
            ('disk_full', 'reportadmin', 0, ['53100', '数据盘', '存在错误记录'], True),
            ('missing_setting', 'reportadmin', 1, ['42704', '数据库版本'], True),
            ('missing_table', 'reportadmin', 0, ['42P01', '目标表'], True),
            ('duplicate', 'reportadmin', 0, ['23505', '存在错误记录'], False),
            ('deadlock', 'reportadmin', 0, ['40P01', '存在错误记录'], False),
            ('client_oom', 'reportadmin', 1, ['OutOfMemoryError', 'JVM -Xmx'], True),
            ('cli_permission', 'report_readonly', 1, ['42501', '执行状态=执行失败', '权限'], True),
        ]
        environment = {key: value for key, value in os.environ.items() if not key.startswith('DBCHAOS_PREFLIGHT_')}
        for name, user, exit_code, fragments, expect_hint in scenarios:
            conf = config(name, user, name == 'bad_password', name == 'connection_refused')
            cp = ':'.join([str(conf), str(classes), str(ROOT / 'resources'), str(ROOT / 'lib/*')])
            command = ['java', '-Xmx16m' if name == 'client_oom' else '-Xmx128m', '-cp', cp]
            if name == 'cli_permission':
                command += ['chaos.Main', '--db', 'opengauss', 'buffer', 'memory_pressure',
                            '-duration', '500', '-batch', '1', '-threads', '1']
            else:
                command += ['ReportProbe', str(conf / 'db.properties'), name]
            case = run(command, env=environment, timeout=25)
            log = case.stdout.replace(PASSWORD + 'wrong', '[REDACTED]').replace(PASSWORD, '[REDACTED]')
            (OUTPUT / (name + '.txt')).write_text(log)
            failures = []
            if case.returncode != exit_code:
                failures.append('exit code %s != %s' % (case.returncode, exit_code))
            for fragment in fragments:
                if fragment not in log:
                    failures.append('missing ' + fragment)
            if ('检查建议' in log) != expect_hint:
                failures.append('unexpected environmental suggestion presence')
            result = {'scenario': name, 'passed': not failures, 'exit_code': case.returncode,
                      'failures': failures, 'log': name + '.txt'}
            results.append(result)
            print(name + ': ' + ('PASS' if not failures else 'FAIL ' + '; '.join(failures)), flush=True)
        (OUTPUT / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
    print('Database health: ' + sql('SELECT 1;'), flush=True)
finally:
    if created:
        logs = docker('logs', NAME)
        (OUTPUT / 'container.txt').write_text(logs.stdout.replace(PASSWORD, '[REDACTED]'))
        # Name is random and created by this invocation; never remove an existing container.
        removed = docker('rm', '-f', '-v', NAME)
        (OUTPUT / 'cleanup.json').write_text(json.dumps({'container': NAME, 'removed': removed.returncode == 0}))
        print('Isolated container removed: ' + str(removed.returncode == 0), flush=True)
if not results or not all(row['passed'] for row in results):
    raise SystemExit(1)
