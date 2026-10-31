"""Report math, SQL error attribution and connection-fault teardown without a live database."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ConnectionReportTests(unittest.TestCase):
    def test_report_regressions(self):
        with tempfile.TemporaryDirectory(prefix='connection-report-') as classes:
            subprocess.run(['javac', '--release', '8', '-encoding', 'UTF-8', '-cp', str(ROOT/'lib/*'),
                            '-d', classes, *map(str, (ROOT/'src').rglob('*.java')),
                            str(ROOT/'tests/ConnectionReportRegression.java')], check=True)
            result = subprocess.run(['java', '-cp', ':'.join([classes, str(ROOT/'resources'), str(ROOT/'lib/*')]),
                                     'ConnectionReportRegression'], capture_output=True, text=True, timeout=25)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('Connection report regression checks passed', result.stdout)
