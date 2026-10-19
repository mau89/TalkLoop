#!/usr/bin/env python3
"""Create a disposable PR with a removed authorization guard and run the real reviewer."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

import review_pr


README = '''# TalkLoop review demo
This fixture represents private chat history, not the production implementation.
Only the owner can load their history. Unauthenticated cross-owner reads are forbidden.
'''
DOCS = '''# Private chat history contract
HistoryService.load(ownerId, callerId) must check ownerId == callerId before accessing storage.
A different caller must receive an error, never another user's messages.
HistoryStore.load does not enforce access control; authorization belongs to HistoryService.
'''
BEFORE = '''interface HistoryStore {
    fun load(ownerId: String): List<String>
}

class HistoryService(private val store: HistoryStore) {
    fun load(ownerId: String, callerId: String): List<String> {
        require(ownerId == callerId) { "Access denied" }
        return store.load(ownerId)
    }
}
'''
AFTER = BEFORE.replace('        require(ownerId == callerId) { "Access denied" }\n', '')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--provider', choices=('anthropic', 'ollama'), default='ollama')
    parser.add_argument('--model')
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--output', type=Path, default=Path('day32-data/demo'))
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='talkloop-review-demo-') as temporary:
        root = Path(temporary)
        def git(*command):
            return subprocess.run(['git', '-C', str(root), *command], check=True, capture_output=True).stdout.decode().strip()
        git('init', '-b', 'demo-base')
        git('config', 'user.name', 'TalkLoop Demo')
        git('config', 'user.email', 'demo@example.invalid')
        for name, text in [('README.md', README), ('docs/access.md', DOCS), ('core/src/HistoryService.kt', BEFORE)]:
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding='utf-8')
        git('add', '.')
        git('commit', '-m', 'Contract: private history requires owner authorization')
        base = git('rev-parse', 'HEAD')
        (root / 'core/src/HistoryService.kt').write_text(AFTER, encoding='utf-8')
        git('add', '.')
        git('commit', '-m', 'Remove owner check')
        arguments = ['--repo', str(root), '--base', base, '--head', 'HEAD', '--provider', args.provider,
                     '--output', str(args.output)]
        if args.model:
            arguments.extend(['--model', args.model])
        if args.prepare_only:
            arguments.append('--prepare-only')
        result = review_pr.main(arguments)
    if result == 0 and not args.prepare_only:
        report = json.loads((args.output / 'review.json').read_text())
        bugs = [f for f in report['review']['findings'] if f['category'] == 'bugs']
        # A smoke check of the location/category, not a claim of semantic proof or production accuracy.
        expected = any(f['file'] == 'core/src/HistoryService.kt' and f['side'] == 'old' and f['line'] == 7 for f in bugs)
        evaluation = {'fixture': 'removed owner authorization before private history read',
                      'expectedLocationFoundInBugs': expected, 'bugCount': len(bugs),
                      'note': 'Искусственный контрольный пример; качество на реальных PR оценивается отдельно.'}
        review_pr.write_json(args.output / 'smoke-check.json', evaluation)
        print('Проверка контрольной строки:', 'найдена' if expected else 'не найдена')
        if not expected:
            return 1
    return result


if __name__ == '__main__':
    raise SystemExit(main())
