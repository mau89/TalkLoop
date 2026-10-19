import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import prepare_release as release


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.folder = Path(self.temp.name)
        self.root = self.folder / 'repo'
        self.root.mkdir()
        self.sdk = self.folder / 'sdk'
        (self.sdk / 'build-tools/35.0.0').mkdir(parents=True)
        for name in ['aapt', 'apksigner']:
            (self.sdk / 'build-tools/35.0.0' / name).write_text('tool placeholder used with mock subprocess')
        for name, content in [('README.md', '# TalkLoop\n'), ('gradlew', '#!/bin/sh\nexit 0\n'),
                              ('app/Main.kt', 'fun main() {}\n')]:
            path = self.root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(content)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Release test')
        self.git('config', 'user.email', 'test@example.invalid')
        self.git('add', '.')
        self.git('commit', '-qm', 'Initial')
        self.base = self.git('rev-parse', 'HEAD')
        (self.root / 'app/Main.kt').write_text('fun main() { println("Files") }\n')
        self.git('add', '.')
        self.git('commit', '-qm', 'Add project files screen')
        self.head = self.git('rev-parse', 'HEAD')
        self.output = self.folder / 'result'
        self.calls = []
        self.environment = patch.dict(os.environ, {'ANDROID_HOME': str(self.sdk)})
        self.environment.start()

    def tearDown(self):
        self.environment.stop(); self.temp.cleanup()

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], text=True).strip()

    def notes(self, evidence):
        self.calls.append(evidence)
        return {'summary': 'Добавлен экран файлов.', 'changes': [{'text': 'Поиск по проекту.', 'commit': self.head}],
                'manualChecks': ['Открыть экран и проверить поиск.']}

    def gate(self, name, command, snapshot, output, report, env, timeout=0):
        (output / (name + '.log')).write_text('Passed\n')
        report['gates'].append({'name': name, 'state': 'passed', 'log': name + '.log'})
        self.assertFalse((snapshot / 'secrets.properties').exists())
        if name != 'gradle':
            return
        self.assertIn('-Ptalkloop.distribution=true', command)
        for directory in ['core/build/test-results/jvmTest', 'server/build/test-results/test']:
            path = snapshot / directory; path.mkdir(parents=True)
            (path / 'TEST-suite.xml').write_text('<testsuite tests="2" failures="0" errors="0" skipped="0"/>')
        apk = snapshot / 'app/androidApp/build/outputs/apk/release/test-unsigned.apk'
        apk.parent.mkdir(parents=True)
        with zipfile.ZipFile(apk, 'w') as package:
            package.writestr('classes.dex', b'Fake APK for pipeline gate tests')
        config = snapshot / 'app/androidApp/build/generated/source/buildConfig/release/com/mau89/talkloop/BuildConfig.java'
        config.parent.mkdir(parents=True)
        config.write_text('public static final String ANTHROPIC_API_KEY = "";')

    def prepare(self, provider=None, gate=None):
        metadata = {'applicationId': 'com.mau89.talkloop', 'version': '1.1.0', 'versionCode': 2,
                    'variant': 'release', 'signed': False, 'embeddedCloudKey': False}
        with patch.object(release, 'inspect_apk', return_value=metadata):
            return release.prepare(self.root, self.base, '1.1.0', 2, self.output,
                                   provider=provider or self.notes, gate=gate or self.gate)

    def test_candidate_uses_committed_code_not_dirty_sources_or_local_secrets(self):
        (self.root / 'secrets.properties').write_text('private key must stay local')
        (self.root / 'app/Main.kt').write_text('Dirty uncommitted code\n')
        self.assertEqual(0, self.prepare(), (self.output / 'release.json').read_text())
        report = json.loads((self.output / 'release.json').read_text())
        self.assertEqual('prepared', report['state'])
        self.assertFalse(report['publishingReady'])
        self.assertEqual(self.head, report['head'])
        self.assertEqual(4, report['tests']['tests'])
        self.assertTrue(report['ignoredWorkingTreeChanges'])
        apk = self.output / report['apk']['file']
        self.assertEqual(release.sha256(apk), report['apk']['sha256'])
        self.assertNotIn('Dirty uncommitted', json.dumps(self.calls[0]))
        self.assertFalse(self.calls[0]['releaseFacts']['cloudKeyEmbedded'])
        self.assertFalse(self.calls[0]['releaseFacts']['signed'])
        with zipfile.ZipFile(self.output / 'TalkLoop-1.1.0-candidate.zip') as package:
            self.assertIn('release-notes.md', package.namelist())
            self.assertNotIn('secrets.properties', package.namelist())
        with self.assertRaises(release.ReleaseError): self.prepare()

    def test_failed_checks_block_candidate_before_ai(self):
        def failed(*args, **kwargs):
            raise release.ReleaseError('Tests failed')
        self.assertEqual(1, self.prepare(gate=failed))
        self.assertFalse(self.calls)
        self.assertEqual('blocked', json.loads((self.output / 'release.json').read_text())['state'])
        self.assertFalse(list(self.output.glob('*candidate.zip')))

    def test_missing_ai_keeps_verified_apk_but_never_claims_complete_candidate(self):
        def unavailable(_): raise release.ReleaseError('Ollama unavailable')
        self.assertEqual(1, self.prepare(provider=unavailable))
        report = json.loads((self.output / 'release.json').read_text())
        self.assertEqual('blocked', report['state'])
        self.assertIsNotNone(report['apk'])
        self.assertFalse((self.output / 'release-notes.md').exists())
        self.assertFalse(list(self.output.glob('*candidate.zip')))

    def test_fabricated_commit_and_broken_model_shape_are_rejected(self):
        good = self.notes({})
        for bad in [{}, {**good, 'manualChecks': []}, {**good, 'changes': [{'text': 'Invented', 'commit': 'f' * 40}]}]:
            with self.assertRaises(release.ReleaseError): release.validate_notes(bad, [{'sha': self.head}])

    def test_invalid_versions_and_option_like_refs_are_rejected(self):
        for version, code in [('1.1', 2), ('../1.1.0', 2), ('1.1.0', 0), ('1.1.0', 2100000001)]:
            with self.assertRaises(release.ReleaseError): release.validate_version(version, code)
        for ref in ['--help', 'HEAD ;whoami', '']:
            with self.assertRaises(release.ReleaseError): release.resolve(self.root, ref)

    def test_archive_never_follows_tracked_symlinks(self):
        (self.root / 'link').symlink_to('/tmp')
        self.git('add', 'link'); self.git('commit', '-qm', 'Bad link')
        destination = self.folder / 'snapshot'; destination.mkdir()
        with self.assertRaises(release.ReleaseError):
            release.export_snapshot(self.root, self.git('rev-parse', 'HEAD'), destination, self.folder / 'source.zip')

    def test_apk_validation_rejects_wrong_version_debug_and_embedded_key(self):
        apk = self.folder / 'test.apk'
        with zipfile.ZipFile(apk, 'w') as package:
            package.writestr('classes.dex', b'sk-ant-FAKE-TEST-KEY-NOT-A-CREDENTIAL')
        valid = "package: name='com.mau89.talkloop' versionCode='2' versionName='1.1.0'\n"
        for text in [valid, valid + 'application-debuggable', valid.replace("versionCode='2'", "versionCode='1'")]:
            responses = [subprocess.CompletedProcess([], 0, text, ''), subprocess.CompletedProcess([], 1, b'', b'')]
            with patch.object(subprocess, 'run', side_effect=responses):
                with self.assertRaises(release.ReleaseError): release.inspect_apk(apk, self.sdk, '1.1.0', 2)

    def test_zero_discovered_python_tests_fail_the_gate(self):
        self.output.mkdir()
        report = {'gates': []}
        with self.assertRaises(release.ReleaseError):
            release.command_gate('python-empty', [__import__('sys').executable, '-m', 'unittest', 'discover', '-s', '.'],
                                 self.root, self.output, report, dict(os.environ), timeout=10)
        self.assertEqual('failed', report['gates'][0]['state'])

    def test_incomplete_newer_sdk_is_skipped_for_complete_installed_tools(self):
        (self.sdk / 'build-tools/37.0.0').mkdir()
        apk = self.folder / 'test.apk'
        with zipfile.ZipFile(apk, 'w') as package:
            package.writestr('classes.dex', b'no embedded key')
        responses = [subprocess.CompletedProcess([], 0, "package: name='com.mau89.talkloop' versionCode='2' versionName='1.1.0'\n", ''),
                     subprocess.CompletedProcess([], 1, b'', b'')]
        with patch.object(subprocess, 'run', side_effect=responses) as tool:
            self.assertFalse(release.inspect_apk(apk, self.sdk, '1.1.0', 2)['embeddedCloudKey'])
            self.assertIn('/35.0.0/aapt', tool.call_args_list[0].args[0][0])

    def test_missing_failed_or_skipped_xml_tests_are_not_success(self):
        with self.assertRaises(release.ReleaseError): release.test_counts(self.folder)
        for directory in ['core/build/test-results/jvmTest', 'server/build/test-results/test']:
            path = self.folder / directory; path.mkdir(parents=True)
            (path / 'TEST-suite.xml').write_text('<testsuite tests="3" skipped="1"/>')
        with self.assertRaises(release.ReleaseError): release.test_counts(self.folder)


if __name__ == '__main__':
    unittest.main()
