"""Check isolated recovery routing and failure propagation without an emulator."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / '.github/scripts/run-lifecycle-suite.sh'


class LifecycleSuiteTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        (self.work / 'scripts').mkdir()
        (self.work / 'bin').mkdir()
        stub = '''#!/usr/bin/env python3
import json, os, pathlib, sys
with open('calls.jsonl', 'a') as f: f.write(json.dumps([pathlib.Path(sys.argv[0]).name, *sys.argv[1:]]) + '\\n')
sys.exit(int(os.environ.get('FAIL_STATUS', '0')))
'''
        for name in ('gradlew', 'bin/adb', 'scripts/m13-process-death.sh'):
            path = self.work / name
            if name.startswith('scripts/'):
                path.write_text('exec python3 "$(dirname "$0")/../recorder.py" "$0" "$@"\n')
            else:
                path.write_text(stub)
            path.chmod(0o755)
        (self.work / 'recorder.py').write_text(stub.replace(
            "pathlib.Path(sys.argv[0]).name, *sys.argv[1:]",
            "pathlib.Path(sys.argv[1]).name, *sys.argv[2:]"))
        self.env = dict(os.environ, ANDROID_SDK_ROOT='/synthetic/sdk',
                        PATH=str(self.work / 'bin') + os.pathsep + os.environ['PATH'])

    def run_suite(self, *args, **env):
        return subprocess.run(['bash', str(SCRIPT), *args], cwd=self.work,
                              env=dict(self.env, **env), capture_output=True, text=True, timeout=10)

    def calls(self):
        return [json.loads(s) for s in (self.work / 'calls.jsonl').read_text().splitlines()]

    def test_each_test_job_runs_only_its_own_journeys(self):
        for suite, script, count in [('production', 'm13-process-death.sh', 3)]:
            before = len(self.calls()) if (self.work / 'calls.jsonl').exists() else 0
            result = self.run_suite(suite, 'test')
            self.assertEqual(0, result.returncode, result.stderr)
            calls = self.calls()[before:]
            self.assertEqual(count, len(calls))
            self.assertEqual([script], calls[-1])
            self.assertFalse(any(c[0] == 'gradlew' for c in calls))
            self.assertIn('(exit 0)', result.stdout)

    def test_rejects_the_retired_upgrade_suite(self):
        self.assertEqual(2, self.run_suite('upgrade', 'test').returncode)

    def test_failure_stops_the_suite_and_is_preserved(self):
        result = self.run_suite('production', 'test', FAIL_STATUS='17')
        self.assertEqual(17, result.returncode)
        self.assertEqual(1, len(self.calls()))
        log = self.work / "app/build/reports/device-ci/lifecycle.log"
        self.assertIn("Install production APK", log.read_text())
        self.assertIn("(exit 17)", log.read_text())

    def test_invalid_input_cannot_start_tools(self):
        for args in [('production', 'build'), ('other', 'test'), ('beta', 'other'), ('beta',), ('beta', 'test', 'extra')]:
            self.assertNotEqual(0, self.run_suite(*args).returncode)
        self.assertFalse((self.work / 'calls.jsonl').exists())

    def test_learning_correction_survives_real_runner_phase_boundaries(self):
        # Exercise the actual recovery script: both methods must run on one
        # device, with force-stop between them, outside the orchestrated shards.
        adb = self.work / 'bin/adb'
        adb.write_text('''#!/usr/bin/env python3
import json, pathlib, sys
with open('calls.jsonl', 'a') as output:
    output.write(json.dumps(['adb', *sys.argv[1:]]) + "\\n")
if 'getprop' in sys.argv: print('36')
if any('am instrument' in arg for arg in sys.argv): print('OK (1 test)')
''')
        result = subprocess.run(['bash', str(ROOT / 'scripts/m13-process-death.sh')],
                                cwd=self.work, env=self.env, text=True,
                                capture_output=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.calls()
        methods = [(index, call[-1]) for index, call in enumerate(calls)
                   if 'LearningProcessDeathJourneyTest#' in call[-1]]
        self.assertEqual(2, len(methods))
        arm, resume = methods
        self.assertIn('#armAfterFinancialCorrectionBeforeLearning', arm[1])
        self.assertIn('#resumeFinancialCorrectionAndBlockNextTemplate', resume[1])
        self.assertIn('-e m13Resume true', resume[1])
        self.assertEqual([['adb', 'shell', 'am', 'force-stop', 'com.baraa.masroof']],
                         calls[arm[0] + 1:resume[0]])
        config = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('com.baraa.masroof.instrumentation.processdeath', config)


if __name__ == '__main__':
    unittest.main()
