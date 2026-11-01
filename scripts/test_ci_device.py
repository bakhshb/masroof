import io
import importlib.util
from pathlib import Path
import tempfile
import os
import subprocess
import sys
import textwrap
import json
from unittest.mock import patch
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('device', Path(__file__).with_name('ci_device.py'))
device = importlib.util.module_from_spec(spec)
spec.loader.exec_module(device)


class DeviceProtocolTest(unittest.TestCase):
    def test_workflow_publishes_package_version_separately_from_runtime_api(self):
        workflow = (device.ROOT / '.github/workflows/ci.yml').read_text()
        resolve = workflow.split('      - name: Resolve validated Android image', 1)[1]
        code = textwrap.dedent(resolve.split("python3 - <<'PY'", 1)[1].split('\n          PY', 1)[0])
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'output'
            subprocess.run([sys.executable, '-c', code], cwd=device.ROOT,
                           env=dict(os.environ, GITHUB_OUTPUT=str(output)), check=True, capture_output=True)
            config = json.loads(device.CONFIG.read_text())
            self.assertEqual([f"api-level={config['api']}", f"image-version={config['imageVersion']}"],
                             output.read_text().splitlines())
        self.assertEqual(int(config['imageVersion'].split('.')[0]), config['api'])
        self.assertIn('api-level: ${{ needs.android-version.outputs.image-version }}', workflow)

    def test_workflow_keeps_complete_coverage_and_clean_devices(self):
        workflow = (device.ROOT / '.github/workflows/ci.yml').read_text()
        app = workflow.split('  emulator-app:', 1)[1].split('  emulator-recovery:', 1)[0]
        recovery = workflow.split('  emulator-recovery:', 1)[1].split('  emulator-lifecycle:', 1)[0]
        self.assertIn('shard: [0, 1, 2, 3]', app)
        self.assertIn('scripts/ci_device.py run --shard', app)
        self.assertNotIn('gradlew', app)
        self.assertIn('fail-fast: false', app)
        self.assertIn('suite: [production]', recovery)
        self.assertNotIn('gradlew', recovery)
        self.assertIn('CI_DEVICE_BUNDLE:', recovery)
        for section in [app, recovery]:
            self.assertIn('name: shared-test-apks', section)
            self.assertIn('-no-snapshot', section)
            self.assertIn('if: always()', section)
        gate = workflow.split('  emulator-smoke:', 1)[1]
        self.assertIn('unit-test, static-analysis, emulator-app, emulator-lifecycle', gate)
        self.assertIn('Require complete CI', gate)
        self.assertIn('Require statement reconciliation screenshots', gate)

    def test_device_jobs_reserve_enough_internal_storage_for_api37(self):
        workflow = (device.ROOT / '.github/workflows/ci.yml').read_text()
        options = [line for line in workflow.splitlines() if 'emulator-options:' in line]
        self.assertEqual(2, len(options))  # ordinary shards and stateful recovery
        for line in options:
            self.assertIn('-partition-size 8192', line)

    def test_emulator_creation_uses_current_tools_instead_of_runner_preinstall(self):
        workflow = (device.ROOT / '.github/workflows/ci.yml').read_text()
        name = '      - name: Use current tools for emulator creation'
        steps = workflow.split(name)[1:]
        self.assertEqual(2, len(steps))
        for step in steps:
            code = textwrap.dedent(step.split('        run: |\n', 1)[1].split('      - ', 1)[0])
            for preinstalled_current in (False, True):
                with tempfile.TemporaryDirectory() as directory:
                    root = Path(directory) / 'cmdline-tools'
                    selected = root / ('latest' if preinstalled_current else '23.0')
                    current = selected / 'bin'
                    current.mkdir(parents=True)
                    (selected / 'source.properties').write_text('Pkg.Revision=23.0\n')
                    manager = current / 'avdmanager'
                    manager.write_text('#!/bin/sh\nexit 0\n')
                    manager.chmod(0o755)
                    if not preinstalled_current:
                        (root / 'latest').mkdir()
                        (root / 'latest/source.properties').write_text('Pkg.Revision=12.0')
                    subprocess.run(['bash', '-ec', code],
                                   env=dict(os.environ, ANDROID_HOME=directory, PATH=str(current) + os.pathsep + os.environ['PATH']),
                                   check=True, capture_output=True)
                    self.assertEqual(selected.resolve(), (root / 'latest').resolve())
                    self.assertEqual('Pkg.Revision=23.0\n', (root / 'latest/source.properties').read_text())
        self.assertEqual(2, workflow.count("pre-emulator-launch-script: grep -Fx 'target=android-${{ needs.android-version.outputs.image-version }}'"))
        for path in ['.github/workflows/ci.yml', '.github/workflows/release.yml']:
            source = (device.ROOT / path).read_text()
            count = source.count('uses: android-actions/setup-android@')
            self.assertEqual(count, source.count("cmdline-tools-version: '16111833'"))

    def test_adb_failure_preserves_the_install_error(self):
        failure = subprocess.CalledProcessError(1, ['adb', 'install', 'app.apk'],
                                               output='INSTALL_FAILED_INSUFFICIENT_STORAGE',
                                               stderr='Requested internal only, but not enough space')
        output = io.StringIO()
        with patch.object(device.subprocess, 'run', side_effect=failure), patch.object(device.sys, 'stderr', output):
            with self.assertRaises(subprocess.CalledProcessError) as raised:
                device.adb('install', 'app.apk')
        self.assertIs(failure, raised.exception)
        self.assertIn('INSTALL_FAILED_INSUFFICIENT_STORAGE', output.getvalue())
        self.assertIn('not enough space', output.getvalue())

    def stream(self, status=0, final=True):
        p = device.Instrumentation()
        for line in ['INSTRUMENTATION_STATUS: class=example.CaseTest', 'INSTRUMENTATION_STATUS: test=case',
                     'INSTRUMENTATION_STATUS_CODE: 1', 'INSTRUMENTATION_STATUS: class=example.CaseTest',
                     'INSTRUMENTATION_STATUS: test=case', f'INSTRUMENTATION_STATUS_CODE: {status}']:
            p.line(line)
        if final: p.line('INSTRUMENTATION_CODE: -1')
        return p

    def test_success_requires_completed_expected_case_and_terminal_frame(self):
        self.stream().complete(['example.CaseTest#case'])
        for expected in [[], ['example.CaseTest#other'], ['example.CaseTest#case', 'other#case']]:
            with self.assertRaises(ValueError): self.stream().complete(expected)
        with self.assertRaises(ValueError): self.stream(final=False).complete(['example.CaseTest#case'])

    def test_adb_success_does_not_hide_test_failure_or_skip(self):
        for code in [-1, -2, -3, -4]:
            with self.subTest(code=code), self.assertRaises(ValueError):
                self.stream(code).complete(['example.CaseTest#case'])

    def test_duplicates_and_incomplete_stream_are_failures(self):
        p = self.stream()
        for line in ['INSTRUMENTATION_STATUS: class=example.CaseTest', 'INSTRUMENTATION_STATUS: test=case',
                     'INSTRUMENTATION_STATUS_CODE: 0']: p.line(line)
        with self.assertRaises(ValueError): p.complete(['example.CaseTest#case'])
        with self.assertRaises(ValueError): device.Instrumentation().complete(['missing#case'])

    def test_timeout_and_missing_case_are_visible_in_junit(self):
        p = device.Instrumentation()
        p.errors.append('Timeout: example.CaseTest#case')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'test.xml'
            p.xml(path, ['example.CaseTest#case'])
            root = ET.parse(path).getroot()
            self.assertEqual(2, len(root.findall('.//failure')))
            self.assertIn('Timeout', path.read_text())

    def test_balancing_is_disjoint_complete_and_deterministic(self):
        cases = ['a', 'b', 'c', 'd', 'e', 'f']
        weights = dict(a=100, b=80, c=70, d=20, e=15, f=10)
        groups = device.partition(cases, 3, weights)
        self.assertEqual(set(cases), {c for group in groups for c in group})
        self.assertEqual(len(cases), sum(map(len, groups)))
        self.assertEqual(groups, device.partition(reversed(cases), 3, weights))
        self.assertLessEqual(max(sum(weights[c] for c in g) for g in groups), 100)

    def test_registry_covers_every_real_source_case_and_keeps_phases_together(self):
        import json
        config = json.loads(device.CONFIG.read_text())
        cases = device.source_cases()
        suites = device.classify(cases, config)
        self.assertEqual(cases, {c for group in suites.values() for c in group})
        self.assertEqual(len(cases), sum(map(len, suites.values())))
        self.assertEqual(20, len(suites['processDeath']))
        self.assertEqual(2, len(suites['excluded']))
        self.assertTrue(all(len(pair) == 2 for pair in config['processDeath']))
        with self.assertRaises(ValueError):
            device.classify(cases | {'com.baraa.masroof.instrumentation.processdeath.NewTest#orphan'}, config)

    def test_default_new_tests_are_executed_not_omitted(self):
        import json
        config = json.loads(device.CONFIG.read_text())
        suites = device.classify(device.source_cases() | {'example.NewTest#case'}, config)
        self.assertIn('example.NewTest#case', suites['primary'])

    def test_signer_formats_preserve_exactly_one_verified_certificate(self):
        cert = 'a'*64
        old = f'Number of signers: 1\nSigner #1 certificate SHA-256 digest: {cert}\n'
        modern = f'Number of signers: 1\nV2 Signer: certificate SHA-256 digest: {cert}\nV3.0 Signer: certificate SHA-256 digest: {cert}\n'
        for text in [old, modern, modern + 'Source Stamp Signer certificate SHA-256 digest: ' + 'b'*64]:
            self.assertEqual(cert, device.signing_certificate(text))
        for invalid in [old.replace('signers: 1', 'signers: 2'), modern + 'V3.1 Signer: certificate SHA-256 digest: ' + 'b'*64,
                        'Number of signers: 1\n', old.replace('Number of signers: 1\n', ''), old.replace(cert, 'not-a-digest')]:
            with self.subTest(output=invalid), self.assertRaises(ValueError): device.signing_certificate(invalid)

    def test_bundle_rejects_wrong_commit_changed_apk_and_lost_case(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory)
            apk = bundle / 'app.apk'
            apk.write_bytes(b'verified-apk')
            config = dict(processDeath=[], excluded={})
            manifest = dict(testedSha='a'*40, config=config, suites=dict(primary=['example.Test#case'],
                processDeath=[], excluded=[]), shards=[['example.Test#case']],
                apks=dict(app=dict(file='app.apk', sha256=device.digest(apk))))
            def save(): (bundle / 'manifest.json').write_text(json.dumps(manifest))
            save()
            with patch.object(device, 'BUNDLE', bundle), patch.object(device, 'source_cases', return_value={'example.Test#case'}), \
                    patch.object(device.subprocess, 'check_output', return_value='a'*40):
                device.verify_bundle()
                manifest['testedSha'] = 'b'*40
                save()
                with self.assertRaisesRegex(ValueError, 'another checkout'): device.verify_bundle()
                manifest['testedSha'] = 'a'*40
                manifest['shards'] = [[]]
                save()
                with self.assertRaisesRegex(ValueError, 'cover each'): device.verify_bundle()
                manifest['shards'] = [['example.Test#case', 'example.Test#case']]
                save()
                with self.assertRaisesRegex(ValueError, 'cover each'): device.verify_bundle()
                manifest['shards'] = [['example.Test#case']]
                save()
                apk.write_bytes(b'replaced-apk')
                with self.assertRaisesRegex(ValueError, 'checksum'): device.verify_bundle()

    def test_primary_runner_does_not_rebuild_apks(self):
        workflow = (device.ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn('Build shared test APKs', workflow)
        section = workflow.split('  emulator-app:', 1)[1].split('  emulator-recovery:', 1)[0]
        self.assertNotIn('gradlew', section)
        self.assertIn('scripts/ci_device.py run', section)
        self.assertIn('fail-fast: false', section)


    def test_settle_waits_for_boot_load_and_never_blocks_forever(self):
        from types import SimpleNamespace
        readings = iter(['3.60 2.00 1.00 2/900 77', '1.20 2.00 1.00 1/900 77'])
        def adb(*args, **_):
            out = {'/proc/loadavg': lambda: next(readings), 'nproc': lambda: '4'}.get(args[-1], lambda: '')
            return SimpleNamespace(stdout=out())
        with patch.object(device, 'adb', side_effect=adb) as calls, patch.object(device.time, 'sleep') as sleep:
            device.settle()
        self.assertEqual(('shell', 'am', 'wait-for-broadcast-idle'), calls.call_args_list[0].args)
        self.assertEqual(1, sleep.call_count)
        busy = lambda *args, **_: SimpleNamespace(stdout='4' if args[-1] == 'nproc' else '9.00 9.00 9.00 4/900 77')
        clock = iter(range(0, 1000, 100))
        with patch.object(device, 'adb', side_effect=busy), patch.object(device.time, 'sleep'), \
                patch.object(device.time, 'monotonic', side_effect=lambda: next(clock)):
            device.settle(limit=250)  # returns at the deadline instead of failing the job


if __name__ == '__main__': unittest.main()
