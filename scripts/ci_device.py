"""Build provenance, balanced coverage and strict native Orchestrator execution."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import shlex
import shutil
import subprocess
import sys
import time
import tomllib
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
BUNDLE = ROOT / 'app/build/ci-device'
CONFIG = ROOT / 'ci/device-suites.json'


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def source_cases(root=ROOT):
    cases = set()
    for path in (root / 'app/src/androidTest').rglob('*.kt'):
        source = path.read_text()
        package = re.search(r'^package\s+([\w.]+)', source, re.M)
        klass = re.search(r'\bclass\s+(\w+Test)\b', source)
        if not package or not klass:
            continue
        # Runtime log-only discovery below checks this inventory against compiled tests.
        for method in re.finditer(r'@Test\b(?:(?!@Test).)*?\bfun\s+(\w+)\s*\(', source, re.S):
            case = f'{package[1]}.{klass[1]}#{method[1]}'
            if case in cases: raise ValueError(f'Duplicate test: {case}')
            cases.add(case)
    return cases


def classify(cases, config):
    stateful = {case for pair in config['processDeath'] for case in pair}
    upgrade = set(config['upgrade'])
    excluded = set(config['excluded'])
    if stateful & upgrade or (stateful | upgrade) & excluded:
        raise ValueError('Suites overlap')
    if not (stateful | upgrade | excluded) <= cases:
        raise ValueError('A registered lifecycle case no longer exists')
    unknown = {c for c in cases - stateful - upgrade if '.processdeath.' in c or '.upgrade.' in c}
    if unknown: raise ValueError(f'Unpaired lifecycle phases: {sorted(unknown)}')
    return {'primary': sorted(cases - stateful - upgrade - excluded),
            'processDeath': sorted(stateful), 'upgrade': sorted(upgrade), 'excluded': sorted(excluded)}


def partition(cases, count, weights):
    groups = [[] for _ in range(count)]
    totals = [0.0] * count
    for case in sorted(cases, key=lambda c: (-weights.get(c, 10), c)):
        index = min(range(count), key=lambda i: (totals[i], i))
        groups[index].append(case)
        totals[index] += weights.get(case, 10)
    return groups


def signing_certificate(output):
    # Build Tools 34-36 use "Signer #1"; 37 labels each verified signature scheme.
    # Source-stamp certificates are separate and cannot stand in for an APK signer.
    counts = re.findall(r'^Number of signers: (\d+)$', output, re.M)
    certificates = set(re.findall(
        r'^(?:Signer(?: #\d+| \(.*\))|V\d+(?:\.\d+)* Signer(?: #\d+)?):? certificate SHA-256 digest: ([0-9a-fA-F]{64})$',
        output, re.M))
    if counts != ['1'] or len(certificates) != 1:
        raise ValueError('Expected exactly one verified APK signer certificate')
    return certificates.pop().lower()


def apk_identity(path, build_tools):
    sdk = Path(os.environ.get('ANDROID_SDK_ROOT', os.environ.get('ANDROID_HOME', '')))
    tools = sdk / 'build-tools' / build_tools
    if not tools.is_dir(): raise ValueError(f'Validated Android Build Tools {build_tools} are required')
    signed = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(path)], text=True)
    certificate = signing_certificate(signed)
    badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(path)], text=True)
    package = re.search(r"package: name='([^']+)'", badging)
    if not package: raise ValueError('APK package identity is absent')
    return dict(package=package[1], certificate=certificate, buildTools=build_tools)


def bundle():
    config = json.loads(CONFIG.read_text())
    cases = source_cases()
    suites = classify(cases, config)
    weights = json.loads((ROOT / 'ci/device-durations.json').read_text())
    BUNDLE.mkdir(parents=True, exist_ok=True)
    artifacts = {'app': ROOT / 'app/build/outputs/apk/debug/app-debug.apk',
                 'tests': ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
                 'baseline': ROOT / 'app/build/upgrade-baseline/app/build/outputs/apk/debug/app-debug.apk'}
    versions = tomllib.loads((ROOT / 'gradle/libs.versions.toml').read_text())['versions']
    for key, group, name, ref in [('orchestrator', 'androidx/test', 'orchestrator', 'androidxTestOrchestrator'),
                                  ('services', 'androidx/test/services', 'test-services', 'androidxTestServices')]:
        version = versions[ref]
        target = BUNDLE / f'{key}.apk'
        url = f'https://dl.google.com/dl/android/maven2/{group}/{name}/{version}/{name}-{version}.apk'
        with urllib.request.urlopen(url, timeout=60) as response: target.write_bytes(response.read())
        artifacts[key] = target
    paths = {}
    for key, source in artifacts.items():
        target = BUNDLE / f'{key}.apk'
        if source != target: shutil.copyfile(source, target)
        paths[key] = dict(file=target.name, sha256=digest(target), **apk_identity(target, config['buildTools']))
    expected_packages = dict(app=config['appId'], baseline=config['appId'], tests=config['appId']+'.test',
                             orchestrator='androidx.test.orchestrator', services='androidx.test.services')
    for key, package in expected_packages.items():
        if paths[key]['package'] != package: raise ValueError(f'Wrong APK identity: {key}')
    if len({paths[key]['certificate'] for key in ['app', 'tests', 'baseline']}) != 1:
        raise ValueError('Baseline, application and tests must share the same test signing certificate')
    sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    manifest = dict(version=1, testedSha=sha, sourceSha=os.environ.get('PR_HEAD_SHA', sha), config=config,
                    suites=suites, shards=partition(suites['primary'], config['shards'], weights), apks=paths)
    print('Estimated case seconds per shard:', [round(sum(weights.get(c, 10) for c in g)) for g in manifest['shards']])
    (BUNDLE / 'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
    print(f"Bundled {len(cases)} cases for {sha}; shard sizes {[len(g) for g in manifest['shards']]}")


class Instrumentation:
    def __init__(self):
        self.bundle = {}
        self.key = None
        self.active = None
        self.started = 0.0
        self.results = {}
        self.run_code = None
        self.errors = []

    def line(self, line):
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, _, value = line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            self.key = key
            self.bundle[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = int(line.split(': ', 1)[1])
            case = self.bundle.get('class', '') + '#' + self.bundle.get('test', '')
            if case != '#':
                if code == 1:
                    self.active, self.started = case, time.monotonic()
                elif code in (0, -1, -2, -3, -4):
                    if case in self.results: self.errors.append(f'Duplicate result: {case}')
                    self.results[case] = dict(status='passed' if code == 0 else 'skipped' if code in (-3, -4) else 'failed',
                                              seconds=max(0, time.monotonic() - self.started),
                                              detail=self.bundle.get('stack', self.bundle.get('stream', '')))
                    self.active = None
            self.bundle, self.key = {}, None
        elif line.startswith('INSTRUMENTATION_CODE: '):
            self.run_code = int(line.split(': ', 1)[1])
        elif line.startswith(('INSTRUMENTATION_FAILED:', 'INSTRUMENTATION_ABORTED:')):
            self.errors.append(line)
        elif self.key and not line.startswith('INSTRUMENTATION_'):
            self.bundle[self.key] += '\n' + line

    def complete(self, expected):
        missing = set(expected) - self.results.keys()
        extra = self.results.keys() - set(expected)
        if self.run_code != -1 or missing or extra or self.errors:
            raise ValueError(f'Incomplete instrumentation: code={self.run_code}, missing={sorted(missing)}, extra={sorted(extra)}, errors={self.errors}')
        if any(r['status'] != 'passed' for r in self.results.values()):
            raise ValueError('A required case failed or was skipped')

    def xml(self, path, expected):
        root = ET.Element('testsuite', name='production-device', tests=str(len(expected)))
        for case in expected:
            result = self.results.get(case, dict(status='failed', seconds=0, detail='No completed result'))
            klass, method = case.split('#', 1)
            node = ET.SubElement(root, 'testcase', classname=klass, name=method, time=str(round(result['seconds'], 3)))
            if result['status'] != 'passed':
                ET.SubElement(node, 'failure', message=result['status']).text = result['detail']
        for error in self.errors:
            ET.SubElement(ET.SubElement(root, 'testcase', classname='runner', name='protocol'), 'failure').text = error
        root.set('tests', str(len(root.findall('testcase'))))
        root.set('failures', str(len(root.findall('.//failure'))))
        root.set('time', str(round(sum(r['seconds'] for r in self.results.values()), 3)))
        ET.ElementTree(root).write(path, encoding='utf-8', xml_declaration=True)


def adb(*args, check=True, timeout=30):
    return subprocess.run(['adb', *args], check=check, capture_output=True, text=True, timeout=timeout)


def collect(directory):
    directory.mkdir(parents=True, exist_ok=True)
    for name, args in [('device.log', ['logcat', '-d']), ('jobs.txt', ['shell', 'dumpsys', 'jobscheduler']),
                       ('activity.txt', ['shell', 'dumpsys', 'activity', 'processes'])]:
        try: (directory / name).write_text(adb(*args, check=False, timeout=10).stdout)
        except subprocess.TimeoutExpired: (directory / name).write_text('Diagnostic command timed out')
    for remote, local in [('/sdcard/m19-statement-screenshots/.', 'statement'),
                          ('/sdcard/masroof-ci/.', 'cases')]:
        try: adb('pull', remote, str(directory / local), check=False, timeout=30)
        except subprocess.TimeoutExpired: pass


def execute(command, expected, config, directory, discovery=False):
    parser = Instrumentation()
    process = subprocess.Popen(['adb', 'shell', command], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    started = time.monotonic()
    pending = b''
    last_progress = started
    try:
        with (directory / ('discovery.log' if discovery else 'instrumentation.log')).open('w') as log:
            while selector.get_map():
                for key, _ in selector.select(1):
                    data = os.read(key.fileobj.fileno(), 65536)
                    if not data:
                        selector.unregister(key.fileobj)
                        continue
                    pending += data
                    while b'\n' in pending:
                        raw, pending = pending.split(b'\n', 1)
                        line = raw.decode(errors='replace').rstrip('\r')
                        log.write(line+'\n'); log.flush(); parser.line(line)
                now = time.monotonic()
                if now-last_progress >= 10:
                    print(f'{len(parser.results)}/{len(expected)} complete; active={parser.active} elapsed={int(now-started)}s', flush=True)
                    last_progress = now
                timeout = config.get('timeouts', {}).get(parser.active, 120)
                if (parser.active and now-parser.started > timeout) or now-started > (60 if discovery else 900):
                    parser.errors.append(f'Timeout: {parser.active or "runner"}')
                    collect(directory)
                    adb('shell', 'am', 'force-stop', config['appId'], check=False)
                    raise ValueError(parser.errors[-1])
            if pending: parser.line(pending.decode(errors='replace').rstrip('\r'))
            if process.wait(timeout=10) != 0: raise ValueError('adb instrumentation command failed')
            parser.complete(expected)
            return parser
    except Exception as error:
        if str(error) not in parser.errors: parser.errors.append(str(error))
        raise
    finally:
        selector.close()
        if process.poll() is None: process.kill(); process.wait()
        parser.xml(directory / ('discovery.xml' if discovery else 'results.xml'), expected)
        if not discovery:
            (directory / 'results.json').write_text(json.dumps(parser.results, indent=2))


def verify_bundle():
    manifest = json.loads((BUNDLE / 'manifest.json').read_text())
    sha = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    if manifest['testedSha'] != sha: raise ValueError('Test APK belongs to another checkout')
    suites = classify(source_cases(), manifest['config'])
    if suites != manifest['suites']: raise ValueError('Test inventory differs from the APK bundle')
    flattened = [c for group in manifest['shards'] for c in group]
    if len(flattened) != len(set(flattened)) or set(flattened) != set(suites['primary']):
        raise ValueError('Shards must cover each required case exactly once')
    for item in manifest['apks'].values():
        if digest(BUNDLE / item['file']) != item['sha256']: raise ValueError('APK checksum mismatch')
    return manifest


def restore():
    manifest = verify_bundle()
    for key, target in [('app', ROOT / 'app/build/outputs/apk/debug/app-debug.apk'),
                        ('tests', ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'),
                        ('baseline', ROOT / 'app/build/upgrade-baseline/app/build/outputs/apk/debug/app-debug.apk')]:
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(BUNDLE / manifest['apks'][key]['file'], target)


def phase(case, resume):
    manifest = verify_bundle()
    config = manifest['config']
    if case not in manifest['suites']['processDeath'] + manifest['suites']['upgrade']:
        raise ValueError('Unregistered lifecycle phase')
    directory = ROOT / 'app/build/reports/device-ci/phases' / case.replace('#', '-')
    directory.mkdir(parents=True, exist_ok=True)
    args = ['am', 'instrument', '-w', '-r', '-e', 'clearPackageData', 'false', '-e', 'ciDiagnostics', 'true',
            '-e', 'class', case]
    if resume: args += ['-e', 'm13Resume', 'true']
    args.append(config['runner'])
    try:
        execute(shlex.join(args), [case], config, directory)
        print(f'PASS: {case}', flush=True)
        (directory / 'device.log').write_text(adb('logcat', '-d', check=False).stdout)
    except Exception:
        collect(directory)
        raise


def run(index):
    manifest = verify_bundle()
    sha = manifest['testedSha']
    config = manifest['config']
    if not adb('get-serialno').stdout.strip().startswith('emulator-'): raise ValueError('Only an isolated emulator may be used')
    if not 0 <= index < len(manifest['shards']): raise ValueError('Invalid shard index')
    actual_api = adb('shell', 'getprop', 'ro.build.version.sdk').stdout.strip()
    if actual_api != str(config['api']): raise ValueError(f'Unexpected device API: {actual_api}')
    directory = ROOT / f'app/build/reports/device-ci/shard-{index}'
    directory.mkdir(parents=True, exist_ok=True)
    try:
        for key in ['app', 'tests', 'orchestrator', 'services']:
            adb('install', '--force-queryable', '-r', '-t', str(BUNDLE / manifest['apks'][key]['file']), timeout=90)
        all_cases = sorted(c for group in manifest['suites'].values() for c in group)
        discovery = shlex.join(['am', 'instrument', '-w', '-r', '-e', 'log', 'true', config['runner']])
        execute(discovery, all_cases, config, directory, discovery=True)
        service = adb('shell', 'pm', 'path', 'androidx.test.services').stdout.strip().removeprefix('package:')
        expected = manifest['shards'][index]
        arguments = ['app_process', '/', 'androidx.test.services.shellexecutor.ShellMain', 'am', 'instrument', '-w', '-r',
                     '-e', 'targetInstrumentation', config['runner'], '-e', 'clearPackageData', 'true',
                     '-e', 'ciDiagnostics', 'true', '-e', 'class', ','.join(expected), 'androidx.test.orchestrator/.AndroidTestOrchestrator']
        execute('CLASSPATH='+shlex.quote(service)+' '+shlex.join(arguments), expected, config, directory)
        print(f'PASS: {len(expected)} required cases on {sha}')
    finally:
        collect(directory)


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('command', choices=['bundle', 'run', 'restore', 'phase', 'diagnostics'])
    p.add_argument('--shard', type=int, default=0)
    p.add_argument('--case')
    p.add_argument('--resume', action='store_true')
    a = p.parse_args()
    try:
        if a.command == 'bundle': bundle()
        elif a.command == 'run': run(a.shard)
        elif a.command == 'restore': restore()
        elif a.command == 'phase': phase(a.case, a.resume)
        else: collect(ROOT / 'app/build/reports/device-ci/diagnostics')
    except (ValueError, KeyError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as e:
        sys.exit(str(e))
