#!/usr/bin/env python3
"""Install, pause, resume, inspect or remove the dedicated TalkLoop Mac runner.

Installation writes only ~/.local/share/talkloop-actions-runner,
~/.local/share/talkloop-runner-policy and the runner's user LaunchAgent.
Uses the existing gh login. Registration/removal tokens are never printed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tarfile
import urllib.request

REPOSITORY = 'mau89/TalkLoop'
NAME = 'talkloop-mac-a1'
LABEL = 'talkloop-review-mac'
ROOT = Path.home() / '.local/share/talkloop-actions-runner'
POLICY = Path.home() / '.local/share/talkloop-runner-policy'


def run(arguments, cwd=None, private_value=None):
    result = subprocess.run(arguments, cwd=cwd, text=True, capture_output=True)
    if result.returncode:
        output = result.stderr or result.stdout
        if private_value:
            output = output.replace(private_value, '[redacted]')
        raise RuntimeError(output.strip() or 'Command failed')
    return result.stdout


def api(endpoint, method='GET'):
    arguments = ['gh', 'api', endpoint]
    if method != 'GET':
        arguments.extend(['--method', method])
    output = run(arguments)
    return json.loads(output) if output.strip() else None


def remote_runner():
    return next((r for r in api(f'repos/{REPOSITORY}/actions/runners')['runners'] if r['name'] == NAME), None)


def install():
    if platform.system() != 'Darwin' or platform.machine() != 'arm64':
        raise RuntimeError('This installer is for this Apple Silicon Mac only.')
    if sys.version_info < (3, 12):
        raise RuntimeError('Use Python 3.12+: .day21-venv/bin/python tools/day32/mac_runner.py install')
    if not shutil.which('gh'):
        raise RuntimeError('GitHub CLI gh is required.')
    existing = remote_runner()
    if existing and not (ROOT / '.runner').exists():
        raise RuntimeError('A runner with this name already exists elsewhere; installation stopped.')
    ROOT.mkdir(parents=True, exist_ok=True)
    POLICY.mkdir(parents=True, exist_ok=True)
    os.chmod(ROOT, 0o700)
    os.chmod(POLICY, 0o700)
    if not (ROOT / 'config.sh').exists():
        release = api('repos/actions/runner/releases/latest')
        asset = next(a for a in release['assets'] if a['name'].startswith('actions-runner-osx-arm64-') and a['name'].endswith('.tar.gz'))
        digest = asset.get('digest', '')
        if not digest.startswith('sha256:'):
            raise RuntimeError('Official release has no SHA-256 digest; installation stopped.')
        url = asset['browser_download_url']
        if not url.startswith('https://github.com/actions/runner/releases/download/'):
            raise RuntimeError('Unexpected download URL.')
        archive = ROOT / asset['name']
        if not archive.exists():
            print('Downloading official GitHub Actions runner...', flush=True)
            urllib.request.urlretrieve(url, archive)
        checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
        if checksum != digest.removeprefix('sha256:'):
            raise RuntimeError('Runner archive SHA-256 mismatch; archive was not executed.')
        # The archive is authenticated by its official digest. Extract only in the dedicated directory.
        with tarfile.open(archive) as package:
            package.extractall(ROOT, filter='data')
        (POLICY / 'installation.json').write_text(json.dumps({
            'repository': REPOSITORY, 'name': NAME, 'label': LABEL,
            'runnerVersion': release['tag_name'], 'sha256': checksum,
            'runnerDirectory': str(ROOT), 'policyDirectory': str(POLICY),
        }, indent=2) + '\n')
        archive.unlink()
    shutil.copyfile(Path(__file__).with_name('runner_guard.py'), POLICY / 'runner_guard.py')
    hook = POLICY / 'job-started.sh'
    hook.write_text('#!/bin/bash\nset -eu\nexec /usr/bin/python3 -I "' + str(POLICY / 'runner_guard.py') + '"\n')
    hook.chmod(0o700)
    if not (ROOT / '.runner').exists():
        token = api(f'repos/{REPOSITORY}/actions/runners/registration-token', 'POST')['token']
        print('Registering dedicated review runner...', flush=True)
        run(['./config.sh', '--unattended', '--url', f'https://github.com/{REPOSITORY}',
             '--token', token, '--name', NAME, '--work', '_work', '--labels', LABEL,
             '--no-default-labels'], cwd=ROOT, private_value=token)
    environment = ROOT / '.env'
    entries = environment.read_text().splitlines() if environment.exists() else []
    entries = [line for line in entries if not line.startswith('ACTIONS_RUNNER_HOOK_JOB_STARTED=')]
    entries.append('ACTIONS_RUNNER_HOOK_JOB_STARTED=' + str(hook))
    environment.write_text('\n'.join(entries) + '\n')
    environment.chmod(0o600)
    if not (ROOT / '.service').exists():
        run(['./svc.sh', 'install'], cwd=ROOT)
    run(['./svc.sh', 'start'], cwd=ROOT)
    print(f'Installed {NAME}; label {LABEL}; host policy enabled.')


def control(action):
    if action == 'status':
        runner = remote_runner()
        print(json.dumps({'name': NAME, 'registered': runner is not None,
                          'status': runner['status'] if runner else 'not_registered',
                          'busy': runner['busy'] if runner else False,
                          'directory': str(ROOT)}, ensure_ascii=False, indent=2))
        if (ROOT / 'svc.sh').exists():
            print(run(['./svc.sh', 'status'], cwd=ROOT))
        return
    if not (ROOT / 'svc.sh').exists():
        raise RuntimeError('Dedicated runner is not installed.')
    if action in ('start', 'stop'):
        print(run(['./svc.sh', action], cwd=ROOT))
        return
    runner = remote_runner()
    if runner and runner['busy']:
        raise RuntimeError('Runner is busy. Wait for the job to finish before removing it.')
    run(['./svc.sh', 'stop'], cwd=ROOT)
    run(['./svc.sh', 'uninstall'], cwd=ROOT)
    if (ROOT / '.runner').exists():
        if runner:
            token = api(f'repos/{REPOSITORY}/actions/runners/remove-token', 'POST')['token']
            run(['./config.sh', 'remove', '--unattended', '--token', token], cwd=ROOT, private_value=token)
        else:
            print('Runner is already absent in GitHub; downloaded files retained.')
    print('Dedicated runner stopped and unregistered. Project files and Ollama were preserved.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('install', 'status', 'start', 'stop', 'remove'))
    args = parser.parse_args()
    try:
        install() if args.action == 'install' else control(args.action)
    except (RuntimeError, OSError, ValueError, StopIteration) as error:
        print(str(error) or 'Runner setup failed.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
