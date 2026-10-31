"""Resilience metrics, live file ingestion, missing evidence and report lifecycle."""
import json
import csv
from pathlib import Path
import subprocess
import tempfile
import os
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ResilienceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build = tempfile.TemporaryDirectory(prefix='resilience-classes-')
        subprocess.run(['javac', '--release', '8', '-encoding', 'UTF-8', '-cp', str(ROOT / 'lib/*'),
                        '-d', cls.build.name, *map(str, (ROOT / 'src').rglob('*.java')),
                        str(ROOT / 'tests/ResilienceRegression.java')], check=True)
        cls.cp = ':'.join([cls.build.name, str(ROOT / 'resources'), str(ROOT / 'lib/*')])

    @classmethod
    def tearDownClass(cls):
        cls.build.cleanup()

    def test_numeric_analysis(self):
        r = subprocess.run(['java', '-cp', self.cp, 'ResilienceRegression'], capture_output=True, text=True, timeout=8)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn('Numeric resilience checks passed', r.stdout)

    def test_live_series_and_recovery(self):
        for mode in ['recovered', 'degraded', 'stale', 'truncated', 'preflight_failure', 'invalid_parameter', 'execution_failure', 'partial_line', 'malformed', 'mixed_runs', 'estimated', 'slow_available', 'slow_recovery']:
            with self.subTest(mode=mode), tempfile.TemporaryDirectory(prefix='resilience-feed-') as directory:
                r = subprocess.run(['java', '-cp', self.cp, 'ResilienceRegression', mode, directory],
                                   capture_output=True, text=True, timeout=14)
                self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
                report = json.loads((Path(directory) / 'report/resilience.json').read_text())
                saved_text = (Path(directory) / 'report/report.txt').read_text()
                self.assertTrue(saved_text.startswith('=== DBChaos 测试报告 ==='))
                for heading in ['隔离效果好不好', '故障期间是否可用', '撤销后恢复如何', '阶段指标对比']:
                    self.assertIn(heading, saved_text)
                with (Path(directory) / 'report/summary.csv').open() as stream:
                    summary = list(csv.DictReader(stream))
                conclusions = [row for row in summary if row['类别'] == '核心结论']
                self.assertEqual(len(conclusions), 3)
                self.assertEqual(conclusions[0]['结论或数值'], report['isolation'])
                self.assertEqual(conclusions[1]['结论或数值'], report['continuity'])
                self.assertEqual(conclusions[2]['结论或数值'], report['recovery'])
                self.assertAlmostEqual(report['P0'], 100, delta=2)
                if mode in ['recovered', 'slow_available']:
                    self.assertTrue(report['recovered'], r.stdout)
                    self.assertTrue(report['evidence_complete'], r.stdout)
                    if mode == 'recovered':
                        self.assertGreater(report['max_drop_pct'], 40)
                        self.assertLess(report['max_drop_pct'], 60)
                    else:
                        self.assertEqual(report['business_availability'], 'available_slow')
                        self.assertEqual(report['isolation_grade'], 'poor')
                        self.assertGreater(report['fault_p95_multiplier'], 8)
                    self.assertGreaterEqual(report['restored_baseline_pct'], 95)
                    self.assertGreater(report['tr_epoch_ms'], report['fault_withdrawal_epoch_ms'])
                else:
                    self.assertFalse(report['recovered'], r.stdout)
                    self.assertIsNone(report['tr_epoch_ms'])
                    if mode == 'degraded':
                        self.assertLess(report['restored_baseline_pct'], 60)
                        self.assertTrue(report['recovery_stable'])
                    if mode == 'slow_recovery':
                        self.assertTrue(report['throughput_recovered'])
                        self.assertIn(report['recovery'], saved_text)
                        self.assertIn('确认恢复耗时 未确认', saved_text)
                        self.assertEqual(report['recovery_status'], 'quality_not_recovered')
                        self.assertGreater(report['recovery_p95_ms'], report['baseline_p95_ms'] * 5)
                    if mode == 'stale':
                        self.assertIsNone(report['Pr'])
                    if mode == 'estimated':
                        self.assertFalse(report['time_alignment_exact'])
                        self.assertFalse(report['evidence_complete'])
                    if mode in ['truncated', 'malformed', 'mixed_runs']:
                        self.assertIsNotNone(report['monitoring_error'])
                        self.assertFalse(report['evidence_complete'])
                    if mode == 'partial_line':
                        self.assertIsNone(report['monitoring_error'])
                        with (Path(directory) / 'report/throughput.csv').open() as stream:
                            rows = list(csv.DictReader(stream))
                        self.assertEqual(sum(int(row['successes']) for row in rows if row['phase'] == 'fault'), 1)
                    if mode in ['preflight_failure', 'invalid_parameter']:
                        self.assertIsNone(report['t0_epoch_ms'])
                        self.assertIsNone(report['Pd'])
                        self.assertFalse(report['evidence_complete'])
                        self.assertEqual(report['failure_phase'], '预检')
                        self.assertTrue(report['failure_samples'])
                        saved = (Path(directory) / 'report/report.txt').read_text()
                        self.assertIn('[TestReport]', saved)
                        self.assertIn('具体失败原因', saved)
                        self.assertNotIn('N/A', saved)
                        if mode == 'invalid_parameter':
                            self.assertIn('-threads 必须为正整数', saved)
                            self.assertIn('预检', report['unavailable_reasons']['t0'])
                    if mode == 'execution_failure':
                        self.assertTrue(report['execution_failed'])
                        self.assertFalse(report['evidence_complete'])

    def test_existing_report_summary_preserves_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            reason = '预检失败：权限不足,"quoted"\n下一行'
            source = json.dumps({'execution_failed': True, 'evidence_complete': False,
                                 'failure_phase': '预检', 'failure_samples': [reason],
                                 'isolation': '无法评估', 'continuity': '无法评估', 'recovery': '无法评估',
                                 'unavailable_reasons': {'t0': reason}}, ensure_ascii=False)
            (path / 'resilience.json').write_text(source)
            (path / 'report.txt').write_text('原始异常与执行记录\n')
            for _ in range(2):
                r = subprocess.run(['java', '-cp', self.cp, 'chaos.monitor.ResilienceReport', directory],
                                   capture_output=True, text=True, timeout=5)
                self.assertEqual(r.returncode, 0, r.stderr)
            text = (path / 'report.txt').read_text()
            self.assertEqual(text.count('=== DBChaos 测试报告 ==='), 1)
            self.assertIn('测试状态：测试执行失败', text)
            self.assertIn('数据库探针：未评估', text)
            self.assertIn(reason, text)
            self.assertTrue(text.endswith('原始异常与执行记录\n'))
            self.assertEqual((path / 'resilience.json').read_text(), source)
            with (path / 'summary.csv').open() as stream:
                rows = list(csv.DictReader(stream))
            self.assertTrue(any(row['结论或数值'] == reason for row in rows))

    def test_cli_option_validation(self):
        for args in [['--recovery-timeout', '0'], ['--recovery-ratio', 'NaN'],
                     ['--recovery-ratio', '101'], ['--isolation-max-drop', '101'],
                     ['--report-dir', '/tmp'], ['--recovery-timeout', '100000000000000000']]:
            with self.subTest(args=args):
                r = subprocess.run(['java', '-cp', self.cp, 'chaos.Main', 'txn', 'deadlock_storm', *args],
                                   capture_output=True, text=True, timeout=8)
                self.assertEqual(r.returncode, 2, r.stdout + r.stderr)

    def test_frozen_history_cannot_supply_live_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'result.csv'
            source.write_text('run,elapsed,latency,dblatency,ttype,rbk,dskipped,error\n' +
                              ''.join(f'1,{i},1,1,UPDATE,0,0,0\n' for i in range(0, 5000, 10)))
            os.utime(source, (time.time() - 60, time.time() - 60))
            r = subprocess.run(['java', '-cp', self.cp, 'chaos.Main', 'quota', 'test_case', '-duration', '100',
                                '--wait-stable', directory, '--baseline-window', '1', '--baseline-stable-windows', '2',
                                '--baseline-min-wait', '0', '--baseline-max-wait', '1', '--baseline-poll', '50'],
                               capture_output=True, text=True, timeout=5)
            self.assertEqual(r.returncode, 3, r.stdout + r.stderr)
            self.assertNotIn('[Resilience] 全程', r.stdout)
