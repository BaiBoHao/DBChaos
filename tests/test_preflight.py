"""Compile and exercise prerequisite checks without contacting a database."""
import os
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class PreflightTests(unittest.TestCase):
    def test_preflight_regressions(self):
        with tempfile.TemporaryDirectory() as classes:
            checker = pathlib.Path(classes) / 'checker.py'
            checker.write_text("""#!/usr/bin/env python3
import os
print('result=pass')
print('target=' + os.environ['DBCHAOS_PREFLIGHT_TARGET'])
print('data_available_bytes=1000000000000000')
print('log_available_bytes=1000000000000000')
print('data_growth_bytes=1000000')
print('log_growth_bytes=1000000')
print('shared_volume=true')
print('table=public.test')
print('hotspot_access_verified=true')
print('hotspot_rows_checked=1000')
print('client_memory_budget_bytes=1000000000000000')
""")
            checker.chmod(0o700)
            environment = dict(os.environ, DBCHAOS_PREFLIGHT_CHECKER=str(checker))
            sources = [str(p) for p in (ROOT / 'src').rglob('*.java')]
            subprocess.run(['javac', '--release', '8', '-encoding', 'UTF-8',
                            '-cp', str(ROOT / 'lib/*'), '-d', classes, *sources,
                            str(ROOT / 'tests/PreflightRegression.java'),
                            str(ROOT / 'tests/EnvironmentRegression.java'),
                            str(ROOT / 'tests/TestReportRegression.java')], check=True)
            no_configuration = {k: v for k, v in os.environ.items()
                                if not k.startswith('DBCHAOS_PREFLIGHT_')}
            no_configuration['DBCHAOS_PREFLIGHT_CHECKER'] = '/nonexistent/checker'
            result = subprocess.run(['java', '-cp', ':'.join([
                classes, str(ROOT / 'resources'), str(ROOT / 'lib/*')]),
                'PreflightRegression'], text=True, stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, timeout=15, env=no_configuration)
            self.assertEqual(result.returncode, 0, result.stdout)
            self.assertIn('Preflight regression checks passed', result.stdout)
            report = subprocess.run(['java', '-cp', ':'.join([
                classes, str(ROOT / 'resources'), str(ROOT / 'lib/*')]),
                'TestReportRegression'], text=True, stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, timeout=8, env=no_configuration)
            self.assertEqual(report.returncode, 0, report.stdout)
            self.assertIn('Test report regression checks passed', report.stdout)
            # Legacy standalone checker contract remains separately covered.
            # A pass status alone must never replace the resource and workload checks.
            for kind, override, expected in [
                ('storage', '', 0),
                ('storage', 'data_available_bytes=10', 1),
                ('storage', 'log_available_bytes=10', 1),
                ('storage', 'data_growth_bytes=0', 1),
                ('storage', 'target=another-server', 1),
                ('storage', 'result=unknown', 1),
                ('storage', 'shared_volume=unknown', 1),
                # 2,000,050 is individually enough for data/log but insufficient for their sum.
                ('storage', 'data_available_bytes=2000050\nlog_available_bytes=2000050\ndata_growth_bytes=2000000', 1),
                ('business', '', 0),
                ('business', 'hotspot_access_verified=false', 1),
                ('business', 'hotspot_rows_checked=999', 1),
                ('business', 'table=another_table', 1),
                ('memory', '', 0),
                ('memory', 'client_memory_budget_bytes=1', 1),
            ]:
                with self.subTest(kind=kind, override=override):
                    original = checker.read_text()
                    checker.write_text(original + '\nprint(' + repr(override) + ')\n')
                    try:
                        result = subprocess.run(['java', '-cp', ':'.join([
                            classes, str(ROOT / 'resources'), str(ROOT / 'lib/*')]),
                            'EnvironmentRegression', kind], text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=8, env=environment)
                        self.assertEqual(result.returncode, expected, result.stdout)
                    finally:
                        checker.write_text(original)
            missing = dict(os.environ)
            missing.pop('DBCHAOS_PREFLIGHT_CHECKER', None)
            for kind in ['storage', 'business', 'memory']:
                result = subprocess.run(['java', '-cp', classes, 'EnvironmentRegression', kind],
                                        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                        timeout=8, env=missing)
                self.assertEqual(result.returncode, 1, result.stdout)
                self.assertIn('未验证则退出', result.stdout)

            local_checker = ROOT / 'scripts/probe/check_fault_prerequisites.py'
            local_env = dict(os.environ)
            local_env.update(DBCHAOS_PREFLIGHT_TARGET='wrong-target',
                             DBCHAOS_PREFLIGHT_SERVER_JDBC_URL='jdbc:test:preflight',
                             DBCHAOS_PREFLIGHT_SERVER_DB_USER='test')
            result = subprocess.run([str(local_checker), 'storage', 'TEST', '30000', '100'],
                                    env=local_env, text=True, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, timeout=8)
            self.assertEqual(result.returncode, 1, result.stdout)
            self.assertIn('differs from JDBC target', result.stdout)
            import hashlib
            local_env.update(DBCHAOS_PREFLIGHT_TARGET=hashlib.sha256(b'jdbc:test:preflight\ntest').hexdigest(),
                             DBCHAOS_PREFLIGHT_DATA_PATH=classes,
                             DBCHAOS_PREFLIGHT_LOG_PATH=classes,
                             DBCHAOS_PREFLIGHT_DATA_BYTES_PER_SEC='1000',
                             DBCHAOS_PREFLIGHT_LOG_BYTES_PER_SEC='2000')
            result = subprocess.run([str(local_checker), 'storage', 'TEST', '30000', '100'],
                                    env=local_env, text=True, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, timeout=8)
            self.assertEqual(result.returncode, 0, result.stdout)
            self.assertIn('shared_volume=true', result.stdout)
            self.assertIn('data_growth_bytes=30000', result.stdout)
            self.assertIn('log_growth_bytes=60000', result.stdout)
            result = subprocess.run([str(local_checker), 'business-hotspots', 'TEST', '30000', 'test', '1'],
                                    env=local_env, text=True, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, timeout=8)
            self.assertEqual(result.returncode, 1, result.stdout)
            self.assertIn('workload-specific', result.stdout)
            cases = [
                ['exec', 'read_amp_trap', '-duration', '100', '-warmup', '100'],
                ['storage', 'mvcc_bloat', '-duration', '0'],
                ['txn', 'uncommitted_txn', '-duration', '100', '-table', 'test', '-holders', '0'],
                ['txn', 'duplicate_txn', '-duration', '100', '-sessions', '0'],
                ['txn', 'deadlock_storm', '-duration', '100', '-hot-rows', '32'],
                ['txn', 'max_prepared', '-duration', '100', '-concurrency', '0'],
                ['buffer', 'memory_pressure', '-duration', '100', '-batch', '2048'],
                ['log', 'massive_rollback', '-duration', '100', '-rate', 'NaN'],
            ]
            for options in cases:
                with self.subTest(options=options):
                    result = subprocess.run(['java', '-cp', ':'.join([
                        classes, str(ROOT / 'resources'), str(ROOT / 'lib/*')]),
                        'chaos.Main', '--db', 'postgresql', *options], text=True,
                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=8)
                    self.assertEqual(result.returncode, 1, result.stdout)
                    self.assertIn('[Preflight]', result.stdout)
                    self.assertIn('预检失败，退出测试', result.stdout)
                    self.assertIn('[TestReport]', result.stdout)
                    self.assertIn('执行状态=执行失败', result.stdout)



if __name__ == '__main__':
    unittest.main()
