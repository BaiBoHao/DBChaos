"""CLI regression checks; real workload/fault evidence is kept in the workspace report."""
import pathlib
import subprocess
import tempfile
import unittest

JAR = pathlib.Path(__file__).resolve().parents[1] / 'target/DBChaos-0.0.1.jar'


class StabilityGateTests(unittest.TestCase):
    def run_cli(self, *args):
        return subprocess.run(['java', '-jar', str(JAR), *args], text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=8)

    def assert_no_injection(self, result):
        self.assertNotIn('[Gate] 性能已稳定，自动开始故障注入', result.stdout)
        self.assertNotIn('启动交叉锁风暴注入', result.stdout)

    def test_timeout_prevents_injection_and_writes_unstable_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            baseline = pathlib.Path(directory) / 'baseline.json'
            result = self.run_cli('txn', 'deadlock_storm', '-duration', '100',
                                  '--wait-stable', directory, '--baseline-min-wait', '0',
                                  '--baseline-max-wait', '1', '--baseline-poll', '50',
                                  '--baseline-json', str(baseline))
            self.assertEqual(result.returncode, 3, result.stdout)
            self.assertIn('"stable": false', baseline.read_text())
            self.assert_no_injection(result)

    def test_help_does_not_wait_or_write_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            baseline = pathlib.Path(directory) / 'baseline.json'
            result = self.run_cli('txn', 'deadlock_storm', '--wait-stable', directory,
                                  '--baseline-json', str(baseline), '--help')
            self.assertEqual(result.returncode, 0, result.stdout)
            self.assertNotIn('[Monitor]', result.stdout)
            self.assertFalse(baseline.exists())
            self.assert_no_injection(result)

    def test_invalid_gate_arguments_fail_without_injection(self):
        cases = [
            ['--wait-stable'],
            ['--baseline-max-wait', '1'],
            ['--wait-stable', '/tmp', '--baseline-on-timeout', 'continue'],
            ['--wait-stable', '/tmp', '--baseline-unknown', '1'],
            ['--wait-stable', '/tmp', '--baseline-window', '0'],
            ['--wait-stable', '/tmp', '--wait-stable', '/tmp'],
        ]
        for options in cases:
            with self.subTest(options=options):
                result = self.run_cli('txn', 'deadlock_storm', '-duration', '100', *options)
                self.assertEqual(result.returncode, 2, result.stdout)
                self.assert_no_injection(result)

    def test_gate_options_before_subsystem_are_parsed(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_cli('--wait-stable', directory, '--baseline-max-wait', '1',
                                  '--baseline-poll', '50', 'txn', 'deadlock_storm', '-duration', '100')
            self.assertEqual(result.returncode, 3, result.stdout)
            self.assert_no_injection(result)

    def test_standalone_monitor_remains_available(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_cli('monitor', 'baseline', '-result-dir', directory,
                                  '-max-wait', '1', '-poll', '50')
            self.assertEqual(result.returncode, 3, result.stdout)
            self.assert_no_injection(result)

    def test_baseline_output_failure_cancels_injection(self):
        with tempfile.TemporaryDirectory() as directory:
            result_file = pathlib.Path(directory) / 'result.csv'
            result_file.write_text('run,elapsed,latency,dblatency,ttype,rbk,dskipped,error\n' +
                                   ''.join('1,%d,1,1,NEW_ORDER,0,0,\n' % t for t in range(0, 4001, 250)))
            # The transactions are stable, but a directory cannot be used as a JSON file.
            result = self.run_cli('txn', 'deadlock_storm', '-duration', '100',
                                  '--wait-stable', directory, '--baseline-window', '1',
                                  '--baseline-min-wait', '0', '--baseline-max-wait', '1',
                                  '--baseline-json', directory)
            self.assertEqual(result.returncode, 1, result.stdout)
            self.assertIn('[Gate] 监控失败，取消故障注入', result.stdout)
            self.assert_no_injection(result)


if __name__ == '__main__':
    unittest.main()
